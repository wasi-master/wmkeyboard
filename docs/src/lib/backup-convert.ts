/**
 * Turns a WM Keyboard encrypted backup into the form 0.6.3 opens.
 *
 * A port of the reading half of `BackupCrypto` in
 * core/settings/src/main/java/com/wasimaster/wmkeyboard/core/settings/BackupCrypto.kt
 * and of its `singlePiece` writer, on WebCrypto. Everything happens in the
 * page: no byte of the file and no character of the passphrase goes anywhere.
 *
 * Builds up to 0.6.3 open container 1 under kdf 1 only. 0.6.4 and 0.6.5 wrote
 * container 1 under kdf 2 (PBKDF2-HMAC-SHA512), and later builds write
 * container 2 (64 KiB pieces) for a bundle over 4 MiB. This opens either and
 * seals the plaintext again as container 1, kdf 1, 600,000 rounds, keeping
 * the salt so the file still belongs to the same passphrase setup.
 *
 * No imports, so `node scripts/check-backup-convert.mjs` can load it as is.
 */

const HEADER_SIZE = 44;
const SALT_SIZE = 16;
const NONCE_SIZE = 12;
const TAG_SIZE = 16;
const KEY_BITS = 256;
const MAX_ITERATIONS = 2_000_000;

const CONTAINER_SINGLE = 1;
const CONTAINER_CHUNKED = 2;
const KDF_SHA256 = 1;
const KDF_SHA512 = 2;
const CIPHER_AES_GCM = 1;

const VERSION_OFFSET = 7;
const KDF_OFFSET = 8;
const CIPHER_OFFSET = 9;
const ITERATIONS_OFFSET = 10;
const SALT_LENGTH_OFFSET = 14;
const SALT_OFFSET = 15;
const NONCE_LENGTH_OFFSET = 31;
const NONCE_OFFSET = 32;

/** Plaintext bytes per piece of a container 2 file. */
export const CHUNK_SIZE = 64 * 1024;
/** What the converted file is keyed with: `BackupCrypto.COMPAT_ITERATIONS`. */
export const TARGET_ITERATIONS = 600_000;
/** The largest file 0.6.3's import takes. */
export const OLD_IMPORT_LIMIT = 32 * 1024 * 1024;

const MAGIC = [0x57, 0x4d, 0x4b, 0x42, 0x45, 0x4e, 0x43]; // "WMKB" "ENC"
const CHUNK_KEY_LABEL = new TextEncoder().encode('WMKB chunked v2');

export type Inspection =
	/** Plain JSON or a zip: every version imports it as it is. */
	| { kind: 'plain' }
	/** Not a WM Keyboard backup this page recognises. */
	| { kind: 'unknown' }
	/** An encrypted backup in a layout newer than this page. */
	| { kind: 'unsupported' }
	| {
		kind: 'encrypted';
		container: number;
		kdf: number;
		iterations: number;
		/** Already container 1 under kdf 1: 0.6.3 opens it. */
		compatible: boolean;
	};

/** What the header says, read before anything is checked. */
export function inspect(file: Uint8Array): Inspection {
	if (startsWith(file, MAGIC.slice(0, 4))) {
		if (file.length < HEADER_SIZE || !startsWith(file, MAGIC)) return { kind: 'unknown' };
		const container = file[VERSION_OFFSET];
		const kdf = file[KDF_OFFSET];
		const iterations = readInt(file, ITERATIONS_OFFSET);
		const known =
			(container === CONTAINER_SINGLE || container === CONTAINER_CHUNKED) &&
			(kdf === KDF_SHA256 || kdf === KDF_SHA512) &&
			file[CIPHER_OFFSET] === CIPHER_AES_GCM &&
			file[SALT_LENGTH_OFFSET] === SALT_SIZE &&
			file[NONCE_LENGTH_OFFSET] === NONCE_SIZE &&
			iterations >= 1 &&
			iterations <= MAX_ITERATIONS;
		if (!known) return { kind: 'unsupported' };
		return {
			kind: 'encrypted',
			container,
			kdf,
			iterations,
			compatible: container === CONTAINER_SINGLE && kdf === KDF_SHA256,
		};
	}
	// A zip export, or JSON after optional whitespace and a byte order mark.
	if (file[0] === 0x50 && file[1] === 0x4b) return { kind: 'plain' };
	let i = 0;
	if (file[0] === 0xef && file[1] === 0xbb && file[2] === 0xbf) i = 3;
	while (i < file.length && (file[i] === 0x20 || file[i] === 0x09 || file[i] === 0x0a || file[i] === 0x0d)) i++;
	if (file[i] === 0x7b) return { kind: 'plain' };
	return { kind: 'unknown' };
}

export type Conversion =
	| { ok: true; file: Uint8Array; plainSize: number }
	| { ok: false; reason: 'plain' | 'unknown' | 'unsupported' | 'already' | 'bad' };

/**
 * Opens [file] under [passphrase] and seals it again for 0.6.3. `bad` is a
 * wrong passphrase or a damaged file, which a tag cannot tell apart.
 */
export async function convert(file: Uint8Array, passphrase: string): Promise<Conversion> {
	const seen = inspect(file);
	if (seen.kind !== 'encrypted') return { ok: false, reason: seen.kind };
	if (seen.compatible) return { ok: false, reason: 'already' };

	const plain = await open(file, passphrase, seen.container, seen.kdf, seen.iterations);
	if (!plain) return { ok: false, reason: 'bad' };
	try {
		const salt = file.slice(SALT_OFFSET, SALT_OFFSET + SALT_SIZE);
		return { ok: true, file: await seal(plain, passphrase, salt), plainSize: plain.length };
	} finally {
		plain.fill(0);
	}
}

/** The plaintext, or null when a tag fails. */
async function open(
	file: Uint8Array,
	passphrase: string,
	container: number,
	kdf: number,
	iterations: number,
): Promise<Uint8Array | null> {
	const header = file.slice(0, HEADER_SIZE);
	const salt = file.slice(SALT_OFFSET, SALT_OFFSET + SALT_SIZE);
	const hash = kdf === KDF_SHA512 ? 'SHA-512' : 'SHA-256';
	try {
		if (container === CONTAINER_SINGLE) {
			const key = await deriveAes(passphrase, salt, iterations, hash, ['decrypt']);
			const nonce = file.slice(NONCE_OFFSET, NONCE_OFFSET + NONCE_SIZE);
			return await gcmOpen(key, nonce, header, file.slice(HEADER_SIZE));
		}
		const master = await deriveBits(passphrase, salt, iterations, hash);
		const key = await chunkKey(master, header);
		master.fill(0);
		return await openPieces(file, key, header);
	} catch {
		// OperationError is how WebCrypto reports a failed tag.
		return null;
	}
}

/**
 * Container 2: pieces of CHUNK_SIZE + 16 bytes, the last one the piece the
 * file ends after, which may be shorter and may hold no plaintext at all.
 */
async function openPieces(file: Uint8Array, key: CryptoKey, header: Uint8Array): Promise<Uint8Array> {
	const full = CHUNK_SIZE + TAG_SIZE;
	const parts: Uint8Array[] = [];
	let total = 0;
	let offset = HEADER_SIZE;
	let index = 0;
	for (;;) {
		const n = Math.min(full, file.length - offset);
		if (n < TAG_SIZE) throw new Error('short piece');
		const last = n < full || offset + n === file.length;
		const part = await gcmOpen(key, chunkNonce(index, last), header, file.slice(offset, offset + n));
		parts.push(part);
		total += part.length;
		offset += n;
		index++;
		if (last) break;
	}
	const plain = new Uint8Array(total);
	let at = 0;
	for (const part of parts) {
		plain.set(part, at);
		at += part.length;
		part.fill(0);
	}
	return plain;
}

/** `BackupCrypto.singlePiece`: container 1, kdf 1, the same salt, a new nonce. */
async function seal(plain: Uint8Array, passphrase: string, salt: Uint8Array): Promise<Uint8Array> {
	const nonce = crypto.getRandomValues(new Uint8Array(NONCE_SIZE));
	const header = new Uint8Array(HEADER_SIZE);
	header.set(MAGIC, 0);
	header[VERSION_OFFSET] = CONTAINER_SINGLE;
	header[KDF_OFFSET] = KDF_SHA256;
	header[CIPHER_OFFSET] = CIPHER_AES_GCM;
	writeInt(header, ITERATIONS_OFFSET, TARGET_ITERATIONS);
	header[SALT_LENGTH_OFFSET] = SALT_SIZE;
	header.set(salt, SALT_OFFSET);
	header[NONCE_LENGTH_OFFSET] = NONCE_SIZE;
	header.set(nonce, NONCE_OFFSET);

	const key = await deriveAes(passphrase, salt, TARGET_ITERATIONS, 'SHA-256', ['encrypt']);
	const sealed = new Uint8Array(
		await crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce, additionalData: header, tagLength: 128 }, key, plain),
	);
	const out = new Uint8Array(HEADER_SIZE + sealed.length);
	out.set(header, 0);
	out.set(sealed, HEADER_SIZE);
	return out;
}

async function passphraseKey(passphrase: string): Promise<CryptoKey> {
	return crypto.subtle.importKey('raw', new TextEncoder().encode(passphrase), 'PBKDF2', false, ['deriveBits', 'deriveKey']);
}

async function deriveBits(passphrase: string, salt: Uint8Array, iterations: number, hash: string): Promise<Uint8Array> {
	const base = await passphraseKey(passphrase);
	return new Uint8Array(await crypto.subtle.deriveBits({ name: 'PBKDF2', salt, iterations, hash }, base, KEY_BITS));
}

async function deriveAes(
	passphrase: string,
	salt: Uint8Array,
	iterations: number,
	hash: string,
	usages: KeyUsage[],
): Promise<CryptoKey> {
	const base = await passphraseKey(passphrase);
	return crypto.subtle.deriveKey(
		{ name: 'PBKDF2', salt, iterations, hash },
		base,
		{ name: 'AES-GCM', length: KEY_BITS },
		false,
		usages,
	);
}

/** `BackupCrypto.chunkKey`: HMAC-SHA256(master, label || header). */
async function chunkKey(master: Uint8Array, header: Uint8Array): Promise<CryptoKey> {
	const mac = await crypto.subtle.importKey('raw', master, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
	const input = new Uint8Array(CHUNK_KEY_LABEL.length + header.length);
	input.set(CHUNK_KEY_LABEL, 0);
	input.set(header, CHUNK_KEY_LABEL.length);
	const raw = new Uint8Array(await crypto.subtle.sign('HMAC', mac, input));
	const key = await crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['decrypt']);
	raw.fill(0);
	return key;
}

/** `BackupCrypto.chunkNonce`: seven zero bytes, the index, 1 on the last. */
function chunkNonce(index: number, last: boolean): Uint8Array {
	const nonce = new Uint8Array(NONCE_SIZE);
	writeInt(nonce, 7, index);
	nonce[11] = last ? 1 : 0;
	return nonce;
}

async function gcmOpen(key: CryptoKey, nonce: Uint8Array, aad: Uint8Array, data: Uint8Array): Promise<Uint8Array> {
	return new Uint8Array(
		await crypto.subtle.decrypt({ name: 'AES-GCM', iv: nonce, additionalData: aad, tagLength: 128 }, key, data),
	);
}

function startsWith(bytes: Uint8Array, prefix: number[]): boolean {
	return bytes.length >= prefix.length && prefix.every((b, i) => bytes[i] === b);
}

function readInt(bytes: Uint8Array, offset: number): number {
	return ((bytes[offset] << 24) | (bytes[offset + 1] << 16) | (bytes[offset + 2] << 8) | bytes[offset + 3]) >>> 0;
}

function writeInt(bytes: Uint8Array, offset: number, value: number): void {
	bytes[offset] = (value >>> 24) & 0xff;
	bytes[offset + 1] = (value >>> 16) & 0xff;
	bytes[offset + 2] = (value >>> 8) & 0xff;
	bytes[offset + 3] = value & 0xff;
}

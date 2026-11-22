// Round trip for src/lib/backup-convert.ts, the /privacy/backup-converter/ page.
//
//   node scripts/check-backup-convert.mjs
//
// Seals fixtures with node:crypto the way BackupCrypto writes them (and the
// way BackupCryptoTest builds them): container 1 under PBKDF2-HMAC-SHA512, as
// 0.6.4 and 0.6.5 wrote, and container 2 in 64 KiB pieces. Converts each with
// the page's code, then opens the result the only way 0.6.3 can: PBKDF2-HMAC-
// SHA256 and one AES-GCM pass with the header as associated data. Node 22.18
// or later, for loading the .ts file directly.
import { createCipheriv, createDecipheriv, createHmac, pbkdf2Sync, randomBytes } from 'node:crypto';
import assert from 'node:assert/strict';
import {
	CHUNK_SIZE, TARGET_ITERATIONS, convert, inspect,
} from '../src/lib/backup-convert.ts';

const PASS = 'correct horse ✓ battery';
const salt = randomBytes(16);

function header(container, kdf, iterations, nonce) {
	const h = Buffer.alloc(44);
	h.write('WMKBENC', 0, 'ascii');
	h[7] = container;
	h[8] = kdf;
	h[9] = 1;
	h.writeUInt32BE(iterations, 10);
	h[14] = 16;
	salt.copy(h, 15);
	h[31] = 12;
	nonce.copy(h, 32);
	return h;
}

function gcmSeal(key, nonce, aad, plain) {
	const c = createCipheriv('aes-256-gcm', key, nonce);
	c.setAAD(aad);
	return Buffer.concat([c.update(plain), c.final(), c.getAuthTag()]);
}

function singleSha512(plain, iterations = 210_000) {
	const nonce = randomBytes(12);
	const h = header(1, 2, iterations, nonce);
	const key = pbkdf2Sync(PASS, salt, iterations, 32, 'sha512');
	return Buffer.concat([h, gcmSeal(key, nonce, h, plain)]);
}

function chunked(plain, iterations = 210_000) {
	const h = header(2, 2, iterations, randomBytes(12));
	const master = pbkdf2Sync(PASS, salt, iterations, 32, 'sha512');
	const key = createHmac('sha256', master).update('WMKB chunked v2').update(h).digest();
	const out = [h];
	// A full piece goes out only once another byte follows, so a bundle of
	// whole pieces ends on a full last piece and an empty one is one empty
	// last piece, exactly as ChunkSealer does it.
	let index = 0;
	let offset = 0;
	for (;;) {
		const end = Math.min(offset + CHUNK_SIZE, plain.length);
		const last = end === plain.length;
		const nonce = Buffer.alloc(12);
		nonce.writeUInt32BE(index, 7);
		nonce[11] = last ? 1 : 0;
		out.push(gcmSeal(key, nonce, h, plain.subarray(offset, end)));
		if (last) break;
		offset = end;
		index++;
	}
	return Buffer.concat(out);
}

/** How 0.6.3 opens a file: kdf 1, one GCM pass. */
function openAsOld(file) {
	const h = file.subarray(0, 44);
	assert.equal(h.toString('ascii', 0, 7), 'WMKBENC');
	assert.equal(h[7], 1, 'container 1');
	assert.equal(h[8], 1, 'kdf 1');
	assert.equal(h[9], 1, 'cipher 1');
	assert.equal(h.readUInt32BE(10), TARGET_ITERATIONS);
	assert.deepEqual(h.subarray(15, 31), salt, 'same salt');
	const key = pbkdf2Sync(PASS, h.subarray(15, 31), h.readUInt32BE(10), 32, 'sha256');
	const d = createDecipheriv('aes-256-gcm', key, h.subarray(32, 44));
	d.setAAD(h);
	const body = file.subarray(44);
	d.setAuthTag(body.subarray(body.length - 16));
	return Buffer.concat([d.update(body.subarray(0, body.length - 16)), d.final()]);
}

async function roundTrip(name, file, plain) {
	const r = await convert(new Uint8Array(file), PASS);
	assert.ok(r.ok, `${name}: converted`);
	assert.equal(r.plainSize, plain.length);
	assert.deepEqual(openAsOld(Buffer.from(r.file)), plain, `${name}: 0.6.3 opens it`);
	console.log(`ok  ${name} (${plain.length} bytes)`);
}

const json = Buffer.from(JSON.stringify({ format: 'wmkeyboard-config', v: 1, note: 'héllo' }));
const big = randomBytes(CHUNK_SIZE * 3 + 1234);
const exact = randomBytes(CHUNK_SIZE * 2);

await roundTrip('container 1, SHA-512', singleSha512(json), json);
await roundTrip('container 2, partial last piece', chunked(big), big);
await roundTrip('container 2, full last piece', chunked(exact), exact);
await roundTrip('container 2, empty bundle', chunked(Buffer.alloc(0)), Buffer.alloc(0));

// Wrong passphrase and damage give the same answer.
assert.equal((await convert(new Uint8Array(singleSha512(json)), 'nope')).reason, 'bad');
const cut = chunked(big);
assert.equal((await convert(new Uint8Array(cut.subarray(0, 44 + CHUNK_SIZE + 16)), PASS)).reason, 'bad');
const flipped = chunked(big);
flipped[44 + 10] ^= 1;
assert.equal((await convert(new Uint8Array(flipped), PASS)).reason, 'bad');
const lowered = singleSha512(json);
lowered.writeUInt32BE(1, 10);
assert.equal((await convert(new Uint8Array(lowered), PASS)).reason, 'bad');
console.log('ok  wrong passphrase, cut file, flipped byte, edited header all refused');

// What needs nothing.
const old = (await convert(new Uint8Array(singleSha512(json)), PASS)).file;
assert.equal((await convert(old, PASS)).reason, 'already');
assert.equal(inspect(new Uint8Array(Buffer.from(' \n{"a":1}'))).kind, 'plain');
const tooMany = singleSha512(json);
tooMany.writeUInt32BE(2_000_001, 10);
assert.equal(inspect(new Uint8Array(tooMany)).kind, 'unsupported');
const newer = singleSha512(json);
newer[7] = 3;
assert.equal(inspect(new Uint8Array(newer)).kind, 'unsupported');
console.log('ok  compatible, plain and unsupported files recognised');

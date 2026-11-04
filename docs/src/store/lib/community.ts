/**
 * Sending an addon to the community repository: a port of the app's
 * CommunitySubmission.kt. Change one, change the other; both are held to
 * app/src/test/resources/addons/community-submission-vectors.json.
 *
 * The payload is `wmkb:1:` + base64url (no padding) of the zlib-wrapped
 * DEFLATE of the file's bytes: UTF-8 JSON for a theme, layout or snippet pack,
 * the pack's own zip for an icon, sticker or sound pack. It reaches the issue
 * in the link, pasted, or as an attached zip, whichever is the smallest that fits.
 */
import { strFromU8, strToU8, unzlibSync, zipSync, zlibSync } from 'fflate';

export const COMMUNITY_URL = 'https://github.com/wasi-master/wmkeyboard-community-addons';
export const PREFIX = 'wmkb:1:';
/** Longest link opened with the payload in it; GitHub answers a longer one with an error page. */
export const LINK_LIMIT = 7_500;
/** Longest payload worth pasting: an issue body holds 65,536 characters. */
export const PASTE_LIMIT = 60_000;
export const LICENSES = ['CC0-1.0', 'CC-BY-4.0', 'CC-BY-SA-4.0'] as const;

export type Kind = 'theme' | 'layout' | 'icon_pack' | 'stickers' | 'sound_pack' | 'snippets';
export type Tier = 'link' | 'paste' | 'attach';

/**
 * `binary`: the file is a zip of its own, which GitHub takes as an attachment
 * once it is named `.zip`; the rest are one JSON file, wrapped in a zip.
 */
export const KINDS: Record<Kind, { template: string; fileName: string; title: string; noun: string; binary: boolean }> = {
	theme: { template: 'submit-theme.yml', fileName: 'theme.wmtheme.json', title: '[Theme]', noun: 'theme', binary: false },
	layout: { template: 'submit-layout.yml', fileName: 'layout.wmlayout.json', title: '[Layout]', noun: 'layout', binary: false },
	icon_pack: { template: 'submit-icon-pack.yml', fileName: 'pack.wmicons', title: '[Icon pack]', noun: 'icon pack', binary: true },
	stickers: { template: 'submit-stickers.yml', fileName: 'pack.wmstickers', title: '[Sticker pack]', noun: 'sticker pack', binary: true },
	sound_pack: { template: 'submit-sound-pack.yml', fileName: 'pack.wmsoundpack', title: '[Sound pack]', noun: 'sound pack', binary: true },
	snippets: { template: 'submit-snippets.yml', fileName: 'snippets.wmsnippets.json', title: '[Snippets]', noun: 'snippet pack', binary: false },
};

/** The file's bytes: the JSON as UTF-8, or a pack as it is. */
function bytesOf(file: string | Uint8Array): Uint8Array {
	return typeof file === 'string' ? strToU8(file) : file;
}

export interface Fields {
	name: string;
	description?: string;
	tags?: string[];
	license?: string;
	/** Id of an addon this user published before. */
	updates?: string;
	/** `"<repoId>/<addonId>"` of the addon this one remixes. */
	basedOn?: string;
}

export interface Plan {
	tier: Tier;
	/** Carries the payload only on 'link'. */
	url: string;
	encoded: string;
}

function toBase64Url(bytes: Uint8Array): string {
	let s = '';
	for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
	return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function fromBase64Url(text: string): Uint8Array | null {
	const clean = text.replace(/\s+/g, '').replace(/=+$/, '').replace(/-/g, '+').replace(/_/g, '/');
	if (!/^[A-Za-z0-9+/]*$/.test(clean) || clean.length % 4 === 1) return null;
	try {
		const s = atob(clean + '='.repeat((4 - (clean.length % 4)) % 4));
		const out = new Uint8Array(s.length);
		for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
		return out;
	} catch {
		return null;
	}
}

export function encodePayload(file: string | Uint8Array): string {
	return PREFIX + toBase64Url(zlibSync(bytesOf(file), { level: 9 }));
}

/** The file in `text`, or null when it is not an encoded payload. */
export function decodePayloadBytes(text: string): Uint8Array | null {
	const t = text.trim();
	if (!t.startsWith(PREFIX)) return null;
	const bytes = fromBase64Url(t.slice(PREFIX.length));
	if (!bytes) return null;
	try {
		return unzlibSync(bytes);
	} catch {
		return null;
	}
}

/** The JSON in `text`, or null when it is not an encoded payload. */
export function decodePayload(text: string): string | null {
	const t = text.trim();
	if (!t.startsWith(PREFIX)) return null;
	const bytes = fromBase64Url(t.slice(PREFIX.length));
	if (!bytes) return null;
	try {
		return strFromU8(unzlibSync(bytes));
	} catch {
		return null;
	}
}

/** The link that opens `kind`'s issue form filled in; `%20` for spaces, which GitHub's prefill needs. */
export function issueUrl(kind: Kind, f: Fields, payload: string | null): string {
	const params: [string, string][] = [
		['template', KINDS[kind].template],
		['title', `${KINDS[kind].title} ${f.name.trim()}`],
		['name', f.name.trim()],
	];
	if (f.description?.trim()) params.push(['description', f.description.trim()]);
	const tags = [...new Set((f.tags ?? []).map((t) => t.trim()).filter(Boolean))];
	if (tags.length) params.push(['tags', tags.join(', ')]);
	params.push(['license', f.license ?? LICENSES[0]]);
	if (f.updates?.trim()) params.push(['updates', f.updates.trim()]);
	if (f.basedOn?.trim()) params.push(['based_on', f.basedOn.trim()]);
	if (payload != null) params.push(['payload', payload]);
	return `${COMMUNITY_URL}/issues/new?` + params.map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');
}

export function plan(kind: Kind, f: Fields, file: string | Uint8Array): Plan {
	const encoded = encodePayload(file);
	const withPayload = issueUrl(kind, f, encoded);
	if (withPayload.length <= LINK_LIMIT) return { tier: 'link', url: withPayload, encoded };
	return { tier: encoded.length <= PASTE_LIMIT ? 'paste' : 'attach', url: issueUrl(kind, f, null), encoded };
}

/**
 * The zip an 'attach' submission saves: GitHub takes a .zip in an issue, not a
 * .json or a .wmicons. A pack is a zip already and goes as it is; the bot
 * reads it by the pack.json at its top.
 */
export function attachment(kind: Kind, file: string | Uint8Array): Uint8Array {
	if (KINDS[kind].binary && typeof file !== 'string') return file;
	return zipSync({ [KINDS[kind].fileName]: bytesOf(file) });
}

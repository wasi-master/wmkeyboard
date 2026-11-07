/**
 * Repository health check: everything the app's decoder, the JSON schema and
 * the sample repository's validate.py would object to, plus the things only
 * the network can tell (reachability, sizes, checksums, whether a payload
 * actually parses as its type).
 */
import { decodeManifest, ManifestError } from './decode';
import { isKnownLanguage } from './languages';
import { fetchBytes, fetchText, MAX_MANIFEST_BYTES, NetError, probe, PREVIEW_MAX_BYTES } from './net';
import { resolveAsset } from './resolve';
import { isStrictSemver } from './semver';
import { LANG_REQUIRED, MANIFEST_NAME, typeInfo, type AddonEntry, type Manifest } from './types';
import { payloadFileName, sha256Hex } from './util';
import { ICON_SLOT_IDS } from './icon-slots';
import {
	readDictionary,
	readEmojiKeywords,
	readFont,
	readIconPack,
	readLayout,
	readPlugin,
	readSnippets,
	readSound,
	readSoundPack,
	readStickerPack,
	readTheme,
	readVocabulary,
} from './payloads';

export type Severity = 'error' | 'warn' | 'info';

export interface Finding {
	severity: Severity;
	/** Where it applies: 'repo', 'manifest', or an addon id. */
	where: string;
	message: string;
	/** JSON path or field, when useful. */
	field?: string;
}

export interface CheckReport {
	url: string;
	manifest: Manifest | null;
	findings: Finding[];
	/** Reachability/size probe per addon id (payload only). */
	probes: Record<string, { ok: boolean; status: number; size: number | null; error?: string }>;
	verified: Record<string, 'ok' | 'mismatch' | 'skipped' | 'error'>;
	startedAt: number;
	finishedAt: number | null;
}

export interface CheckOptions {
	/** Download every payload (under the preview cap) and compare its sha256, and parse it as its type. */
	deep: boolean;
	signal?: AbortSignal;
	onProgress?: (done: number, total: number, label: string) => void;
	onFinding?: (f: Finding) => void;
}

const ID_PATTERN = /^[A-Za-z0-9._-]+$/;
const SHA_PATTERN = /^[a-f0-9]{64}$/;
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}/;

function staticFindings(url: string, text: string, manifest: Manifest, rawDropped: { index: number; reason: string }[]): Finding[] {
	const f: Finding[] = [];
	let raw: Record<string, unknown> = {};
	try {
		raw = JSON.parse(text);
	} catch {
		/* decode already threw */
	}
	const push = (severity: Severity, where: string, message: string, field?: string) => f.push({ severity, where, message, field });

	if (text.length > MAX_MANIFEST_BYTES) push('error', 'manifest', `The manifest is ${Math.round(text.length / 1024)} KB; the app reads at most 1 MB.`);
	if (!url.endsWith('/' + MANIFEST_NAME)) push('info', 'manifest', `The file is not named ${MANIFEST_NAME}; a repository page URL only resolves to that name, so people must paste this exact file address.`);
	if (typeof raw.version !== 'number' || !Number.isInteger(raw.version) || raw.version < 1) push('error', 'manifest', '"version" must be an integer ≥ 1 (currently 1).', 'version');
	else if (raw.version > 1) push('warn', 'manifest', `"version": ${raw.version} is newer than this client understands (1).`, 'version');
	if (!raw.$schema) push('info', 'manifest', 'No "$schema"; adding the published schema URL gives editors validation and completion.', '$schema');

	const r = manifest.repo;
	if (!ID_PATTERN.test(r.id)) push('error', 'repo', `"repo.id" ${JSON.stringify(r.id)} must match ${ID_PATTERN}.`, 'repo.id');
	if (!r.id.includes('.')) push('info', 'repo', 'A reverse-DNS "repo.id" (com.example.pack) avoids collisions; the install key is "<repo.id>/<addon.id>", so never change a published id.', 'repo.id');
	if (!r.name?.trim()) push('warn', 'repo', '"repo.name" is missing; the app falls back to the id.', 'repo.name');
	if (!r.description) push('info', 'repo', 'No "repo.description".', 'repo.description');
	if (r.homepage && !/^https:\/\//i.test(r.homepage)) push('warn', 'repo', 'The app only offers "Open homepage" for https:// URLs.', 'repo.homepage');
	if (r.icon && !resolveAsset(url, r.icon)) push('error', 'repo', `"repo.icon" ${JSON.stringify(r.icon)} is not a path the app accepts (https, or relative and inside the repository).`, 'repo.icon');
	if (!r.icon) push('info', 'repo', 'No "repo.icon"; the app shows initials instead.', 'repo.icon');
	if (r.updatedAt && !DATE_PATTERN.test(r.updatedAt)) push('warn', 'repo', `"repo.updatedAt" should be an ISO date (YYYY-MM-DD), got ${JSON.stringify(r.updatedAt)}.`, 'repo.updatedAt');

	for (const d of rawDropped) push('error', `addons[${d.index}]`, `Dropped by the app: ${d.reason}.`);
	if (!manifest.addons.length) push('warn', 'manifest', 'No addons.');

	const ids = new Map<string, number>();
	for (const a of manifest.addons) ids.set(a.id, (ids.get(a.id) ?? 0) + 1);
	const known = new Set(manifest.addons.map((a) => a.id));

	for (const a of manifest.addons) {
		const w = a.id;
		const info = typeInfo(a.type);
		if ((ids.get(a.id) ?? 0) > 1) push('error', w, 'Duplicate addon id; the install key would collide.', 'id');
		if (!ID_PATTERN.test(a.id)) push('error', w, `Id must match ${ID_PATTERN}.`, 'id');
		if (!isStrictSemver(a.version)) push('error', w, `"version" ${JSON.stringify(a.version)} is not semver (X.Y.Z[-pre][+build]); update detection compares versions and validate.py rejects this.`, 'version');
		if (!a.name.trim()) push('warn', w, 'No name.', 'name');
		if (!a.description) push('info', w, 'No description.', 'description');
		if (!a.author) push('info', w, 'No author.', 'author');
		if (!resolveAsset(url, a.path)) push('error', w, `"path" ${JSON.stringify(a.path)} is refused: must be https://, or relative without "/", "//" or "../" escaping the manifest directory.`, 'path');
		if (a.sha256 && !SHA_PATTERN.test(a.sha256)) push('error', w, '"sha256" must be 64 lowercase hex characters.', 'sha256');
		if (!a.sha256) push(a.type === 'plugin' ? 'error' : 'warn', w, a.type === 'plugin' ? 'A plugin without "sha256" is refused by the app before any download.' : 'No "sha256": the app installs it unverified. tools/build_index.py fills it in.', 'sha256');
		if (a.sizeBytes == null) push('info', w, 'No "sizeBytes": the app cannot show a size or guard before downloading.', 'sizeBytes');
		else if (!Number.isInteger(a.sizeBytes) || a.sizeBytes < 0) push('error', w, '"sizeBytes" must be a non-negative integer.', 'sizeBytes');
		else if (info.maxBytes && a.sizeBytes > info.maxBytes) push('error', w, `"sizeBytes" ${a.sizeBytes} is over the ${Math.round(info.maxBytes / 1048576)} MB cap for ${info.plural.toLowerCase()}; the app aborts the download.`, 'sizeBytes');
		if (!a.previews.length && !['sound', 'font', 'emoji_font', 'dictionary'].includes(a.type)) push('info', w, 'No previews; the card shows a type glyph.', 'previews');
		a.previews.forEach((p, i) => {
			if (!resolveAsset(url, p)) push('error', w, `previews[${i}] ${JSON.stringify(p)} is a path the app refuses.`, `previews[${i}]`);
		});
		if (a.minAppVersion != null && (!Number.isInteger(a.minAppVersion) || a.minAppVersion < 0)) push('error', w, '"minAppVersion" must be a non-negative integer versionCode.', 'minAppVersion');
		if (LANG_REQUIRED.has(a.type) && !a.langId) push('error', w, `"langId" is required for type "${a.type}".`, 'langId');
		if (a.langId && !isKnownLanguage(a.langId)) push('error', w, `"langId" ${JSON.stringify(a.langId)} is not a language id the app registers.`, 'langId');
		a.langIds.forEach((l, i) => {
			if (!isKnownLanguage(l)) push('warn', w, `langIds[${i}] ${JSON.stringify(l)} is not a language id the app registers.`, `langIds[${i}]`);
		});
		if (!a.license && !a.licenseText && !a.licenseFile) push('warn', w, 'No licence stated.', 'license');
		if (a.licenseFile && !resolveAsset(url, a.licenseFile)) push('error', w, `"licenseFile" ${JSON.stringify(a.licenseFile)} is a path the app refuses.`, 'licenseFile');
		if (a.licenseText && a.licenseText.length > 256 * 1024) push('warn', w, '"licenseText" is over 256 KB; prefer "licenseFile".', 'licenseText');
		a.requires.forEach((id, i) => {
			if (!known.has(id)) push('error', w, `requires[${i}] "${id}" is not an addon in this repository.`, `requires[${i}]`);
			if (id === a.id) push('error', w, 'An addon cannot require itself.', `requires[${i}]`);
		});
		const file = payloadFileName(a).toLowerCase();
		const expect: Record<string, RegExp> = {
			theme: /\.wmtheme\.json$/,
			layout: /\.wmlayout\.json$/,
			snippets: /\.wmsnippets\.json$/,
			stickers: /\.wmstickers$/,
			icon_pack: /\.wmicons$/,
			sound_pack: /\.wmsoundpack$/,
			plugin: /\.wmplugin$/,
			vocabulary: /\.wmvocab\.json(\.gz)?$/,
			font: /\.(ttf|otf)$/,
			emoji_font: /\.(ttf|otf)$/,
			sound: /\.(mp3|ogg|wav)$/,
			espanso: /\.(ya?ml|zip)$/,
		};
		if (expect[a.type] && !expect[a.type]!.test(file)) push('info', w, `The file name "${file}" is unusual for a ${info.singular.toLowerCase()} (${info.payload}); the app sniffs content, so this is cosmetic.`, 'path');
	}
	return f;
}

async function parsePayload(a: AddonEntry, bytes: Uint8Array): Promise<string[]> {
	const name = payloadFileName(a);
	const text = () => new TextDecoder('utf-8').decode(bytes);
	switch (a.type) {
		case 'theme': return readTheme(text()).problems;
		case 'layout': return readLayout(text()).problems;
		case 'snippets': return readSnippets(text()).problems;
		case 'stickers': return readStickerPack(bytes).problems;
		case 'sound_pack': return readSoundPack(bytes).problems;
		case 'icon_pack': return readIconPack(bytes, ICON_SLOT_IDS).problems;
		case 'plugin': return readPlugin(bytes).problems;
		case 'vocabulary': return (await readVocabulary(bytes, name)).problems;
		case 'font': case 'emoji_font': readFont(bytes); return [];
		case 'sound': readSound(bytes, name); return [];
		case 'dictionary': { const d = await readDictionary(bytes, name, 1); return d.total ? [] : ['No words found.']; }
		case 'emoji_keywords': { const d = await readEmojiKeywords(bytes, name, 1); return d.total ? [] : ['No rows found.']; }
		default: return [];
	}
}

/** The static half of the check, for the repository builder: no network. */
export function validateManifestText(url: string, text: string): { findings: Finding[]; manifest: Manifest | null } {
	try {
		const { manifest, dropped } = decodeManifest(text);
		return { findings: staticFindings(url, text, manifest, dropped), manifest };
	} catch (e) {
		return { findings: [{ severity: 'error', where: 'manifest', message: e instanceof ManifestError ? e.message : String(e) }], manifest: null };
	}
}

export async function runCheck(url: string, opts: CheckOptions): Promise<CheckReport> {
	const report: CheckReport = { url, manifest: null, findings: [], probes: {}, verified: {}, startedAt: Date.now(), finishedAt: null };
	const add = (f: Finding) => {
		report.findings.push(f);
		opts.onFinding?.(f);
	};
	let text: string;
	try {
		text = await fetchText(url, { maxBytes: MAX_MANIFEST_BYTES, signal: opts.signal, accept: 'application/json' });
	} catch (e) {
		add({ severity: 'error', where: 'manifest', message: e instanceof NetError ? `${e.kind === 'cors' ? 'Not readable from a browser: ' : ''}${e.message}` : String(e) });
		report.finishedAt = Date.now();
		return report;
	}
	let manifest: Manifest;
	let dropped: { index: number; reason: string }[];
	try {
		({ manifest, dropped } = decodeManifest(text));
	} catch (e) {
		add({ severity: 'error', where: 'manifest', message: e instanceof ManifestError ? e.message : String(e) });
		report.finishedAt = Date.now();
		return report;
	}
	report.manifest = manifest;
	for (const f of staticFindings(url, text, manifest, dropped)) add(f);

	// Network: probe every referenced file.
	const jobs: { label: string; run: () => Promise<void> }[] = [];
	const iconUrl = manifest.repo.icon ? resolveAsset(url, manifest.repo.icon) : null;
	if (iconUrl) jobs.push({ label: 'repo icon', run: async () => { const p = await probe(iconUrl, opts.signal); if (!p.ok) add({ severity: 'warn', where: 'repo', message: `Icon not reachable (${p.error ?? `HTTP ${p.status}`}).`, field: 'repo.icon' }); } });
	for (const a of manifest.addons) {
		const pu = resolveAsset(url, a.path);
		if (pu) {
			jobs.push({
				label: a.id,
				run: async () => {
					const p = await probe(pu, opts.signal);
					report.probes[a.id] = p;
					if (!p.ok) add({ severity: 'error', where: a.id, message: `Payload not reachable: ${p.error ?? `HTTP ${p.status}`}.`, field: 'path' });
					else if (p.size != null && a.sizeBytes != null && p.size !== a.sizeBytes && !p.compressed) {
						const textLike = /\.(json|txt|tsv|ya?ml|svg|md|py|csv)$/i.test(payloadFileName(a));
						if (p.size < a.sizeBytes && textLike) {
							if (!opts.deep) add({ severity: 'info', where: a.id, message: `The server sent ${p.size} bytes for a ${a.sizeBytes}-byte file: a compressed transfer, most likely. A deep check compares the real size.`, field: 'sizeBytes' });
						} else add({ severity: 'error', where: a.id, message: `"sizeBytes" says ${a.sizeBytes} but the server serves ${p.size} bytes; the manifest is stale (re-run build_index.py).`, field: 'sizeBytes' });
					} else if (p.size != null && typeInfo(a.type).maxBytes && p.size > typeInfo(a.type).maxBytes) add({ severity: 'error', where: a.id, message: `Served size ${p.size} is over the type cap.`, field: 'path' });
				},
			});
		}
		a.previews.forEach((pv, i) => {
			const u = resolveAsset(url, pv);
			if (u) jobs.push({ label: `${a.id} preview ${i + 1}`, run: async () => { const p = await probe(u, opts.signal); if (!p.ok) add({ severity: 'warn', where: a.id, message: `previews[${i}] not reachable (${p.error ?? `HTTP ${p.status}`}).`, field: `previews[${i}]` }); } });
		});
		if (a.licenseFile) {
			const u = resolveAsset(url, a.licenseFile);
			if (u) jobs.push({ label: `${a.id} licence`, run: async () => { const p = await probe(u, opts.signal); if (!p.ok) add({ severity: 'warn', where: a.id, message: `"licenseFile" not reachable (${p.error ?? `HTTP ${p.status}`}).`, field: 'licenseFile' }); } });
		}
	}
	let done = 0;
	const total = jobs.length + (opts.deep ? manifest.addons.length : 0);
	const pool = 6;
	let next = 0;
	const worker = async () => {
		while (next < jobs.length) {
			const j = jobs[next++]!;
			if (opts.signal?.aborted) return;
			opts.onProgress?.(done, total, j.label);
			await j.run();
			done++;
		}
	};
	await Promise.all(Array.from({ length: pool }, worker));

	if (opts.deep) {
		for (const a of manifest.addons) {
			if (opts.signal?.aborted) break;
			opts.onProgress?.(done, total, `verifying ${a.id}`);
			const pu = resolveAsset(url, a.path);
			const cap = Math.min(typeInfo(a.type).maxBytes || PREVIEW_MAX_BYTES, PREVIEW_MAX_BYTES);
			if (!pu || (a.sizeBytes != null && a.sizeBytes > cap)) {
				report.verified[a.id] = 'skipped';
				if (pu) add({ severity: 'info', where: a.id, message: `Skipped checksum verification: over the ${Math.round(cap / 1048576)} MB in-browser cap.` });
				done++;
				continue;
			}
			try {
				const bytes = await fetchBytes(pu, { maxBytes: cap, signal: opts.signal });
				if (a.sizeBytes != null && bytes.byteLength !== a.sizeBytes) add({ severity: 'error', where: a.id, message: `"sizeBytes" says ${a.sizeBytes} but the file is ${bytes.byteLength} bytes; the manifest is stale (re-run build_index.py).`, field: 'sizeBytes' });
				if (a.sha256) {
					const sha = await sha256Hex(bytes);
					if (sha === a.sha256) report.verified[a.id] = 'ok';
					else {
						report.verified[a.id] = 'mismatch';
						add({ severity: 'error', where: a.id, message: `sha256 mismatch: manifest ${a.sha256.slice(0, 12)}…, file ${sha.slice(0, 12)}…. The app refuses to install this.`, field: 'sha256' });
					}
				} else report.verified[a.id] = 'skipped';
				const problems = await parsePayload(a, bytes);
				for (const p of problems) add({ severity: 'warn', where: a.id, message: `Payload: ${p}` });
			} catch (e) {
				report.verified[a.id] = 'error';
				add({ severity: 'error', where: a.id, message: `Could not verify: ${e instanceof NetError ? e.message : (e as Error).message}` });
			}
			done++;
		}
	}
	report.finishedAt = Date.now();
	return report;
}

export function reportToMarkdown(r: CheckReport): string {
	const lines: string[] = [];
	lines.push(`## Repository check: ${r.manifest?.repo.name ?? r.url}`);
	lines.push('');
	lines.push(`Manifest: ${r.url}`);
	lines.push(`Checked: ${new Date(r.startedAt).toISOString()} via wmkeyboard.pages.dev/addons/check`);
	lines.push('');
	const by = (s: Severity) => r.findings.filter((f) => f.severity === s);
	lines.push(`**${by('error').length} errors · ${by('warn').length} warnings · ${by('info').length} notes**`);
	for (const s of ['error', 'warn', 'info'] as Severity[]) {
		const list = by(s);
		if (!list.length) continue;
		lines.push('');
		lines.push(`### ${s === 'error' ? 'Errors' : s === 'warn' ? 'Warnings' : 'Notes'}`);
		for (const f of list) lines.push(`- \`${f.where}\`${f.field ? ` (${f.field})` : ''}: ${f.message}`);
	}
	return lines.join('\n');
}

/**
 * Repository builder: repo info, one entry per addon, payload files dropped
 * in (type sniffed, size + sha256 computed here), validation with the same
 * rules as the health check, and a wmkeyboard-repo.json or a ready-to-host
 * folder zip out.
 */
import { useMemo, useState } from 'preact/hooks';
import { validateManifestText, type Finding } from '../../lib/check';
import { decodeManifest } from '../../lib/decode';
import { LANGUAGES } from '../../lib/languages';
import { fetchText, fmtBytes, MAX_MANIFEST_BYTES } from '../../lib/net';
import { readDynStickerPack, readIconPack, readLayout, readPlugin, readSnippets, readSoundPack, readStickerPack, readTheme } from '../../lib/payloads';
import { ICON_SLOT_IDS } from '../../lib/icon-slots';
import { resolveManifestUrl } from '../../lib/resolve';
import { ADDON_TYPES, REPO_FORMAT, typeInfo, type AddonType } from '../../lib/types';
import { sha256Hex, slugify, uid } from '../../lib/util';
import { writeZip } from '../../lib/zip';
import { CopyButton, Notice } from '../common';
import { IconCheck, IconDownload, IconPlus, IconTrash, IconWarn, TypeIcon } from '../icons';
import { Area, DropZone, ExportPanel, idError, Num, saveBytes, saveJson, Section, Select, Tags, Text, useDraft, type PickedFile } from './shared';

interface Entry {
	key: string;
	id: string;
	type: AddonType;
	name: string;
	version: string;
	author: string;
	description: string;
	tags: string[];
	path: string;
	sha256: string;
	sizeBytes: number | null;
	previews: string[];
	minAppVersion: number | null;
	langId: string;
	langIds: string[];
	license: string;
	licenseFile: string;
	licenseText: string;
	requires: string[];
	/** Dropped file kept in memory for the folder zip (not persisted in the draft). */
	fileName?: string;
}

interface Draft {
	repo: { id: string; name: string; description: string; author: string; homepage: string; icon: string; updatedAt: string };
	entries: Entry[];
}

const DIRS: Record<AddonType, string> = {
	theme: 'themes', layout: 'layouts', dictionary: 'dictionaries', emoji_keywords: 'emoji', snippets: 'snippets', espanso: 'snippets',
	stickers: 'stickers', dynamic_stickers: 'dynstickers', icon_pack: 'icons', font: 'fonts', emoji_font: 'fonts', sound: 'sounds', sound_pack: 'packs', plugin: 'plugins', vocabulary: 'vocab',
};

const SCHEMA_URL = 'https://wmkeyboard.pages.dev/schemas/wmkeyboard-repo.schema.json';

function blank(): Draft {
	return { repo: { id: '', name: '', description: '', author: '', homepage: '', icon: '', updatedAt: new Date().toISOString().slice(0, 10) }, entries: [] };
}

function newEntry(partial: Partial<Entry> = {}): Entry {
	return { key: uid(), id: '', type: 'theme', name: '', version: '1.0.0', author: '', description: '', tags: [], path: '', sha256: '', sizeBytes: null, previews: [], minAppVersion: null, langId: '', langIds: [], license: '', licenseFile: '', licenseText: '', requires: [], ...partial };
}

/** Guess a type and pull a name/id/description out of a dropped payload. */
async function sniff(file: PickedFile): Promise<Partial<Entry>> {
	const n = file.name.toLowerCase();
	const text = () => new TextDecoder('utf-8').decode(file.bytes);
	const base = file.name.replace(/\.(wmtheme\.json|wmlayout\.json|wmsnippets\.json|wmvocab\.json(\.gz)?|wmstickers|wmdynstickers|wmicons|wmsoundpack|wmplugin|ttf|otf|mp3|ogg|wav|txt|txt\.gz|tsv|tsv\.gz|ya?ml|json)$/i, '');
	const out: Partial<Entry> = { id: slugify(base), name: base, sha256: await sha256Hex(file.bytes), sizeBytes: file.bytes.byteLength, fileName: file.name };
	try {
		if (n.endsWith('.wmtheme.json')) { const t = readTheme(text()); return { ...out, type: 'theme', id: slugify(t.spec.id || base), name: t.spec.name || base }; }
		if (n.endsWith('.wmlayout.json')) { const l = readLayout(text()); return { ...out, type: 'layout', id: slugify(l.layout.id || base), name: l.layout.name || base, langId: l.layout.langId ?? '' }; }
		if (n.endsWith('.wmsnippets.json')) { readSnippets(text()); return { ...out, type: 'snippets' }; }
		if (n.endsWith('.wmvocab.json') || n.endsWith('.wmvocab.json.gz')) return { ...out, type: 'vocabulary' };
		if (n.endsWith('.wmstickers')) { const p = readStickerPack(file.bytes); return { ...out, type: 'stickers', id: slugify(p.id || base), name: p.name || base, author: p.author, description: p.description }; }
		if (n.endsWith('.wmdynstickers')) { const p = readDynStickerPack(file.bytes); return { ...out, type: 'dynamic_stickers', id: slugify(p.id || base), name: p.name || base, author: p.author, description: p.description, version: /^\d+\.\d+\.\d+/.test(p.version) ? p.version : '1.0.0', license: p.license }; }
		if (n.endsWith('.wmicons')) { const p = readIconPack(file.bytes, ICON_SLOT_IDS); return { ...out, type: 'icon_pack', id: slugify(p.id || base), name: p.name || base, author: p.author, description: p.description, version: /^\d+\.\d+\.\d+/.test(p.version) ? p.version : '1.0.0' }; }
		if (n.endsWith('.wmsoundpack')) { const p = readSoundPack(file.bytes); return { ...out, type: 'sound_pack', id: slugify(p.id || base), name: p.name || base, author: p.author, description: p.description, version: p.packVersion }; }
		if (n.endsWith('.wmplugin')) { const p = readPlugin(file.bytes); return { ...out, type: 'plugin', id: slugify(p.manifest.id || base), name: p.manifest.name || base, author: p.manifest.author, description: p.manifest.description, version: /^\d+\.\d+\.\d+/.test(p.manifest.pluginVersion) ? p.manifest.pluginVersion : '1.0.0' }; }
		if (/\.(ttf|otf)$/.test(n)) return { ...out, type: 'font' };
		if (/\.(mp3|ogg|wav)$/.test(n)) return { ...out, type: 'sound' };
		if (/\.tsv(\.gz)?$/.test(n)) return { ...out, type: 'emoji_keywords' };
		if (/\.ya?ml$/.test(n)) return { ...out, type: 'espanso' };
		if (/\.txt(\.gz)?$/.test(n)) return { ...out, type: 'dictionary' };
	} catch {
		/* unreadable: leave the guess to the user */
	}
	return out;
}

function toManifest(d: Draft) {
	const clean = <T,>(v: T): T | undefined => (Array.isArray(v) ? (v.length ? v : undefined) : v === '' || v === null ? undefined : v);
	return {
		$schema: SCHEMA_URL,
		format: REPO_FORMAT,
		version: 1,
		repo: { id: d.repo.id, name: d.repo.name, description: clean(d.repo.description), author: clean(d.repo.author), homepage: clean(d.repo.homepage), icon: clean(d.repo.icon), updatedAt: clean(d.repo.updatedAt) },
		addons: d.entries.map((e) => ({
			id: e.id, type: e.type, name: e.name, version: e.version, author: clean(e.author), description: clean(e.description), tags: clean(e.tags),
			path: e.path, sha256: clean(e.sha256), sizeBytes: e.sizeBytes ?? undefined, previews: clean(e.previews), minAppVersion: e.minAppVersion ?? undefined,
			langId: clean(e.langId), langIds: clean(e.langIds), license: clean(e.license), licenseText: clean(e.licenseText), licenseFile: clean(e.licenseFile), requires: clean(e.requires),
		})),
	};
}

function fromManifest(text: string): Draft {
	const { manifest } = decodeManifest(text);
	const r = manifest.repo;
	return {
		repo: { id: r.id, name: r.name, description: r.description ?? '', author: r.author ?? '', homepage: r.homepage ?? '', icon: r.icon ?? '', updatedAt: r.updatedAt ?? '' },
		entries: manifest.addons.map((a) => newEntry({ id: a.id, type: a.type as AddonType, name: a.name, version: a.version, author: a.author ?? '', description: a.description ?? '', tags: a.tags, path: a.path, sha256: a.sha256 ?? '', sizeBytes: a.sizeBytes ?? null, previews: a.previews, minAppVersion: a.minAppVersion ?? null, langId: a.langId ?? '', langIds: a.langIds, license: a.license ?? '', licenseFile: a.licenseFile ?? '', licenseText: a.licenseText ?? '', requires: a.requires })),
	};
}

export function RepoBuilder() {
	const [d, setD, reset] = useDraft<Draft>('repo', blank);
	const [sel, setSel] = useState<string | null>(null);
	const [files] = useState(() => new Map<string, PickedFile>());
	const [importUrl, setImportUrl] = useState('');
	const [importing, setImporting] = useState<string | null>(null);
	const [busy, setBusy] = useState(false);
	const patchRepo = (p: Partial<Draft['repo']>) => setD({ ...d, repo: { ...d.repo, ...p } });
	const patchEntry = (key: string, p: Partial<Entry>) => setD({ ...d, entries: d.entries.map((e) => (e.key === key ? { ...e, ...p } : e)) });

	const manifest = useMemo(() => toManifest(d), [d]);
	const json = useMemo(() => JSON.stringify(manifest, null, 2), [manifest]);
	const findings = useMemo(() => validateManifestText('https://example.invalid/repo/wmkeyboard-repo.json', json).findings, [json]);
	const errors = findings.filter((f) => f.severity === 'error');
	const current = d.entries.find((e) => e.key === sel) ?? null;

	const addFiles = async (picked: PickedFile[]) => {
		setBusy(true);
		const entries: Entry[] = [];
		for (const f of picked) {
			const s = await sniff(f);
			const type = (s.type ?? 'theme') as AddonType;
			const e = newEntry({ ...s, type, path: `${DIRS[type]}/${f.name}` });
			files.set(e.key, f);
			entries.push(e);
		}
		setD({ ...d, entries: [...d.entries, ...entries] });
		setSel(entries[0]?.key ?? sel);
		setBusy(false);
	};

	const doImport = async () => {
		const url = resolveManifestUrl(importUrl);
		if (!url) return setImporting('Not an address the app can resolve.');
		setImporting('Fetching…');
		try {
			const text = await fetchText(url, { maxBytes: MAX_MANIFEST_BYTES, accept: 'application/json' });
			setD(fromManifest(text));
			setSel(null);
			setImporting(null);
		} catch (e) {
			setImporting((e as Error).message);
		}
	};

	const exportZip = () => {
		const entries: { name: string; data: Uint8Array | string }[] = [{ name: 'wmkeyboard-repo.json', data: json + '\n' }];
		for (const e of d.entries) {
			const f = files.get(e.key);
			if (f && !/^https?:\/\//.test(e.path)) entries.push({ name: e.path, data: f.bytes });
		}
		entries.push({ name: 'README.md', data: `# ${d.repo.name || d.repo.id}\n\n${d.repo.description}\n\nAdd this repository in WM Keyboard: Settings › Addons › Add repository, and paste the address of the folder that holds \`wmkeyboard-repo.json\`.\n` });
		saveBytes(`${slugify(d.repo.id || 'repository')}.zip`, writeZip(entries));
	};

	return (
		<div class="st-creator-layout">
			<div style="display:flex;flex-direction:column;gap:0.8rem">
				<div>
					<span class="st-kicker">Repository builder</span>
					<h1 style="font-size:1.5rem;font-weight:800">Index your addons</h1>
					<p class="st-muted st-small" style="max-width:62ch;margin-top:0.3rem">
						A repository is a <code>wmkeyboard-repo.json</code> next to the files it lists. Drop payloads here to get their type, size and sha256 filled in, describe each one, then download the manifest alone or the whole folder as a zip. Host it anywhere that serves plain files over https; GitHub works with zero setup.
					</p>
				</div>

				<Section title="Start from an existing repository" open={false}>
					<div class="st-row" style="flex-wrap:nowrap">
						<input class="st-input" placeholder="github.com/user/repo or a manifest URL" value={importUrl} onInput={(e) => setImportUrl((e.target as HTMLInputElement).value)} />
						<button class="st-btn" onClick={doImport}>Import</button>
					</div>
					{importing && <span class="st-small st-muted">{importing}</span>}
					<DropZone accept=".json" multiple={false} onFiles={async ([f]) => { if (!f) return; try { setD(fromManifest(new TextDecoder().decode(f.bytes))); setSel(null); } catch (e) { setImporting((e as Error).message); } }}>
						Or drop a wmkeyboard-repo.json
					</DropZone>
				</Section>

				<Section title="Repository">
					<div class="st-grid2">
						<Text label="Id" required mono value={d.repo.id} onInput={(v) => patchRepo({ id: v })} placeholder="com.example.mypack" error={idError(d.repo.id)} help="Reverse-DNS, stable forever: installs are keyed by it." />
						<Text label="Name" required value={d.repo.name} onInput={(v) => patchRepo({ name: v })} placeholder="My addon pack" />
					</div>
					<Area label="Description" value={d.repo.description} onInput={(v) => patchRepo({ description: v })} rows={2} />
					<div class="st-grid2">
						<Text label="Author" value={d.repo.author} onInput={(v) => patchRepo({ author: v })} />
						<Text label="Homepage" type="url" value={d.repo.homepage} onInput={(v) => patchRepo({ homepage: v })} placeholder="https://…" />
						<Text label="Icon" mono value={d.repo.icon} onInput={(v) => patchRepo({ icon: v })} placeholder="icon.png" help="Relative to the manifest, or an https URL." />
						<Text label="Updated" type="date" value={d.repo.updatedAt} onInput={(v) => patchRepo({ updatedAt: v })} />
					</div>
				</Section>

				<Section title={`Addons (${d.entries.length})`}>
					<DropZone onFiles={addFiles}>Drop addon files: .wmtheme.json, .wmlayout.json, .wmsnippets.json, .wmstickers, .wmdynstickers, .wmicons, .wmsoundpack, .wmplugin, fonts, sounds, word lists…</DropZone>
					{busy && <span class="st-small st-muted"><span class="st-spinner" /> Hashing…</span>}
					<div class="st-entry-list">
						{d.entries.map((e) => (
							<div class="st-entry" key={e.key} aria-selected={sel === e.key} style={{ '--type-hue': `#${typeInfo(e.type).hue}` }}>
								<span class="st-tile-icon" style="display:inline-flex;width:1.8rem;height:1.8rem;align-items:center;justify-content:center;border-radius:30%;background:color-mix(in oklab, var(--type-hue) 18%, transparent);color:var(--type-hue)"><TypeIcon type={e.type} style="width:1rem;height:1rem" /></span>
								<button class="st-entry-text" style="background:none;border:0;padding:0;text-align:left;cursor:pointer" onClick={() => setSel(sel === e.key ? null : e.key)}>
									<span class="st-entry-name">{e.name || e.id || '(unnamed)'}</span>
									<span class="st-entry-sub">{typeInfo(e.type).singular} · v{e.version} · {e.path || 'no path'}{e.sizeBytes != null ? ` · ${fmtBytes(e.sizeBytes)}` : ''}{e.sha256 ? '' : ' · no checksum'}</span>
								</button>
								<span class="st-entry-actions">
									<button class="st-btn st-btn-ghost st-btn-icon st-btn-sm" aria-label="Remove" onClick={() => { files.delete(e.key); setD({ ...d, entries: d.entries.filter((x) => x.key !== e.key) }); if (sel === e.key) setSel(null); }}><IconTrash /></button>
								</span>
							</div>
						))}
					</div>
					<button class="st-btn st-btn-sm" onClick={() => { const e = newEntry(); setD({ ...d, entries: [...d.entries, e] }); setSel(e.key); }}><IconPlus /> Add an entry by hand (hosted elsewhere)</button>
				</Section>

				{current && (
					<Section title={`Entry: ${current.name || current.id || 'new'}`}>
						<div class="st-grid2">
							<Text label="Id" required mono value={current.id} onInput={(v) => patchEntry(current.key, { id: v })} error={idError(current.id) ?? (d.entries.some((x) => x.key !== current.key && x.id === current.id) ? 'Duplicate id.' : null)} help="Unique in this repository; never change once published." />
							<Select label="Type" value={current.type} onInput={(v) => patchEntry(current.key, { type: v, path: current.fileName ? `${DIRS[v]}/${current.fileName}` : current.path })} options={ADDON_TYPES.map((t) => [t, typeInfo(t).singular] as [AddonType, string])} />
							<Text label="Name" required value={current.name} onInput={(v) => patchEntry(current.key, { name: v })} />
							<Text label="Version" required mono value={current.version} onInput={(v) => patchEntry(current.key, { version: v })} help="Semver; bump it for the app to offer an update." error={/^\d+\.\d+\.\d+(?:[-+].+)?$/.test(current.version) ? null : 'Must be X.Y.Z'} />
							<Text label="Author" value={current.author} onInput={(v) => patchEntry(current.key, { author: v })} />
							<Text label="Licence" value={current.license} onInput={(v) => patchEntry(current.key, { license: v })} placeholder="MIT, OFL-1.1, CC0-1.0…" list="st-licences" />
							<datalist id="st-licences">{['MIT', 'Apache-2.0', 'OFL-1.1', 'CC0-1.0', 'CC-BY-4.0', 'CC-BY-SA-4.0', 'GPL-3.0-or-later', 'ISC', 'Public domain'].map((l) => <option value={l} key={l} />)}</datalist>
						</div>
						<Area label="Description" value={current.description} onInput={(v) => patchEntry(current.key, { description: v })} rows={3} />
						<Tags label="Tags" value={current.tags} onInput={(v) => patchEntry(current.key, { tags: v })} />
						<div class="st-grid2">
							<Text label="Path" required mono value={current.path} onInput={(v) => patchEntry(current.key, { path: v })} help={current.fileName ? `Dropped file: ${current.fileName}. Relative to the manifest, or an https URL.` : 'Relative to the manifest, or an https URL (a release asset, a CDN).'} />
							<Text label="sha256" mono value={current.sha256} onInput={(v) => patchEntry(current.key, { sha256: v.toLowerCase() })} help={current.fileName ? 'Computed from the dropped file.' : current.type === 'plugin' ? 'Required for plugins.' : 'Optional; the app verifies it when present.'} />
							<Num label="Size (bytes)" value={current.sizeBytes} onInput={(v) => patchEntry(current.key, { sizeBytes: v })} allowEmpty min={0} />
							<Num label="Min app versionCode" value={current.minAppVersion} onInput={(v) => patchEntry(current.key, { minAppVersion: v })} allowEmpty min={0} help="Leave empty unless the payload needs a newer app." />
						</div>
						<Tags label="Previews" value={current.previews} onInput={(v) => patchEntry(current.key, { previews: v })} placeholder="previews/shot1.jpg, previews/shot2.jpg" help="Screenshots, relative or https. The first is the card image." />
						<div class="st-grid2">
							<Text label={`Language id${['dictionary', 'emoji_keywords', 'vocabulary'].includes(current.type) ? ' (required)' : ''}`} mono value={current.langId} onInput={(v) => patchEntry(current.key, { langId: v })} list="st-langs" placeholder="en" />
							<Tags label="Language ids (fonts: scripts covered)" value={current.langIds} onInput={(v) => patchEntry(current.key, { langIds: v })} placeholder="en, bn" />
							<datalist id="st-langs">{LANGUAGES.map((l) => <option value={l.id} key={l.id}>{l.english}</option>)}</datalist>
							<Text label="Licence file" mono value={current.licenseFile} onInput={(v) => patchEntry(current.key, { licenseFile: v })} placeholder="LICENSE.txt" />
							<Tags label="Requires (addon ids in this repo)" value={current.requires} onInput={(v) => patchEntry(current.key, { requires: v })} help="The app offers to download these alongside." />
						</div>
						<Area label="Licence text (inline, short licences only)" value={current.licenseText} onInput={(v) => patchEntry(current.key, { licenseText: v })} rows={2} />
					</Section>
				)}
			</div>

			<aside class="st-creator-side">
				<ExportPanel title="Export" json={manifest}>
					<button class="st-btn st-btn-primary" onClick={() => saveJson('wmkeyboard-repo.json', manifest)} disabled={!d.repo.id}><IconDownload /> wmkeyboard-repo.json</button>
					<button class="st-btn" onClick={exportZip} disabled={!d.repo.id}><IconDownload /> Folder zip (manifest + dropped files)</button>
					<CopyButton text={json} label="Copy JSON" class="st-btn" />
					<button class="st-btn st-btn-ghost st-btn-sm" onClick={() => { if (confirm('Discard this draft?')) { reset(); setSel(null); files.clear(); } }}>Start over</button>
				</ExportPanel>
				<div class="st-panel">
					<h3>Validation</h3>
					{errors.length === 0 ? (
						<Notice kind="ok" icon={<IconCheck />}>No errors. {findings.length ? `${findings.length} note${findings.length === 1 ? '' : 's'} below.` : ''}</Notice>
					) : (
						<Notice kind="err" icon={<IconWarn />}>{errors.length} error{errors.length === 1 ? '' : 's'} the app would trip on.</Notice>
					)}
					<div style="max-height:22rem;overflow:auto;margin-top:0.6rem">
						{findings.map((f: Finding, i) => (
							<div class="st-finding" key={i} style="padding:0.4rem 0">
								<span class={`st-pill ${f.severity === 'error' ? 'st-pill-err' : f.severity === 'warn' ? 'st-pill-warn' : 'st-pill-muted'}`}>{f.severity}</span>
								<div class="st-small"><b>{f.where}</b>: {f.message}</div>
							</div>
						))}
					</div>
				</div>
				<div class="st-panel st-small st-muted">
					<h3>Then host it</h3>
					<ol style="padding-left:1.1rem;display:flex;flex-direction:column;gap:0.3rem">
						<li>Unzip into a git repository (or any static host).</li>
						<li>Push. On GitHub, the repository page URL is what people paste in the app.</li>
						<li>Check it: <a href="/addons/check/">/addons/check</a>.</li>
						<li>Share <code>wmkeyboard://repo?url=…</code> from your README.</li>
					</ol>
				</div>
			</aside>
		</div>
	);
}

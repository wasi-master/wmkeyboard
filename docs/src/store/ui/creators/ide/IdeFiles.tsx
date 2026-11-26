/**
 * A draft's files on the web (PluginIdeFiles.kt): the strip of files over the
 * code, the Files pane, the file name dialog and the plain text field for
 * files that are not Python; and opening a folder on the computer as a draft.
 */
import { useEffect, useRef, useState } from 'preact/hooks';
import { fmtBytes } from '../../../lib/net';
import { readPlugin } from '../../../lib/payloads';
import type { Lock } from '../../../lib/pypi';
import { readZip } from '../../../lib/zip';
import { BLANK_PLUGIN_SCRIPT, blankManifest, sanitise, sanitised, workspace, type PluginManifest } from './ide-store';
import { draftFiles, fileDataOf, fingerprint, folderLinks, groupOf, isPluginFile, isTestFile, isTextFile, manifestText, MANIFEST_FILE, ownerOf, sizeOf, type DraftFiles, type FileGroup } from './draft-files';
import { listFolder, MAX_FOLDER_FILES, readDiskEntry, sameFolder, skipped, writePath } from './folder';
import type { LinkState } from './folder-link';
import { Dialog, IconAddSvg, IconButton, IconDeleteSvg, IconFileSvg, IconFolderSvg, IconPackageSvg, IconRenameSvg, TextButton } from './parts';
import { S } from './strings';

/** What the editor calls the entry script in its own state, whatever plugin.json names it. */
export const ENTRY = ':entry';

// ---------------------------------------------------------------------------
// The strip over the code
// ---------------------------------------------------------------------------

/**
 * The entry first, then the plugin's own modules, assets and translations.
 * A package's files and the files beside the plugin live in the Files pane;
 * one of them shows here while it is open.
 */
export function FileStrip({ entry, files, lock, open, onOpen, onAdd }: { entry: string; files: DraftFiles; lock: Lock | null; open: string; onOpen: (path: string) => void; onAdd: () => void }) {
	const own = Object.keys(files).filter((p) => isPluginFile(p, entry) && !(lock && ownerOf(p, lock))).sort(byGroup(entry, lock));
	const shown = open !== ENTRY && !own.includes(open) ? [...own, open] : own;
	return (
		<div class="wm-filestrip" role="tablist" aria-label={S.filesLabel}>
			<button type="button" role="tab" class={`st-chip ${open === ENTRY ? 'st-chip-on' : ''}`} aria-selected={open === ENTRY} onClick={() => onOpen(ENTRY)}>{entry}</button>
			{shown.map((p) => (
				<button key={p} type="button" role="tab" class={`st-chip ${open === p ? 'st-chip-on' : ''}`} aria-selected={open === p} onClick={() => onOpen(p)} title={p}>
					{!isTextFile(p) && <IconFileSvg />}
					{p}
				</button>
			))}
			<button type="button" class="st-chip" onClick={onAdd}><IconAddSvg /> {S.filesNewAction}</button>
		</div>
	);
}

const GROUP_ORDER: Record<FileGroup, number> = { module: 0, asset: 1, locale: 2, package: 3, outside: 4 };
const byGroup = (entry: string, lock: Lock | null) => (a: string, b: string) => GROUP_ORDER[groupOf(a, entry, lock)] - GROUP_ORDER[groupOf(b, entry, lock)] || a.localeCompare(b);

// ---------------------------------------------------------------------------
// The Files pane
// ---------------------------------------------------------------------------

export function FilesPane({ entry, entrySize, files, lock, open, folder, onOpen, onManifest, onNew, onRename, onDelete, onPackages }: {
	entry: string;
	entrySize: number;
	files: DraftFiles;
	lock: Lock | null;
	open: string;
	folder: string | null;
	onOpen: (path: string) => void;
	onManifest: () => void;
	onNew: () => void;
	onRename: (path: string) => void;
	onDelete: (path: string) => void;
	onPackages: () => void;
}) {
	const paths = Object.keys(files).sort(byGroup(entry, lock));
	const groups: [FileGroup, string][] = [['module', S.filesModules], ['asset', S.filesAssets], ['locale', S.filesLocales]];
	const row = (path: string, size: number, note: string | null, actions: boolean, onClick: () => void) => (
		<div key={path} class={`wm-file-row ${open === path ? 'wm-file-row-open' : ''}`} role="button" tabIndex={0} onClick={onClick} onKeyDown={(e) => { if (e.key === 'Enter') onClick(); }}>
			<code class="wm-file-name">{path}</code>
			<span class="wm-caption">{note ?? fmtBytes(size)}</span>
			{actions && (
				<span class="wm-file-actions" onClick={(e) => e.stopPropagation()}>
					<IconButton label={S.filesRenameAction} onClick={() => onRename(path)}><IconRenameSvg /></IconButton>
					<IconButton label={S.filesDeleteAction} onClick={() => onDelete(path)}><IconDeleteSvg /></IconButton>
				</span>
			)}
		</div>
	);
	const packages = lock ? Object.entries(lock.packages).sort(([a], [b]) => a.localeCompare(b)) : [];
	// A package's licences are listed with the package.
	const outside = paths.filter((p) => groupOf(p, entry, lock) === 'outside' && !(lock && ownerOf(p, lock)));
	return (
		<div class="wm-pane" style="display:flex;flex-direction:column;gap:4px">
			<div class="wm-row-between">
				{folder && <span class="wm-caption" style="flex:1 1 auto"><IconFolderSvg /> {folder}</span>}
				<span style="flex:1 1 auto" />
				<TextButton onClick={onNew}>{S.filesNewAction}</TextButton>
				<TextButton onClick={onPackages}>{S.packagesAction}</TextButton>
			</div>
			{row(MANIFEST_FILE, 0, S.filesManifestNote, false, onManifest)}
			{row(entry, entrySize, null, false, () => onOpen(ENTRY))}
			{groups.map(([group, title]) => {
				const list = paths.filter((p) => groupOf(p, entry, lock) === group);
				if (!list.length) return null;
				return (
					<div key={group}>
						<h3 class="wm-heading">{title}</h3>
						{list.map((p) => row(p, sizeOf(files[p]!), null, true, () => onOpen(p)))}
					</div>
				);
			})}
			{packages.length > 0 && (
				<div>
					<h3 class="wm-heading">{S.filesPackages}</h3>
					{packages.map(([name, p]) => {
						const own = p.files.filter((f) => f in files);
						return (
							<details key={name} class="wm-file-package">
								<summary><IconPackageSvg /> <b>{name}</b> {p.version} <span class="wm-caption">{S.packageFiles(own.length, fmtBytes(own.reduce((n, f) => n + sizeOf(files[f]!), 0)))}</span></summary>
								{own.map((f) => row(f, sizeOf(files[f]!), null, false, () => onOpen(f)))}
							</details>
						);
					})}
				</div>
			)}
			{outside.length > 0 && (
				<div>
					<h3 class="wm-heading">{S.filesOutside}</h3>
					<div class="wm-caption">{S.filesOutsideNote}</div>
					{outside.map((p) => row(p, sizeOf(files[p]!), null, true, () => onOpen(p)))}
				</div>
			)}
		</div>
	);
}

// ---------------------------------------------------------------------------
// Dialogs and fields
// ---------------------------------------------------------------------------

/** Four path parts of 64 characters, as long as a plugin file's name can be. */
const MAX_FILE_NAME = 4 * 65;

export function FileNameDialog({ title, initial, confirm, error, onDismiss, onConfirm }: { title: string; initial: string; confirm: string; error: string | null; onDismiss: () => void; onConfirm: (name: string) => void }) {
	const [name, setName] = useState(initial);
	return (
		<Dialog title={title} onDismiss={onDismiss} actions={<><TextButton onClick={onDismiss}>{S.cancel}</TextButton><TextButton onClick={() => onConfirm(name)}>{confirm}</TextButton></>}>
			<label class="st-field">
				<span>{S.filesNameHint}</span>
				<input class="st-input" style="font-family:var(--wmc-mono)" value={name} autoFocus spellcheck={false} autocapitalize="off" aria-invalid={error !== null} onInput={(e) => setName((e.target as HTMLInputElement).value.substring(0, MAX_FILE_NAME))} onKeyDown={(e) => { if (e.key === 'Enter') onConfirm(name); }} />
				<span class={error ? 'wm-error-text' : 'wm-caption'}>{error ?? S.filesNameHelp}</span>
			</label>
		</Dialog>
	);
}

/** A translation, a JSON or text asset, a note: numbered lines aside, nothing to colour or check. */
export function PlainTextSurface({ value, onInput, fontSize, wrap }: { value: string; onInput: (text: string) => void; fontSize: number; wrap: boolean }) {
	const field = useRef<HTMLTextAreaElement>(null);
	useEffect(() => field.current?.focus(), []);
	return (
		<div class="wmc-surface wm-plain-surface">
			<textarea
				ref={field}
				class="wm-plaintext"
				style={{ fontSize: `${fontSize}px`, whiteSpace: wrap ? 'pre-wrap' : 'pre' }}
				value={value}
				spellcheck={false}
				autocapitalize="off"
				autocorrect="off"
				onInput={(e) => onInput((e.target as HTMLTextAreaElement).value)}
				onKeyDown={(e) => {
					if (e.key !== 'Tab') return;
					e.preventDefault();
					const t = e.target as HTMLTextAreaElement;
					const at = t.selectionStart;
					t.setRangeText('  ', at, t.selectionEnd, 'end');
					onInput(t.value);
				}}
			/>
		</div>
	);
}

export function ConflictDialog({ paths, onKeep }: { paths: string[]; onKeep: (keep: 'draft' | 'folder') => void }) {
	return (
		<Dialog title={S.conflictTitle} onDismiss={() => undefined} actions={<><TextButton onClick={() => onKeep('folder')}>{S.conflictKeepFolder}</TextButton><TextButton onClick={() => onKeep('draft')}>{S.conflictKeepEditor}</TextButton></>}>
			<p>{S.conflictBody}</p>
			<ul class="wm-conflicts">{paths.map((p) => <li key={p}><code>{p}</code></li>)}</ul>
		</Dialog>
	);
}

/** Under the bar while the folder is not live: why, and what to press. */
export function FolderBanner({ state, detail, name, onReconnect, onUnlink }: { state: LinkState; detail?: string; name: string; onReconnect: () => void; onUnlink: () => void }) {
	if (state === 'live') return null;
	const words = state === 'connecting' ? S.folderConnecting
		: state === 'permission' ? S.folderNeedsPermission(name)
		: state === 'denied' ? S.folderDenied(name)
		: state === 'missing' ? S.folderMissing(name)
		: S.folderError(detail ?? '');
	return (
		<div class={`wm-banner ${state === 'connecting' ? '' : 'wm-banner-warn'}`} role="status">
			<IconFolderSvg />
			<span style="flex:1 1 auto">{words}</span>
			{state !== 'connecting' && state !== 'missing' && <button type="button" class="st-btn st-btn-sm st-btn-primary" onClick={onReconnect}>{S.folderReconnect}</button>}
			{state !== 'connecting' && <TextButton onClick={onUnlink}>{S.folderUnlinkAction}</TextButton>}
		</div>
	);
}

// ---------------------------------------------------------------------------
// A folder as a draft
// ---------------------------------------------------------------------------

export type FolderOpen =
	| { kind: 'opened'; draftId: string; tooBig: string[] }
	| { kind: 'no-manifest' }
	| { kind: 'refused'; message: string };

/** The draft already linked to [handle], if one is. */
export async function linkedDraftOf(handle: FileSystemDirectoryHandle): Promise<string | null> {
	for (const [draftId, record] of await folderLinks.all()) {
		if (workspace.draft(draftId) && (await sameFolder(record.handle, handle))) return draftId;
	}
	return null;
}

/** plugin.json's text as a manifest the editor holds; null with why when it is not JSON. */
export function manifestOfText(text: string): { manifest: PluginManifest } | { error: string } {
	let raw: unknown;
	try {
		raw = JSON.parse(text);
	} catch (e) {
		return { error: (e as Error).message };
	}
	if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return { error: 'not an object' };
	const m = raw as Partial<PluginManifest>;
	return { manifest: sanitised({ ...blankManifest(), ...m, permissions: Array.isArray(m.permissions) ? m.permissions.map(String) : [] } as PluginManifest) };
}

/**
 * Opens [handle] as a draft: the one already linked to it, or a new one with
 * every file the folder holds. A folder with no plugin.json is reported so
 * the editor can offer to start a plugin there.
 */
export async function openFolderDraft(handle: FileSystemDirectoryHandle): Promise<FolderOpen> {
	const existing = await linkedDraftOf(handle);
	if (existing) return { kind: 'opened', draftId: existing, tooBig: [] };
	const listing = await listFolder(handle);
	if (listing.tooMany) return { kind: 'refused', message: S.folderTooMany(MAX_FOLDER_FILES) };
	const manifestEntry = listing.entries.get(MANIFEST_FILE);
	if (!manifestEntry) return { kind: 'no-manifest' };
	const manifestBytes = await readDiskEntry(manifestEntry);
	const read = manifestOfText(new TextDecoder().decode(manifestBytes));
	if ('error' in read) return { kind: 'refused', message: S.folderBadManifest(read.error) };
	const manifest = read.manifest;
	const entry = sanitise(manifest.entry, 'ENTRY');
	const synced: Record<string, [string, string]> = { [MANIFEST_FILE]: [fingerprint(manifestBytes), fingerprint(manifestText(manifest))] };
	const files: DraftFiles = {};
	let script = '';
	for (const [path, e] of listing.entries) {
		if (path === MANIFEST_FILE) continue;
		const bytes = await readDiskEntry(e);
		const data = fileDataOf(path, bytes);
		if (path === entry) {
			script = typeof data === 'string' ? data : '';
			synced[path] = [fingerprint(bytes), fingerprint(script)];
			continue;
		}
		files[path] = data;
		synced[path] = [fingerprint(bytes), fingerprint(data)];
	}
	const draft = workspace.create(manifest, script, `folder:${handle.name}`);
	if (!draft) return { kind: 'refused', message: S.newError };
	await draftFiles.write(draft.draftId, files);
	await folderLinks.put(draft.draftId, { handle, name: handle.name, synced });
	return { kind: 'opened', draftId: draft.draftId, tooBig: listing.tooBig };
}

/** Writes a blank plugin named after [handle] into it, then opens it. */
export async function startPluginInFolder(handle: FileSystemDirectoryHandle, id: string): Promise<FolderOpen> {
	const manifest: PluginManifest = { ...blankManifest(), id, name: sanitise(handle.name, 'NAME') || S.newDefaultName, pluginVersion: '0.1.0', apiVersion: 5 };
	await writePath(handle, MANIFEST_FILE, new TextEncoder().encode(manifestText(manifest)));
	await writePath(handle, manifest.entry, new TextEncoder().encode(BLANK_PLUGIN_SCRIPT));
	return openFolderDraft(handle);
}

/**
 * Writes a draft's files into [handle], for a draft being saved into a
 * folder: what each path then holds on both sides, for the link to start
 * from. Paths the folder holds already are written over.
 */
export async function writeDraftIntoFolder(handle: FileSystemDirectoryHandle, manifest: PluginManifest, script: string, files: DraftFiles): Promise<Record<string, [string, string]>> {
	const synced: Record<string, [string, string]> = {};
	const encode = (s: string) => new TextEncoder().encode(s);
	const all: [string, string | Uint8Array][] = [[MANIFEST_FILE, manifestText(manifest)], [sanitise(manifest.entry, 'ENTRY'), script], ...Object.entries(files).filter(([p]) => !skipped(p))];
	for (const [path, data] of all) {
		const bytes = typeof data === 'string' ? encode(data) : data;
		await writePath(handle, path, bytes);
		synced[path] = [fingerprint(bytes), fingerprint(data)];
	}
	return synced;
}

/**
 * Opens a .wmplugin as a new draft, its other modules, assets and
 * translations with it (PluginWorkspace.adoptFiles). Nothing is installed.
 */
export async function importPluginFile(bytes: Uint8Array): Promise<{ draftId: string } | { error: string }> {
	try {
		const read = readPlugin(bytes);
		const refusal = read.problems.find((p) => /"id"|"name"|"pluginVersion"|apiVersion|Permission|"language"|no longer runs/.test(p));
		if (refusal) return { error: refusal };
		const script = read.files.find((x) => x.name === read.manifest.entry)?.text;
		if (script === null || script === undefined) return { error: S.importNotAPlugin };
		const draft = workspace.create({ ...blankManifest(), ...read.manifest }, script, 'import');
		if (!draft) return { error: S.newError };
		const others: DraftFiles = {};
		for (const e of readZip(bytes, 128)) if (isPluginFile(e.name, read.manifest.entry)) others[e.name] = fileDataOf(e.name, e.data);
		if (Object.keys(others).length) await draftFiles.write(draft.draftId, others);
		workspace.snapshot(draft.draftId, 'import');
		return { draftId: draft.draftId };
	} catch (e) {
		return { error: /plugin\.json|zip/i.test(String((e as Error).message)) ? S.importNotAPlugin : S.importFailed };
	}
}

/** A path a file beside the plugin can have: inside the folder, not hidden, not plugin.json. */
export function isSidePath(path: string): boolean {
	const parts = path.split('/');
	return path.length > 0 && !path.startsWith('/') && parts.every((p) => p && p !== '.' && p !== '..') && !skipped(path) && path !== MANIFEST_FILE;
}

export { isTestFile };

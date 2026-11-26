/**
 * A draft's files besides plugin.json and the entry script (PluginWorkspace's
 * `files/` folder on the phone): its other modules, assets and translations,
 * and, for a draft opened from a folder or given Python packages, the files
 * around the plugin that are not part of it (tests, wmpy.lock, licences).
 *
 * The manifest and the entry stay in localStorage as before (ide-store.ts),
 * so a draft with no other files is stored exactly as it always was. The rest
 * go in IndexedDB, one record a file, with the folder a draft is linked to.
 */
import { writeZip } from '../../../lib/zip';
import * as Py from '../../../lib/python';
import { LICENSES_DIR, LOCK_FILE, lockRoots, parseLock, type Lock } from '../../../lib/pypi';
import { PACKAGE_API, sanitised, type PluginManifest } from './ide-store';
import { S } from './strings';

export type FileData = string | Uint8Array;
/** Path to content, for every file but plugin.json and the entry. */
export type DraftFiles = Record<string, FileData>;

export const MANIFEST_FILE = 'plugin.json';

// ---------------------------------------------------------------------------
// What a plugin file can be called (PluginFile, PluginWorkspace, PluginLocales)
// ---------------------------------------------------------------------------

export const MAX_ENTRIES = 64;
/** Files besides the manifest and the entry, so an export stays inside [MAX_ENTRIES]. */
export const MAX_FILES = MAX_ENTRIES - 2;
export const MAX_TOTAL_BYTES = 4 * 1024 * 1024;
export const MAX_ASSET_BYTES = 512 * 1024;
export const MAX_PYTHON_BYTES = 256 * 1024;
export const MAX_LOCALE_BYTES = 64 * 1024;
export const ASSETS_PREFIX = 'assets/';
export const LOCALES_PREFIX = 'locales/';

const ASSET_NAME = /^[a-z0-9][a-z0-9_.-]{0,63}\.(png|webp|jpg|jpeg|svg|json|txt)$/;
const MODULE_PART = /^[A-Za-z_][A-Za-z0-9_]{0,63}$/;
const LOCALE_TAG = /^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$/;
const TEXT_ASSET_EXTENSIONS = new Set(['json', 'txt', 'svg']);

/** Identifiers between slashes, at most four deep, ending `.py`: what `import` reaches. */
export function isModuleName(path: string): boolean {
	if (!path.endsWith('.py')) return false;
	const parts = path.slice(0, -3).split('/');
	return parts.length <= 4 && parts.every((p) => MODULE_PART.test(p));
}

export const isAssetName = (name: string) => ASSET_NAME.test(name) && !name.includes('..');

/** `locales/pt-BR.json` → `pt-BR`; null for anything else. */
export function localeTag(path: string): string | null {
	if (!path.startsWith(LOCALES_PREFIX) || !path.endsWith('.json')) return null;
	const tag = path.slice(LOCALES_PREFIX.length, -5);
	return LOCALE_TAG.test(tag) ? tag : null;
}

/** Whether [path] goes in the plugin: a module, an asset or a translation, named as a `.wmplugin` holds them. */
export function isPluginFile(path: string, entry: string): boolean {
	if (path === entry || path === MANIFEST_FILE) return false;
	if (isTestFile(path)) return false;
	return isModuleName(path) || (path.startsWith(ASSETS_PREFIX) && isAssetName(path.slice(ASSETS_PREFIX.length))) || localeTag(path) !== null;
}

/** `tests/` and `test.py`: run by `wmpy test`, never installed. */
export const isTestFile = (path: string) => path === 'test.py' || path.startsWith('tests/');

const TEXT_EXTENSIONS = new Set(['py', 'pyi', 'json', 'txt', 'md', 'rst', 'toml', 'cfg', 'ini', 'csv', 'tsv', 'yaml', 'yml', 'xml', 'svg', 'html', 'css', 'js', 'ts', 'po', 'lock', 'typed', 'sh', 'gitignore']);

/** Whether [path] holds text the editor can open: everything but pictures and other binary files. */
export function isTextFile(path: string): boolean {
	const name = path.split('/').pop() ?? path;
	if (path.startsWith(ASSETS_PREFIX)) return TEXT_ASSET_EXTENSIONS.has(name.split('.').pop()?.toLowerCase() ?? '');
	if (!name.includes('.')) return true;
	if (/^(licen[cs]e|copying|notice|authors|readme|changelog)/i.test(name)) return true;
	return TEXT_EXTENSIONS.has(name.split('.').pop()!.toLowerCase());
}

/** The group a file is listed under, in this order. */
export type FileGroup = 'module' | 'asset' | 'locale' | 'package' | 'outside';

/** Where [path] shows in the file list, given the lock's packages. */
export function groupOf(path: string, entry: string, lock: Lock | null): FileGroup {
	if (lock && ownerOf(path, lock)) return isPluginFile(path, entry) ? 'package' : 'outside';
	if (!isPluginFile(path, entry)) return 'outside';
	if (path.endsWith('.py')) return 'module';
	if (path.startsWith(ASSETS_PREFIX)) return 'asset';
	return 'locale';
}

/** The vendored package that put [path] in the plugin, by normalized name. */
export function ownerOf(path: string, lock: Lock): string | null {
	for (const [name, p] of Object.entries(lock.packages)) if (p.files.includes(path)) return name;
	return null;
}

/** Modules by dotted name, `pkg/util.py` as `pkg.util` and `pkg/__init__.py` as `pkg`. */
export function moduleNames(paths: string[], entry: string): string[] {
	const out = new Set<string>();
	for (const p of paths) {
		if (!isPluginFile(p, entry) || !p.endsWith('.py')) continue;
		const dotted = p.slice(0, -3).replace(/\//g, '.');
		out.add(dotted.replace(/\.__init__$/, ''));
		// A package's name imports too: `import pkg` for `pkg/util.py`.
		const parts = dotted.split('.');
		for (let i = 1; i < parts.length; i++) out.add(parts.slice(0, i).join('.'));
	}
	return [...out].sort();
}

/** [name] as a new file's path: trimmed, with `.py` added to a bare name (PluginIdeState.fileName). */
export function newFilePath(name: string): string {
	const trimmed = name.trim().replace(/^\/+|\/+$/g, '');
	return (trimmed.split('/').pop() ?? '').includes('.') ? trimmed : `${trimmed}.py`;
}

/** Whether a text file the editor makes can be called [path]. Pictures come with a plugin; they are not typed. */
export const canName = (path: string, entry: string) => isPluginFile(path, entry) && isTextFile(path);

// ---------------------------------------------------------------------------
// Text and bytes
// ---------------------------------------------------------------------------

const utf8 = new TextEncoder();
// ignoreBOM keeps a byte order mark in the text, so a file read and written back is the same bytes.
const strictUtf8 = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });

/** The editor works in \n; a file written with \r\n goes back with \r\n. */
export const lf = (text: string) => text.replace(/\r\n/g, '\n');
export const eolOf = (text: string): '\n' | '\r\n' => (text.includes('\r\n') ? '\r\n' : '\n');
export const withEol = (text: string, eol: '\n' | '\r\n') => (eol === '\n' ? text : text.replace(/\r?\n/g, eol));

export const bytesOf = (data: FileData): Uint8Array => (typeof data === 'string' ? utf8.encode(data) : data);
export const sizeOf = (data: FileData) => (typeof data === 'string' ? utf8.encode(data).byteLength : data.byteLength);

/** What the draft keeps for a file read from disk: text where the editor can open it, bytes otherwise. */
export function fileDataOf(path: string, bytes: Uint8Array): FileData {
	if (!isTextFile(path)) return bytes;
	try {
		return strictUtf8.decode(bytes);
	} catch {
		return bytes;
	}
}

export const textOf = (data: FileData | undefined): string | null => (typeof data === 'string' ? data : null);

/** A short fingerprint of some bytes (FNV-1a, two lanes), for telling whether a file changed. */
export function fingerprint(data: FileData): string {
	const bytes = bytesOf(data);
	let a = 0x811c9dc5;
	let b = 0x01000193 ^ bytes.length;
	for (let i = 0; i < bytes.length; i++) {
		a = Math.imul(a ^ bytes[i]!, 0x01000193);
		b = Math.imul(b ^ bytes[i]!, 0x5bd1e995);
	}
	return `${bytes.length.toString(36)}.${(a >>> 0).toString(36)}.${(b >>> 0).toString(36)}`;
}

/** plugin.json as the editor writes it. */
export const manifestText = (m: PluginManifest) => JSON.stringify(sanitised(m), null, 2) + '\n';

// ---------------------------------------------------------------------------
// IndexedDB: the files, and the folder each draft is linked to
// ---------------------------------------------------------------------------

const DB_NAME = 'wm.ide';
const DB_VERSION = 1;
const FILES = 'files';
const FOLDERS = 'folders';

/** A draft linked to a folder on the computer. */
export interface FolderRecord {
	handle: FileSystemDirectoryHandle;
	name: string;
	/** Per path, the fingerprints of the folder's copy and the draft's copy when they last agreed. */
	synced: Record<string, [string, string]>;
}

let opening: Promise<IDBDatabase | null> | null = null;

function db(): Promise<IDBDatabase | null> {
	if (!opening) {
		opening = new Promise((resolve) => {
			try {
				const req = indexedDB.open(DB_NAME, DB_VERSION);
				req.onupgradeneeded = () => {
					const d = req.result;
					if (!d.objectStoreNames.contains(FILES)) d.createObjectStore(FILES);
					if (!d.objectStoreNames.contains(FOLDERS)) d.createObjectStore(FOLDERS);
				};
				req.onsuccess = () => resolve(req.result);
				req.onerror = () => resolve(null);
				req.onblocked = () => resolve(null);
			} catch {
				resolve(null);
			}
		});
	}
	return opening;
}

function done<T>(req: IDBRequest<T>): Promise<T> {
	return new Promise((resolve, reject) => {
		req.onsuccess = () => resolve(req.result);
		req.onerror = () => reject(req.error);
	});
}

function finished(tx: IDBTransaction): Promise<boolean> {
	return new Promise((resolve) => {
		tx.oncomplete = () => resolve(true);
		tx.onerror = () => resolve(false);
		tx.onabort = () => resolve(false);
	});
}

const fileKey = (draftId: string, path: string) => `${draftId}\u0000${path}`;
const draftRange = (draftId: string) => IDBKeyRange.bound(`${draftId}\u0000`, `${draftId}\u0001`, false, true);

/** Kept in memory when IndexedDB is not there (a private window that refuses it): the draft works until the tab closes. */
const fallback = new Map<string, DraftFiles>();

export const draftFiles = {
	async load(draftId: string): Promise<DraftFiles> {
		const d = await db();
		if (!d) return { ...(fallback.get(draftId) ?? {}) };
		try {
			const store = d.transaction(FILES, 'readonly').objectStore(FILES);
			const [keys, values] = await Promise.all([done(store.getAllKeys(draftRange(draftId))), done(store.getAll(draftRange(draftId)))]);
			const out: DraftFiles = {};
			keys.forEach((k, i) => {
				const path = String(k).slice(draftId.length + 1);
				const v = values[i] as FileData;
				out[path] = typeof v === 'string' ? v : new Uint8Array(v);
			});
			return out;
		} catch {
			return {};
		}
	},

	/** Writes [changes]: content for a new or changed file, null for one removed. */
	async write(draftId: string, changes: Record<string, FileData | null>): Promise<boolean> {
		const d = await db();
		if (!d) {
			const held = fallback.get(draftId) ?? {};
			for (const [p, v] of Object.entries(changes)) if (v === null) delete held[p]; else held[p] = v;
			fallback.set(draftId, held);
			return true;
		}
		try {
			const tx = d.transaction(FILES, 'readwrite');
			const store = tx.objectStore(FILES);
			for (const [p, v] of Object.entries(changes)) {
				if (v === null) store.delete(fileKey(draftId, p));
				else store.put(v, fileKey(draftId, p));
			}
			return await finished(tx);
		} catch {
			return false;
		}
	},

	async remove(draftId: string): Promise<void> {
		fallback.delete(draftId);
		const d = await db();
		if (!d) return;
		try {
			const tx = d.transaction([FILES, FOLDERS], 'readwrite');
			tx.objectStore(FILES).delete(draftRange(draftId));
			tx.objectStore(FOLDERS).delete(draftId);
			await finished(tx);
		} catch {
			/* gone with the draft anyway */
		}
	},

	async copy(from: string, to: string): Promise<boolean> {
		const files = await this.load(from);
		return Object.keys(files).length === 0 || this.write(to, files);
	},
};

export const folderLinks = {
	async get(draftId: string): Promise<FolderRecord | null> {
		const d = await db();
		if (!d) return null;
		try {
			return ((await done(d.transaction(FOLDERS, 'readonly').objectStore(FOLDERS).get(draftId))) as FolderRecord | undefined) ?? null;
		} catch {
			return null;
		}
	},

	async put(draftId: string, record: FolderRecord): Promise<boolean> {
		const d = await db();
		if (!d) return false;
		try {
			const tx = d.transaction(FOLDERS, 'readwrite');
			tx.objectStore(FOLDERS).put(record, draftId);
			return await finished(tx);
		} catch {
			return false;
		}
	},

	async remove(draftId: string): Promise<void> {
		const d = await db();
		if (!d) return;
		try {
			const tx = d.transaction(FOLDERS, 'readwrite');
			tx.objectStore(FOLDERS).delete(draftId);
			await finished(tx);
		} catch {
			/* already gone */
		}
	},

	/** Every linked draft, by draft id. */
	async all(): Promise<Map<string, FolderRecord>> {
		const d = await db();
		const out = new Map<string, FolderRecord>();
		if (!d) return out;
		try {
			const store = d.transaction(FOLDERS, 'readonly').objectStore(FOLDERS);
			const [keys, values] = await Promise.all([done(store.getAllKeys()), done(store.getAll())]);
			keys.forEach((k, i) => out.set(String(k), values[i] as FolderRecord));
		} catch {
			/* none */
		}
		return out;
	},
};

// ---------------------------------------------------------------------------
// The .wmplugin a draft exports
// ---------------------------------------------------------------------------

export interface ArchiveResult {
	/** The file, made even when [problems] would refuse it, so its size can be shown. */
	bytes: Uint8Array;
	/** What the importer would refuse it for: save and install stop at the first. */
	problems: string[];
	entries: number;
	pythonBytes: number;
	/** What tree shaking took out; null when there was nothing to shake or this build cannot. */
	shake: Py.PipShake | null;
	/** The draft has packages but this build has no tree shaking. */
	shakeMissing: boolean;
	/** Files of the draft that stay out of the plugin: tests, wmpy.lock, notes. */
	leftOut: string[];
}

export const readLock = (files: DraftFiles): Lock | null => parseLock(textOf(files[LOCK_FILE]) ?? '');

/**
 * The plugin file Save as a file and Install write, as PluginFile.write
 * makes it: plugin.json, the entry, then the modules, assets and
 * translations, with the licences of vendored packages beside them. The
 * vendored packages are tree-shaken first when the build can. A draft with
 * no other files gives the same two-entry file it always did.
 */
export function buildArchive(manifest: PluginManifest, entryText: string, files: DraftFiles, shakeSymbols = false): ArchiveResult {
	const m = sanitised(manifest);
	const entry = m.entry;
	const name = m.name;
	const problems: string[] = [];
	const leftOut: string[] = [];
	const modules: Record<string, string> = {};
	const others: { name: string; data: FileData }[] = [];
	const lock = readLock(files);
	const licenceFiles = new Set(lock ? Object.values(lock.packages).flatMap((p) => p.files.filter((f) => f.startsWith(`${LICENSES_DIR}/`))) : []);
	for (const path of Object.keys(files).sort()) {
		const data = files[path]!;
		if (path === entry || path === MANIFEST_FILE) continue;
		if (isPluginFile(path, entry)) {
			if (path.endsWith('.py')) {
				const text = textOf(data);
				if (text === null) problems.push(S.fileCannotLoad(name, path));
				else modules[path] = text;
			} else if (path.startsWith(ASSETS_PREFIX)) {
				if (sizeOf(data) > MAX_ASSET_BYTES) problems.push(S.fileCannotLoad(name, path));
				others.push({ name: path, data });
			} else {
				const text = textOf(data);
				if (text === null || sizeOf(data) > MAX_LOCALE_BYTES || !isLocaleFile(text)) problems.push(S.rejectBadLocale(name, path));
				others.push({ name: path, data });
			}
		} else if (licenceFiles.has(path)) {
			others.push({ name: path, data });
		} else {
			// A .py file outside tests/ that import cannot reach refuses the install.
			if (path.endsWith('.py') && !isTestFile(path) && !isModuleName(path)) problems.push(S.fileCannotLoad(name, path));
			leftOut.push(path);
		}
	}
	const own = Object.keys(modules).length + others.length;
	if (own > 0 && m.apiVersion < PACKAGE_API) problems.push(S.filesNeedsApi(entry, PACKAGE_API));
	// Tree shaking: only the vendored packages' files are trimmed.
	let shake: Py.PipShake | null = null;
	let shakeMissing = false;
	const roots = lock ? lockRoots(lock) : [];
	let python: Record<string, string> = { [entry]: entryText, ...modules };
	if (roots.length) {
		shake = Py.pipShake(python, entry, roots, shakeSymbols);
		if (shake) python = { ...shake.files, [entry]: shake.files[entry] ?? entryText };
		else shakeMissing = !Py.pipAvailable().shake;
	}
	const pythonBytes = Object.values(python).reduce((n, s) => n + utf8.encode(s).byteLength, 0);
	// A one-file plugin keeps the rule it always had: Install checks the script's size (MAX_SCRIPT_BYTES).
	if (Object.keys(python).length > 1 && pythonBytes > MAX_PYTHON_BYTES) problems.push(S.pythonTooLarge(name, Math.round(pythonBytes / 1024), MAX_PYTHON_BYTES / 1024));
	const entries = [
		{ name: MANIFEST_FILE, data: JSON.stringify(m, null, 2) + '\n' as FileData },
		{ name: entry, data: python[entry]! as FileData },
		...Object.keys(python).filter((p) => p !== entry).sort().map((p) => ({ name: p, data: python[p]! as FileData })),
		...others,
	];
	if (entries.length > MAX_ENTRIES) problems.push(S.filesTooMany(MAX_FILES));
	const total = entries.reduce((n, e) => n + sizeOf(e.data), 0);
	if (total > MAX_TOTAL_BYTES) problems.push(S.rejectArchiveTooLarge(name, MAX_TOTAL_BYTES / 1024));
	return {
		bytes: writeZip(entries.map((e) => ({ name: e.name, data: e.data }))),
		problems,
		entries: entries.length,
		pythonBytes,
		shake,
		shakeMissing,
		leftOut,
	};
}

/** PluginLocales.parse: an object of strings, or of plural forms holding strings. */
export function isLocaleFile(text: string): boolean {
	let root: unknown;
	try {
		root = JSON.parse(text);
	} catch {
		return false;
	}
	if (!root || typeof root !== 'object' || Array.isArray(root)) return false;
	const forms = new Set(['zero', 'one', 'two', 'few', 'many', 'other']);
	return Object.values(root as Record<string, unknown>).every((v) =>
		typeof v === 'string' || (!!v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length > 0 && Object.entries(v as Record<string, unknown>).every(([k, x]) => forms.has(k) && typeof x === 'string')),
	);
}

/**
 * What a run of the draft takes besides the entry (PluginIdeState.runInputs):
 * the modules, and the translation the browser's languages pick, whole, with
 * `@lang` naming it.
 */
export function runInputs(files: DraftFiles, entry: string, languages: readonly string[]): { files: Record<string, string>; strings: Record<string, unknown> } {
	const modules: Record<string, string> = {};
	const locales: Record<string, string> = {};
	for (const [path, data] of Object.entries(files)) {
		const text = textOf(data);
		if (text === null || !isPluginFile(path, entry)) continue;
		if (path.endsWith('.py')) modules[path] = text;
		const tag = localeTag(path);
		if (tag) locales[tag] = text;
	}
	const tag = pickLocale(Object.keys(locales), languages);
	let strings: Record<string, unknown> = {};
	if (tag) {
		try {
			const parsed = JSON.parse(locales[tag]!) as Record<string, unknown>;
			if (isLocaleFile(locales[tag]!)) strings = { ...parsed, '@lang': tag };
		} catch {
			/* English stands */
		}
	}
	return { files: modules, strings };
}

/** PluginLocales.pick: the whole tag, then the language, then any tag of that language. */
export function pickLocale(available: string[], preferred: readonly string[]): string | null {
	for (const full of preferred) {
		const language = full.split('-')[0]!.toLowerCase();
		const hit = available.find((t) => t.toLowerCase() === full.toLowerCase()) ?? available.find((t) => t.toLowerCase() === language) ?? available.find((t) => t.split('-')[0]!.toLowerCase() === language);
		if (hit) return hit;
	}
	return null;
}

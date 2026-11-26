/**
 * A plugin folder on the computer, through the File System Access API
 * (Chromium only): picking it, reading every file in it, writing files back,
 * and noticing when something else, VS Code say, saves a file there.
 *
 * FileSystemObserver tells of changes where the browser has it; elsewhere the
 * folder is walked every 1.5 seconds while the tab is visible, comparing each
 * file's lastModified and size.
 */

/** Folders never read: version control, wmpy's own, packages for other tools, caches. */
const SKIP_DIRS = new Set(['.git', '.wmpy', 'node_modules', '__pycache__']);
/** The most a folder may hold for the editor to read it all. */
export const MAX_FOLDER_FILES = 1000;
export const MAX_FOLDER_BYTES = 32 * 1024 * 1024;
/** A file over this cannot be in a plugin, which unpacks to 4 MB at most. */
export const MAX_FOLDER_FILE_BYTES = 4 * 1024 * 1024;
export const POLL_MS = 1500;

/** Whether the folder walk leaves [path] out. */
export function skipped(path: string): boolean {
	const parts = path.split('/');
	const name = parts[parts.length - 1]!;
	// Every hidden file and folder too: .vscode, .venv, .DS_Store, and the .crswap a browser write leaves for a moment.
	if (parts.some((p) => SKIP_DIRS.has(p) || p.startsWith('.'))) return true;
	return name.endsWith('.wmplugin') || name.endsWith('.pyc') || name.endsWith('.crswap');
}

type DirectoryPicker = (options?: { mode?: 'read' | 'readwrite'; id?: string; startIn?: string }) => Promise<FileSystemDirectoryHandle>;
type PermissionHandle = FileSystemHandle & {
	queryPermission?: (d: { mode: 'readwrite' }) => Promise<PermissionState>;
	requestPermission?: (d: { mode: 'readwrite' }) => Promise<PermissionState>;
};
type IterableDirectory = FileSystemDirectoryHandle & { entries(): AsyncIterableIterator<[string, FileSystemHandle]> };

export function folderSupported(): boolean {
	return typeof window !== 'undefined' && typeof (window as unknown as { showDirectoryPicker?: unknown }).showDirectoryPicker === 'function';
}

/** The folder the person picks, or null when they cancel. */
export async function pickFolder(): Promise<FileSystemDirectoryHandle | null> {
	const pick = (window as unknown as { showDirectoryPicker: DirectoryPicker }).showDirectoryPicker;
	try {
		return await pick({ mode: 'readwrite', id: 'wm-plugin-folder' });
	} catch (e) {
		if (e instanceof DOMException && e.name === 'AbortError') return null;
		throw e;
	}
}

/**
 * Whether the page may read and write [handle]. With [ask], prompts for it;
 * a browser only lets that happen in answer to a click.
 */
export async function folderPermission(handle: FileSystemDirectoryHandle, ask: boolean): Promise<PermissionState> {
	const h = handle as PermissionHandle;
	try {
		const now = (await h.queryPermission?.({ mode: 'readwrite' })) ?? 'granted';
		if (now === 'granted' || !ask) return now;
		return (await h.requestPermission?.({ mode: 'readwrite' })) ?? 'denied';
	} catch {
		return 'denied';
	}
}

export interface DiskEntry {
	path: string;
	handle: FileSystemFileHandle;
	lastModified: number;
	size: number;
}

export interface FolderListing {
	entries: Map<string, DiskEntry>;
	/** Over [MAX_FOLDER_FILES]: the walk stopped. */
	tooMany: boolean;
	/** Files too big to be in a plugin, left out. */
	tooBig: string[];
}

/** Every file under [root] the editor reads, by path from the root. */
export async function listFolder(root: FileSystemDirectoryHandle): Promise<FolderListing> {
	const entries = new Map<string, DiskEntry>();
	const tooBig: string[] = [];
	let tooMany = false;
	const walk = async (dir: FileSystemDirectoryHandle, prefix: string): Promise<void> => {
		for await (const [name, handle] of (dir as IterableDirectory).entries()) {
			if (tooMany) return;
			const path = prefix + name;
			if (skipped(path)) continue;
			if (handle.kind === 'directory') {
				await walk(handle as FileSystemDirectoryHandle, `${path}/`);
				continue;
			}
			const file = await (handle as FileSystemFileHandle).getFile();
			if (file.size > MAX_FOLDER_FILE_BYTES) {
				tooBig.push(path);
				continue;
			}
			if (entries.size >= MAX_FOLDER_FILES) {
				tooMany = true;
				return;
			}
			entries.set(path, { path, handle: handle as FileSystemFileHandle, lastModified: file.lastModified, size: file.size });
		}
	};
	await walk(root, '');
	return { entries, tooMany, tooBig };
}

export async function readDiskEntry(entry: DiskEntry): Promise<Uint8Array> {
	return new Uint8Array(await (await entry.handle.getFile()).arrayBuffer());
}

/** The file at [path], or null when there is none. */
export async function readPath(root: FileSystemDirectoryHandle, path: string): Promise<{ bytes: Uint8Array; lastModified: number; size: number } | null> {
	const handle = await fileHandle(root, path, false);
	if (!handle) return null;
	try {
		const file = await handle.getFile();
		return { bytes: new Uint8Array(await file.arrayBuffer()), lastModified: file.lastModified, size: file.size };
	} catch (e) {
		if (e instanceof DOMException && e.name === 'NotFoundError') return null;
		throw e;
	}
}

async function dirHandle(root: FileSystemDirectoryHandle, parts: string[], create: boolean): Promise<FileSystemDirectoryHandle | null> {
	let dir = root;
	for (const part of parts) {
		try {
			dir = await dir.getDirectoryHandle(part, { create });
		} catch (e) {
			if (!create && e instanceof DOMException && (e.name === 'NotFoundError' || e.name === 'TypeMismatchError')) return null;
			throw e;
		}
	}
	return dir;
}

function partsOf(path: string): { dirs: string[]; name: string } {
	const parts = path.split('/').filter(Boolean);
	if (!parts.length || parts.some((p) => p === '.' || p === '..')) throw new Error(`Not a path inside the folder: ${path}`);
	return { dirs: parts.slice(0, -1), name: parts[parts.length - 1]! };
}

async function fileHandle(root: FileSystemDirectoryHandle, path: string, create: boolean): Promise<FileSystemFileHandle | null> {
	const { dirs, name } = partsOf(path);
	const dir = await dirHandle(root, dirs, create);
	if (!dir) return null;
	try {
		return await dir.getFileHandle(name, { create });
	} catch (e) {
		if (!create && e instanceof DOMException && (e.name === 'NotFoundError' || e.name === 'TypeMismatchError')) return null;
		throw e;
	}
}

/** Writes [data] as [path], making the folders on the way. */
export async function writePath(root: FileSystemDirectoryHandle, path: string, data: Uint8Array): Promise<void> {
	const handle = (await fileHandle(root, path, true))!;
	const out = await handle.createWritable();
	try {
		await out.write(data as unknown as FileSystemWriteChunkType);
		await out.close();
	} catch (e) {
		await out.abort().catch(() => undefined);
		throw e;
	}
}

/** Deletes the file at [path], and the folders it leaves empty. */
export async function removePath(root: FileSystemDirectoryHandle, path: string): Promise<void> {
	const { dirs, name } = partsOf(path);
	const dir = await dirHandle(root, dirs, false);
	if (!dir) return;
	try {
		await dir.removeEntry(name);
	} catch (e) {
		if (!(e instanceof DOMException && e.name === 'NotFoundError')) throw e;
	}
	for (let depth = dirs.length; depth > 0; depth--) {
		const parent = await dirHandle(root, dirs.slice(0, depth - 1), false);
		const here = await dirHandle(root, dirs.slice(0, depth), false);
		if (!parent || !here) return;
		for await (const _ of (here as IterableDirectory).entries()) return;
		await parent.removeEntry(dirs[depth - 1]!).catch(() => undefined);
	}
}

/** A folder already linked to a draft, found by what the browser says is the same folder. */
export async function sameFolder(a: FileSystemDirectoryHandle, b: FileSystemDirectoryHandle): Promise<boolean> {
	try {
		return await a.isSameEntry(b);
	} catch {
		return false;
	}
}

// ---------------------------------------------------------------------------
// Noticing changes made outside the browser
// ---------------------------------------------------------------------------

interface ChangeRecord {
	type: 'appeared' | 'disappeared' | 'modified' | 'moved' | 'unknown' | 'errored';
	relativePathComponents: string[];
	relativePathMovedFrom: string[] | null;
}
type ObserverCtor = new (callback: (records: ChangeRecord[]) => void) => { observe(handle: FileSystemHandle, options?: { recursive?: boolean }): Promise<void>; disconnect(): void };

/**
 * Calls [onChange] with the paths that changed in [root], or with null when
 * the whole folder should be read again. Returns the way to stop.
 */
export function watchFolder(root: FileSystemDirectoryHandle, onChange: (paths: string[] | null) => void): () => void {
	let stopped = false;
	let stop: () => void = () => undefined;
	const Observer = (globalThis as unknown as { FileSystemObserver?: ObserverCtor }).FileSystemObserver;
	const poll = () => {
		if (stopped) return;
		stop = pollFolder(root, onChange);
	};
	if (Observer) {
		try {
			const observer = new Observer((records) => {
				if (stopped) return;
				const paths = new Set<string>();
				for (const r of records) {
					if (r.type === 'unknown' || r.type === 'errored') return onChange(null);
					for (const parts of [r.relativePathComponents, r.relativePathMovedFrom]) {
						const path = parts?.join('/');
						if (path && !skipped(path)) paths.add(path);
					}
				}
				// A folder that appears or goes takes its files with it.
				if (paths.size) onChange(records.some((r) => r.type !== 'modified') ? null : [...paths]);
			});
			observer.observe(root, { recursive: true }).then(
				() => { stop = () => observer.disconnect(); if (stopped) observer.disconnect(); },
				() => { observer.disconnect(); poll(); },
			);
			// Coming back to the tab reads the folder once, in case a change slipped by.
			const onVisible = () => { if (document.visibilityState === 'visible') onChange(null); };
			document.addEventListener('visibilitychange', onVisible);
			const inner = () => stop();
			return () => { stopped = true; document.removeEventListener('visibilitychange', onVisible); inner(); };
		} catch {
			/* fall back to polling */
		}
	}
	poll();
	return () => { stopped = true; stop(); };
}

/** The fallback: a walk every [POLL_MS] while the tab is visible. */
function pollFolder(root: FileSystemDirectoryHandle, onChange: (paths: string[] | null) => void): () => void {
	let seen: Map<string, { lastModified: number; size: number }> | null = null;
	let busy = false;
	let stopped = false;
	const tick = async () => {
		if (busy || stopped || document.visibilityState !== 'visible') return;
		busy = true;
		try {
			const { entries } = await listFolder(root);
			const now = new Map([...entries].map(([p, e]) => [p, { lastModified: e.lastModified, size: e.size }]));
			if (seen) {
				const changed: string[] = [];
				for (const [p, e] of now) {
					const before = seen.get(p);
					if (!before || before.lastModified !== e.lastModified || before.size !== e.size) changed.push(p);
				}
				for (const p of seen.keys()) if (!now.has(p)) changed.push(p);
				if (changed.length && !stopped) onChange(changed);
			}
			seen = now;
		} catch {
			// The folder went, or permission did: one full read reports it.
			if (!stopped) onChange(null);
		} finally {
			busy = false;
		}
	};
	void tick();
	const timer = setInterval(() => void tick(), POLL_MS);
	const onVisible = () => void tick();
	document.addEventListener('visibilitychange', onVisible);
	return () => {
		stopped = true;
		clearInterval(timer);
		document.removeEventListener('visibilitychange', onVisible);
	};
}

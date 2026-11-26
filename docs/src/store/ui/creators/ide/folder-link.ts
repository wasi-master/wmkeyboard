/**
 * Keeps a draft and the folder it is linked to in step, both ways.
 *
 * For every path it remembers the fingerprint of the folder's copy and of the
 * draft's copy from the last time the two agreed. A later look at a path is
 * then a three-way comparison: only the folder changed, so the draft takes
 * the folder's copy (VS Code saved the file); only the draft changed, so the
 * folder gets the draft's (an edit here, after the 400 ms autosave); both
 * changed, so the person chooses. Writes the editor makes come back from the
 * watcher as changes and match what was written, so they are not taken for
 * outside edits.
 */
import { fingerprint, folderLinks, MANIFEST_FILE, bytesOf, type FileData, type FolderRecord } from './draft-files';
import { folderPermission, listFolder, MAX_FOLDER_FILE_BYTES, readPath, removePath, skipped, watchFolder, writePath } from './folder';

export type LinkState = 'connecting' | 'live' | 'permission' | 'denied' | 'missing' | 'error';

/** What the link needs from the editor. */
export interface DraftSide {
	/** Every path the draft holds, plugin.json and the entry among them. */
	paths(): string[];
	/** The draft's copy of [path] now: plugin.json as the editor writes it, the open file as typed. Null when it has none. */
	content(path: string): FileData | null;
	/**
	 * Takes the folder's copy of [path], null when the folder no longer has it.
	 * Returns the draft's copy after, as [content] gives it, or `refused` when
	 * the draft keeps its own (plugin.json that is not JSON, the entry deleted).
	 */
	take(path: string, bytes: Uint8Array | null): FileData | null | 'refused';
	/** Paths that changed in both places since they last agreed. */
	conflicts(paths: string[]): void;
	state(state: LinkState, detail?: string): void;
}

const PERSIST_MS = 500;

export class FolderLink {
	private synced: Record<string, [string, string]>;
	private queue: Promise<void> = Promise.resolve();
	private unwatch: (() => void) | null = null;
	private persistTimer: ReturnType<typeof setTimeout> | null = null;
	private live = false;
	private closed = false;
	/** Unlinked: nothing is saved for this link any more. */
	private forgotten = false;

	constructor(readonly draftId: string, private record: FolderRecord, private readonly draft: DraftSide) {
		this.synced = { ...(record.synced ?? {}) };
	}

	get name(): string {
		return this.record.name;
	}

	get handle(): FileSystemDirectoryHandle {
		return this.record.handle;
	}

	get connected(): boolean {
		return this.live;
	}

	/**
	 * Connects: checks the permission (asking for it when [ask], which a
	 * browser allows only in answer to a click), brings both sides in step,
	 * then watches the folder.
	 */
	async start(ask: boolean): Promise<LinkState> {
		if (this.closed) return 'error';
		this.draft.state('connecting');
		const permission = await folderPermission(this.record.handle, ask);
		if (permission !== 'granted') {
			const state: LinkState = permission === 'denied' ? 'denied' : 'permission';
			this.draft.state(state);
			return state;
		}
		this.live = true;
		await this.enqueue(() => this.sync(null));
		if (!this.live || this.closed) return 'error';
		this.unwatch?.();
		this.unwatch = watchFolder(this.record.handle, (paths) => void this.enqueue(() => this.sync(paths)));
		this.draft.state('live');
		return 'live';
	}

	/**
	 * Stops watching. Writes already asked for still reach the folder; what is
	 * not written stays in the draft for the next connect. [keep] false is for
	 * Unlink: the record is not saved again after it was removed.
	 */
	stop(keep = true) {
		this.unwatch?.();
		this.unwatch = null;
		if (!keep) this.forgotten = true;
		void this.queue.then(() => {
			this.closed = true;
			this.live = false;
			if (this.persistTimer) {
				clearTimeout(this.persistTimer);
				this.persistTimer = null;
				if (keep) void this.persistNow();
			}
		});
	}

	/** The draft changed [paths] (saved, added, renamed, deleted): the folder gets them. */
	push(paths: string[]): Promise<void> {
		if (!this.live) return Promise.resolve();
		return this.enqueue(() => this.sync(paths));
	}

	/** Settles paths that changed in both places, keeping the draft's copy or the folder's. */
	resolve(paths: string[], keep: 'draft' | 'folder'): Promise<void> {
		return this.enqueue(async () => {
			for (const path of paths) {
				if (keep === 'draft') await this.writeDraft(path);
				else {
					const disk = await readPath(this.record.handle, path);
					this.takeDisk(path, disk?.bytes ?? null);
				}
			}
			this.persist();
		});
	}

	/** Forgets what was in step, so the next connect treats every difference as new. Used when a draft is first written into a folder. */
	adopt(synced: Record<string, [string, string]>) {
		this.synced = { ...synced };
		this.persist();
	}

	private enqueue(task: () => Promise<void>): Promise<void> {
		const next = this.queue.then(task).catch((e) => this.fail(e));
		this.queue = next;
		return next;
	}

	private fail(e: unknown) {
		const name = e instanceof DOMException ? e.name : '';
		this.live = false;
		this.unwatch?.();
		this.unwatch = null;
		if (this.closed) return;
		if (name === 'NotAllowedError' || name === 'SecurityError') this.draft.state('permission');
		else if (name === 'NotFoundError') this.draft.state('missing');
		else this.draft.state('error', e instanceof Error ? e.message : String(e));
	}

	/** Compares [paths], or the whole folder and draft when null, and settles what one side alone changed. */
	private async sync(paths: string[] | null): Promise<void> {
		if (!this.live || this.closed) return;
		const root = this.record.handle;
		let targets: string[];
		let listing: Awaited<ReturnType<typeof listFolder>> | null = null;
		if (paths === null) {
			listing = await listFolder(root);
			targets = [...new Set([...listing.entries.keys(), ...this.draft.paths(), ...Object.keys(this.synced)])];
		} else targets = [...new Set(paths)];
		const conflicts: string[] = [];
		for (const path of targets) {
			if (skipped(path) || this.closed) continue;
			if (listing && !listing.entries.has(path) && listing.tooBig.includes(path)) continue;
			const disk = listing && !listing.entries.has(path) ? null : await readPath(root, path);
			if (disk && disk.size > MAX_FOLDER_FILE_BYTES) continue;
			const draftNow = this.draft.content(path);
			const d = disk ? fingerprint(disk.bytes) : null;
			const r = draftNow !== null ? fingerprint(draftNow) : null;
			const known = this.synced[path];
			if (d === null && r === null) {
				delete this.synced[path];
				continue;
			}
			if (d !== null && r !== null && (d === r || (path === MANIFEST_FILE && sameJson(disk!.bytes, draftNow!)))) {
				this.synced[path] = [d, r];
				continue;
			}
			if (!known) {
				if (d === null) await this.writeDraft(path);
				else if (r === null) this.takeDisk(path, disk!.bytes);
				else conflicts.push(path);
				continue;
			}
			// '' stands for a copy that is not there.
			const diskChanged = (d ?? '') !== known[0];
			const draftChanged = (r ?? '') !== known[1];
			if (!diskChanged && !draftChanged) continue;
			if (diskChanged && !draftChanged) this.takeDisk(path, disk?.bytes ?? null);
			else if (draftChanged && !diskChanged) await this.writeDraft(path);
			else conflicts.push(path);
		}
		this.persist();
		if (conflicts.length && !this.closed) this.draft.conflicts(conflicts);
	}

	private async writeDraft(path: string) {
		const data = this.draft.content(path);
		if (data === null) {
			await removePath(this.record.handle, path);
			delete this.synced[path];
			return;
		}
		await writePath(this.record.handle, path, bytesOf(data));
		const f = fingerprint(data);
		this.synced[path] = [f, f];
	}

	private takeDisk(path: string, bytes: Uint8Array | null) {
		const after = this.draft.take(path, bytes);
		if (after === 'refused') {
			// The draft keeps its copy; remember the folder's so this is not asked again until either changes.
			const r = this.draft.content(path);
			this.synced[path] = [bytes !== null ? fingerprint(bytes) : '', r !== null ? fingerprint(r) : ''];
			return;
		}
		if (bytes === null || after === null) delete this.synced[path];
		else this.synced[path] = [fingerprint(bytes), fingerprint(after)];
	}

	private persist() {
		if (this.persistTimer) clearTimeout(this.persistTimer);
		this.persistTimer = setTimeout(() => {
			this.persistTimer = null;
			void this.persistNow();
		}, PERSIST_MS);
	}

	private persistNow() {
		this.record = { ...this.record, synced: { ...this.synced } };
		if (this.forgotten) return Promise.resolve(false);
		return folderLinks.put(this.draftId, this.record);
	}
}

/** Two plugin.json texts that hold the same JSON. */
function sameJson(disk: Uint8Array, draft: FileData): boolean {
	try {
		return JSON.stringify(sortKeys(JSON.parse(new TextDecoder().decode(disk)))) === JSON.stringify(sortKeys(JSON.parse(typeof draft === 'string' ? draft : new TextDecoder().decode(draft))));
	} catch {
		return false;
	}
}

function sortKeys(v: unknown): unknown {
	if (Array.isArray(v)) return v.map(sortKeys);
	if (v && typeof v === 'object') return Object.fromEntries(Object.keys(v as object).sort().map((k) => [k, sortKeys((v as Record<string, unknown>)[k])]));
	return v;
}

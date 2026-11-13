/**
 * The plugin editor's workspace on the web (PluginWorkspace.kt): drafts, their
 * kept versions and their preview storage, in localStorage. The rules are the
 * app's: 20 drafts, 30 versions of 512 K characters per draft with the periodic
 * ones pruned first, and the same text never kept twice.
 */
export interface PluginManifest {
	format: 'wmkeyboard-plugin';
	id: string;
	name: string;
	pluginVersion: string;
	author: string;
	description: string;
	apiVersion: number;
	/** Optional in a file; the app runs nothing but "python". */
	language?: string;
	entry: string;
	permissions: string[];
	/** API 5: the websites `http` may reach. */
	hosts?: string[];
	/** API 5: the plugin's settings page. */
	settings?: { key: string; type: string; label?: string; summary?: string; default?: string; options?: string[] }[];
	/** API 5: where the plugin shows up (PluginToolSpec). */
	tool?: { icon?: string; tile?: boolean; float?: boolean; keyword?: string; chip?: boolean; height?: number };
	/** Buttons on the bar over selected text. */
	actions?: { id: string; name: string; summary?: string; icon?: string; replaces?: boolean; input?: string }[];
	/** False for a plugin made of selection actions alone. */
	panel?: boolean;
}

export type SnapshotReason = 'manual' | 'periodic' | 'before_restore' | 'publish' | 'import';

export interface PluginSnapshot {
	snapshotId: string;
	at: number;
	reason: SnapshotReason;
	note: string;
	lines: number;
	characters: number;
}

export interface PluginDraft {
	draftId: string;
	createdAt: number;
	updatedAt: number;
	publishedVersion: string;
	origin: string;
}

export const MAX_DRAFTS = 20;
export const MAX_SNAPSHOTS = 30;
export const MAX_SNAPSHOT_CHARACTERS = 512 * 1024;
export const MAX_SCRIPT_BYTES = 256 * 1024;
export const MANIFEST_FORMAT = 'wmkeyboard-plugin';
export const API_VERSION = 4;
export const LANGUAGE = 'python';
export const DEFAULT_ENTRY = 'main.py';
export const STORAGE_PERMISSION = 'storage';
export const REMINDERS_PERMISSION = 'reminders';
/** Every permission the app knows, in the order the manifest form lists them (PluginPermission.all). */
export const PERMISSIONS = [
	STORAGE_PERMISSION, REMINDERS_PERMISSION, 'selection', 'field.write', 'clipboard.write', 'sensors', 'scan', 'device', 'share',
	'notifications', 'network', 'ai', 'field.read', 'clipboard.read', 'location', 'files',
];
/** The ones API 4 knows; the rest need `"apiVersion": 5`. */
export const API4_PERMISSIONS = [STORAGE_PERMISSION, REMINDERS_PERMISSION, 'selection'];
/** Off after install until the user turns them on (PluginPermission.Tier.RISKY). */
export const RISKY_PERMISSIONS = ['network', 'ai', 'field.read', 'clipboard.read', 'location', 'files'];
export const MAX_HOSTS = 16;
export const ID_PATTERN = /^[a-z0-9][a-z0-9._-]{2,63}$/;
export const MAX_NAME = 40;
const MAX_VERSION = 32;
const MAX_AUTHOR = 64;
const MAX_DESCRIPTION = 280;
const MAX_ENTRY = 64;

const KEY = 'wm.ide.drafts';
const keyOf = (draftId: string, what: string) => `wm.ide.${what}.${draftId}`;

interface Stored {
	drafts: PluginDraft[];
	manifests: Record<string, PluginManifest>;
}

export function blankManifest(): PluginManifest {
	return { format: MANIFEST_FORMAT, id: '', name: '', pluginVersion: '', author: '', description: '', apiVersion: API_VERSION, language: LANGUAGE, entry: DEFAULT_ENTRY, permissions: [] };
}

/** `PluginManifestCodec.displayText`: one line, trimmed, cut to `max`. */
function displayText(raw: string, max: number): string {
	return raw.replace(/[\r\n\t]+/g, ' ').replace(/\s+/g, ' ').trim().substring(0, max);
}

export type ManifestField = 'ID' | 'NAME' | 'VERSION' | 'AUTHOR' | 'DESCRIPTION' | 'ENTRY';

export function sanitise(raw: string, field: ManifestField): string {
	switch (field) {
		case 'ID': return raw.trim().toLowerCase();
		case 'NAME': return displayText(raw, MAX_NAME);
		case 'VERSION': return displayText(raw, MAX_VERSION);
		case 'AUTHOR': return displayText(raw, MAX_AUTHOR);
		case 'DESCRIPTION': return displayText(raw, MAX_DESCRIPTION);
		case 'ENTRY': {
			// A draft saved before API 4 names another entry; it becomes main.py.
			const entry = displayText(raw, MAX_ENTRY);
			return entry.endsWith('.py') ? entry : DEFAULT_ENTRY;
		}
	}
}

/** The API level that brought the wm package; a draft written for it keeps it. */
export const PACKAGE_API = 5;

/** The manifest as the importer would keep it: API 4, or API 5 for a draft already written for it. */
export function sanitised(m: PluginManifest): PluginManifest {
	return { ...m, format: MANIFEST_FORMAT, apiVersion: m.apiVersion >= PACKAGE_API ? PACKAGE_API : API_VERSION, language: LANGUAGE, id: sanitise(m.id, 'ID'), name: sanitise(m.name, 'NAME'), pluginVersion: sanitise(m.pluginVersion, 'VERSION'), author: sanitise(m.author, 'AUTHOR'), description: sanitise(m.description, 'DESCRIPTION'), entry: sanitise(m.entry, 'ENTRY'), permissions: m.permissions.slice(0, 16) };
}

function read(): Stored {
	try {
		const raw = localStorage.getItem(KEY);
		if (raw) {
			const parsed = JSON.parse(raw) as Stored;
			if (Array.isArray(parsed.drafts) && parsed.manifests) return parsed;
		}
	} catch {
		/* fresh */
	}
	return { drafts: [], manifests: {} };
}

function write(s: Stored): boolean {
	try {
		localStorage.setItem(KEY, JSON.stringify(s));
		return true;
	} catch {
		return false;
	}
}

function getItem(key: string): string | null {
	try {
		return localStorage.getItem(key);
	} catch {
		return null;
	}
}

function setItem(key: string, value: string): boolean {
	try {
		localStorage.setItem(key, value);
		return true;
	} catch {
		return false;
	}
}

function removeItem(key: string) {
	try {
		localStorage.removeItem(key);
	} catch {
		/* gone anyway */
	}
}

const newId = () => (crypto.randomUUID ? crypto.randomUUID() : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`);

export const workspace = {
	drafts(): PluginDraft[] {
		return [...read().drafts].sort((a, b) => b.updatedAt - a.updatedAt);
	},

	draft(draftId: string): PluginDraft | null {
		return read().drafts.find((d) => d.draftId === draftId) ?? null;
	},

	manifest(draftId: string): PluginManifest | null {
		return read().manifests[draftId] ?? null;
	},

	script(draftId: string): string | null {
		return getItem(keyOf(draftId, 'script'));
	},

	/** A new draft, or null past the cap or when the browser refuses the write. */
	create(manifest: PluginManifest, script: string, origin: string): PluginDraft | null {
		const s = read();
		if (s.drafts.length >= MAX_DRAFTS) return null;
		const now = Date.now();
		const draft: PluginDraft = { draftId: newId(), createdAt: now, updatedAt: now, publishedVersion: '', origin };
		if (!setItem(keyOf(draft.draftId, 'script'), script)) return null;
		s.drafts.push(draft);
		s.manifests[draft.draftId] = sanitised(manifest);
		if (!write(s)) {
			removeItem(keyOf(draft.draftId, 'script'));
			return null;
		}
		return draft;
	},

	writeScript(draftId: string, text: string): boolean {
		const s = read();
		const d = s.drafts.find((x) => x.draftId === draftId);
		if (!d) return false;
		if (!setItem(keyOf(draftId, 'script'), text)) return false;
		d.updatedAt = Date.now();
		return write(s);
	},

	writeManifest(draftId: string, manifest: PluginManifest): boolean {
		const s = read();
		const d = s.drafts.find((x) => x.draftId === draftId);
		if (!d) return false;
		s.manifests[draftId] = sanitised(manifest);
		d.updatedAt = Date.now();
		return write(s);
	},

	markPublished(draftId: string, version: string) {
		const s = read();
		const d = s.drafts.find((x) => x.draftId === draftId);
		if (!d) return;
		d.publishedVersion = version;
		write(s);
	},

	delete(draftId: string) {
		const s = read();
		s.drafts = s.drafts.filter((d) => d.draftId !== draftId);
		delete s.manifests[draftId];
		write(s);
		for (const snap of this.snapshots(draftId)) removeItem(keyOf(draftId, `snapshot.${snap.snapshotId}`));
		removeItem(keyOf(draftId, 'history'));
		removeItem(keyOf(draftId, 'script'));
		removeItem(keyOf(draftId, 'storage'));
	},

	duplicate(draftId: string): PluginDraft | null {
		const manifest = this.manifest(draftId);
		const script = this.script(draftId);
		if (!manifest || script === null) return null;
		return this.create(manifest, script, `copy:${draftId}`);
	},

	// ---- versions ------------------------------------------------------------

	private_history(draftId: string): PluginSnapshot[] {
		try {
			const raw = getItem(keyOf(draftId, 'history'));
			return raw ? (JSON.parse(raw) as PluginSnapshot[]) : [];
		} catch {
			return [];
		}
	},

	/** The draft's kept versions, newest first. */
	snapshots(draftId: string): PluginSnapshot[] {
		return this.private_history(draftId).slice().reverse();
	},

	snapshotBody(draftId: string, snapshotId: string): string | null {
		return getItem(keyOf(draftId, `snapshot.${snapshotId}`));
	},

	/** Keeps the script as a version. Null when it is the same as the newest one. */
	snapshot(draftId: string, reason: SnapshotReason, note = ''): PluginSnapshot | null {
		const text = this.script(draftId);
		if (text === null) return null;
		const history = this.private_history(draftId);
		const newest = history[history.length - 1];
		if (newest && this.snapshotBody(draftId, newest.snapshotId) === text) return null;
		const snap: PluginSnapshot = { snapshotId: newId(), at: Date.now(), reason, note, lines: text.split('\n').length, characters: text.length };
		if (!setItem(keyOf(draftId, `snapshot.${snap.snapshotId}`), text)) return null;
		history.push(snap);
		this.prune(draftId, history);
		setItem(keyOf(draftId, 'history'), JSON.stringify(history));
		return snap;
	},

	prune(draftId: string, history: PluginSnapshot[]) {
		while (history.length > 1 && (history.length > MAX_SNAPSHOTS || history.reduce((n, s) => n + s.characters, 0) > MAX_SNAPSHOT_CHARACTERS)) {
			const older = history.slice(0, -1);
			const victim = older.find((s) => s.reason === 'periodic') ?? older[0]!;
			history.splice(history.indexOf(victim), 1);
			removeItem(keyOf(draftId, `snapshot.${victim.snapshotId}`));
		}
	},

	/** Puts a kept version back as the draft's script, keeping the script it replaces first. */
	restore(draftId: string, snapshotId: string): boolean {
		const body = this.snapshotBody(draftId, snapshotId);
		if (body === null) return false;
		this.snapshot(draftId, 'before_restore');
		return this.writeScript(draftId, body);
	},

	storageKey(draftId: string): string {
		return keyOf(draftId, 'storage');
	},
};

/** `my.` and the name in small letters, digits and dashes, numbered until nothing in `taken` has it. */
export function uniquePluginId(name: string, taken: Set<string>): string {
	const slug = name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').substring(0, 40).replace(/^-+|-+$/g, '') || 'plugin';
	const base = `my.${slug}`;
	if (!taken.has(base)) return base;
	let number = 2;
	while (taken.has(`${base}-${number}`)) number++;
	return `${base}-${number}`;
}

export function takenIds(): Set<string> {
	const s = read();
	return new Set(Object.values(s.manifests).map((m) => m.id));
}

/**
 * `PluginFile.fileName`: the name, or the id, with only letters, digits, spaces,
 * dashes and underscores, and the plugin's version after it (#970), `Demo-1.2.wmplugin`.
 */
export function fileNameOf(manifest: PluginManifest): string {
	const stem = (manifest.name.trim() || manifest.id).replace(/[^\p{L}\p{N} _-]/gu, '').trim() || 'plugin';
	const version = manifest.pluginVersion.replace(/[^\p{L}\p{N} ._-]/gu, '').replace(/^[. ]+|[. ]+$/g, '');
	return version ? `${stem}-${version}.wmplugin` : `${stem}.wmplugin`;
}

/** The plugin a new draft starts as (PluginIdeState.BLANK_PLUGIN_SCRIPT). */
export const BLANK_PLUGIN_SCRIPT = `# A plugin draws a panel in the keyboard. render() says what the panel shows,
# and on_event(e) hears what the user pressed.

count = 0


def on_event(e):
    global count
    if e.type == "click" and e.id == "add":
        count += 1


def render():
    return ui.column(
        ui.label("Pressed " + str(count) + " times", style="title"),
        ui.button("add", "Press me", style="primary"),
    )
`;

/** The code key row's arrangement (CodeKeyPrefs). */
export const keyPrefs = {
	key: 'wm.ide.keys',
	read(): { showRow: boolean; order: string[]; hidden: string[] } {
		try {
			const raw = getItem(this.key);
			if (raw) {
				const p = JSON.parse(raw) as { showRow?: boolean; order?: string[]; hidden?: string[] };
				return { showRow: p.showRow ?? true, order: p.order ?? [], hidden: p.hidden ?? [] };
			}
		} catch {
			/* defaults */
		}
		return { showRow: true, order: [], hidden: [] };
	},
	write(v: { showRow: boolean; order: string[]; hidden: string[] }) {
		setItem(this.key, JSON.stringify(v));
	},
	reset() {
		removeItem(this.key);
	},
};

/** The editor's own settings that survive a reload. */
export const editorPrefs = {
	key: 'wm.ide.editor',
	read(): { textSize: number; wrap: boolean; autoRun: boolean } {
		try {
			const raw = getItem(this.key);
			if (raw) {
				const p = JSON.parse(raw) as Partial<{ textSize: number; wrap: boolean; autoRun: boolean }>;
				return { textSize: p.textSize ?? 14, wrap: p.wrap ?? true, autoRun: p.autoRun ?? true };
			}
		} catch {
			/* defaults */
		}
		return { textSize: 14, wrap: true, autoRun: true };
	},
	write(v: { textSize: number; wrap: boolean; autoRun: boolean }) {
		setItem(this.key, JSON.stringify(v));
	},
};

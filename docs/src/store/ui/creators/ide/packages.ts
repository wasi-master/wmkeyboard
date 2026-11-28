/**
 * Python packages in a draft: what adding the packages of a plan, or taking
 * one out, changes among the draft's files and in its wmpy.lock. No network
 * here; the dialog fetches and checks, this decides the files.
 */
import { LICENSES_DIR, LOCK_FILE, lockText, type Lock, type LockEntry, type PlannedPackage, type WheelContents } from '../../../lib/pypi';
import * as Py from '../../../lib/python';
import type { PipAnalysis } from '../../../lib/python';
import { MANIFEST_FILE, type DraftFiles, type FileData } from './draft-files';

export interface PreparedPackage {
	planned: PlannedPackage;
	contents: WheelContents;
	/** Null when this build has no compatibility check. */
	analysis: PipAnalysis | null;
	/** What the keyboard changed: its stand-in in place of compiled code ([standInContents]), or its patch ([Py.pipPatch]); one line each. */
	patched?: string[];
}

/**
 * [contents] with the keyboard's patch for [key] applied, as `wmpy pip
 * install` applies it, and what it changed. Unchanged when there is no
 * patch or this build cannot apply one.
 */
export function patchPackage(key: string, contents: WheelContents): { contents: WheelContents; notes: string[] } {
	const sources = packageSources(contents);
	const result = Py.pipPatch(key, sources);
	if (!result || result.notes.length === 0) return { contents, notes: [] };
	const modules = { ...contents.modules };
	const encoder = new TextEncoder();
	for (const [path, text] of Object.entries(result.files)) {
		if (sources[path] !== text) modules[path] = encoder.encode(text);
	}
	return { contents: { ...contents, modules }, notes: result.notes };
}

/**
 * What the keyboard's pure-Python stand-in for [planned] puts in a plugin,
 * as a wheel's contents would, and the note saying so; null when this build
 * has no stand-in for it.
 */
export function standInContents(planned: PlannedPackage): { contents: WheelContents; note: string } | null {
	const s = Py.pipStandIn(planned.key, planned.name, planned.version);
	if (!s) return null;
	const encoder = new TextEncoder();
	const modules: Record<string, Uint8Array> = {};
	for (const [path, text] of Object.entries(s.files)) modules[path] = encoder.encode(text);
	return { contents: { modules, licenses: { LICENSE: encoder.encode(s.license) }, roots: [...s.roots].sort(), requiresDist: [], license: 'MIT' }, note: s.note };
}

export type Changes = Record<string, FileData | null>;

const strictUtf8 = new TextDecoder('utf-8', { fatal: true });

/** A module's source, or null when it is not UTF-8. */
function sourceOf(bytes: Uint8Array): string | null {
	try {
		return strictUtf8.decode(bytes);
	} catch {
		return null;
	}
}

/** What a package puts in the plugin, by path: its modules at the top level, its licences under licenses/<name>/. */
export function packageFiles(p: PreparedPackage): Record<string, FileData> {
	const out: Record<string, FileData> = {};
	for (const [path, bytes] of Object.entries(p.contents.modules)) out[path] = sourceOf(bytes) ?? bytes;
	for (const [inner, bytes] of Object.entries(p.contents.licenses)) {
		const path = `${LICENSES_DIR}/${p.planned.key}/${inner.replace(/^licenses\//, '')}`;
		out[path] = sourceOf(bytes) ?? bytes;
	}
	return out;
}

/** The modules of a package as text, for the compatibility check. */
export function packageSources(contents: WheelContents): Record<string, string> {
	const out: Record<string, string> = {};
	for (const [path, bytes] of Object.entries(contents.modules)) {
		const text = sourceOf(bytes);
		if (text !== null) out[path] = text;
	}
	return out;
}

export interface AddResult {
	changes: Changes;
	lock: Lock;
	/** Paths a package would write over a file it does not own; nothing changes then. */
	collisions: string[];
}

/**
 * Adds [prepared] to a draft holding [files] and [lock]. A package already in
 * the lock is replaced: its old files go, unless another package lists them
 * too. A file that is the plugin's own, or another package's, is never
 * written over: the whole add stops and [collisions] names them.
 */
export function addPackages(files: DraftFiles, entry: string, lock: Lock, prepared: PreparedPackage[]): AddResult {
	const replacing = new Set(prepared.map((p) => p.planned.key));
	const ownedByOthers = new Map<string, string>();
	for (const [key, e] of Object.entries(lock.packages)) if (!replacing.has(key)) for (const f of e.files) ownedByOthers.set(f, key);
	const ownedByReplaced = new Set(Object.entries(lock.packages).filter(([k]) => replacing.has(k)).flatMap(([, e]) => e.files));
	const changes: Changes = {};
	const collisions: string[] = [];
	const writing = new Map<string, string>();
	const next: Lock = { version: 1, packages: { ...lock.packages } };
	for (const p of prepared) {
		const old = lock.packages[p.planned.key];
		if (old) for (const f of old.files) if (!ownedByOthers.has(f)) changes[f] = null;
	}
	for (const p of prepared) {
		const written = packageFiles(p);
		for (const path of Object.keys(written)) {
			const taken = path === MANIFEST_FILE || path === entry || path === LOCK_FILE || ownedByOthers.has(path) || (path in files && !ownedByReplaced.has(path)) || (writing.has(path) && writing.get(path) !== p.planned.key);
			if (taken) collisions.push(path);
			writing.set(path, p.planned.key);
		}
		Object.assign(changes, written);
		next.packages[p.planned.key] = lockEntry(p, Object.keys(written));
	}
	if (collisions.length) return { changes: {}, lock, collisions: [...new Set(collisions)].sort() };
	changes[LOCK_FILE] = lockText(next);
	return { changes, lock: next, collisions: [] };
}

export function lockEntry(p: PreparedPackage, files: string[]): LockEntry {
	return {
		version: p.planned.version,
		roots: [...p.contents.roots].sort(),
		files: [...files].sort(),
		source: p.planned.wheel.url,
		sha256: p.planned.wheel.sha256,
		license: p.contents.license ?? p.planned.release.license,
		requires: [...new Set(p.planned.requires)].sort(),
		// With no check in this build the package is taken on trust, and marked so.
		verdict: p.analysis?.verdict ?? 'warnings',
		problems: p.analysis?.problems.length ?? 0,
		...(p.planned.requested ? { requested: p.planned.requested } : {}),
	};
}

/** Takes [key] out: its files go unless another package lists them, and the lock goes when it is empty. */
export function removePackage(lock: Lock, key: string): { changes: Changes; lock: Lock } {
	const entry = lock.packages[key];
	if (!entry) return { changes: {}, lock };
	const keep = new Set(Object.entries(lock.packages).filter(([k]) => k !== key).flatMap(([, e]) => e.files));
	const changes: Changes = {};
	for (const f of entry.files) if (!keep.has(f)) changes[f] = null;
	const packages = { ...lock.packages };
	delete packages[key];
	const next: Lock = { version: 1, packages };
	changes[LOCK_FILE] = Object.keys(packages).length ? lockText(next) : null;
	return { changes, lock: next };
}

/** The packages in [lock] that need [key]. */
export const neededBy = (lock: Lock, key: string) => Object.entries(lock.packages).filter(([k, e]) => k !== key && e.requires.includes(key)).map(([k]) => k).sort();

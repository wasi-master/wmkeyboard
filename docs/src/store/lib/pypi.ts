/**
 * Python packages from PyPI for the web plugin editor: what `wmpy pip` does on
 * a computer, done in the browser. PyPI's JSON API and files.pythonhosted.org
 * both send `Access-Control-Allow-Origin: *`, so the page reads them directly.
 *
 * Only pure wheels go in (`py3-none-any`, `py2.py3-none-any`): plugins run
 * Python source, so a package that ships compiled code, or ships only an
 * sdist that would have to be built, is refused. Dependencies are read from
 * `Requires-Dist` with the environment markers of a generic CPython
 * ([MARKER_ENV]) and no extras unless they are asked for.
 *
 * The rules (PEP 503 names, PEP 440 versions, PEP 508 requirements, wheel
 * tags) follow packaging.python.org's specifications; they are the subset a
 * resolver without backtracking needs.
 */
import { unzipSync } from 'fflate';
import { fetchBytes, fetchText, NetError } from './net';
import { sha256Hex } from './util';

export const PYPI_URL = 'https://pypi.org/pypi';
/** The most one wheel may weigh: a whole plugin unpacks to 4 MB at most. */
export const MAX_WHEEL_BYTES = 16 * 1024 * 1024;
/** The most one Add brings in, the package and every dependency it pulls. */
export const MAX_PLAN_PACKAGES = 30;
export const LOCK_FILE = 'wmpy.lock';
/** Where a package's licence files go in the plugin: `licenses/<name>/<file>`. */
export const LICENSES_DIR = 'licenses';

// ---------------------------------------------------------------------------
// Names (PEP 503)
// ---------------------------------------------------------------------------

/** `Foo.Bar_baz` → `foo-bar-baz`. */
export function normalizeName(name: string): string {
	return name.trim().replace(/[-_.]+/g, '-').toLowerCase();
}

/** A project name PyPI can hold (the core metadata `Name` rule). */
export function isValidName(name: string): boolean {
	return /^([A-Z0-9]|[A-Z0-9][A-Z0-9._-]*[A-Z0-9])$/i.test(name.trim());
}

// ---------------------------------------------------------------------------
// Versions (PEP 440)
// ---------------------------------------------------------------------------

export interface Version {
	epoch: number;
	release: number[];
	pre: [number, number] | null;
	post: number | null;
	dev: number | null;
	local: (number | string)[] | null;
	raw: string;
}

const VERSION = /^v?(?:(\d+)!)?(\d+(?:\.\d+)*)(?:[-_.]?(a|b|c|rc|alpha|beta|pre|preview)[-_.]?(\d+)?)?(?:-(\d+)|[-_.]?(post|rev|r)[-_.]?(\d+)?)?(?:[-_.]?(dev)[-_.]?(\d+)?)?(?:\+([a-z0-9]+(?:[-_.][a-z0-9]+)*))?$/i;
const PRE_RANK: Record<string, number> = { a: 0, alpha: 0, b: 1, beta: 1, c: 2, rc: 2, pre: 2, preview: 2 };

export function parseVersion(text: string): Version | null {
	const m = VERSION.exec(text.trim());
	if (!m) return null;
	return {
		epoch: m[1] ? Number(m[1]) : 0,
		release: m[2]!.split('.').map(Number),
		pre: m[3] ? [PRE_RANK[m[3].toLowerCase()]!, m[4] ? Number(m[4]) : 0] : null,
		post: m[5] !== undefined ? Number(m[5]) : m[6] ? (m[7] ? Number(m[7]) : 0) : null,
		dev: m[8] ? (m[9] ? Number(m[9]) : 0) : null,
		local: m[10] ? m[10].toLowerCase().split(/[-_.]/).map((p) => (/^\d+$/.test(p) ? Number(p) : p)) : null,
		raw: text.trim(),
	};
}

export const isPrerelease = (v: Version) => v.pre !== null || v.dev !== null;

function compareRelease(a: number[], b: number[]): number {
	const n = Math.max(a.length, b.length);
	for (let i = 0; i < n; i++) {
		const d = (a[i] ?? 0) - (b[i] ?? 0);
		if (d) return d < 0 ? -1 : 1;
	}
	return 0;
}

const cmp = (a: number, b: number) => (a < b ? -1 : a > b ? 1 : 0);

/** packaging's ordering: dev before pre before the release before post, local after. */
export function compareVersions(a: Version, b: Version, withLocal = true): number {
	if (a.epoch !== b.epoch) return cmp(a.epoch, b.epoch);
	const r = compareRelease(a.release, b.release);
	if (r) return r;
	// A dev release with no pre or post sorts before every pre-release of it.
	const preKey = (v: Version): [number, number, number] => (v.pre === null && v.post === null && v.dev !== null ? [-1, 0, 0] : v.pre === null ? [1, 0, 0] : [0, v.pre[0], v.pre[1]]);
	const pa = preKey(a);
	const pb = preKey(b);
	for (let i = 0; i < 3; i++) if (pa[i] !== pb[i]) return cmp(pa[i]!, pb[i]!);
	const post = cmp(a.post ?? -1, b.post ?? -1);
	if (post) return post;
	const dev = cmp(a.dev ?? Infinity, b.dev ?? Infinity);
	if (dev) return dev;
	if (!withLocal) return 0;
	if (!a.local || !b.local) return cmp(a.local ? 1 : 0, b.local ? 1 : 0);
	for (let i = 0; i < Math.max(a.local.length, b.local.length); i++) {
		const x = a.local[i];
		const y = b.local[i];
		if (x === undefined || y === undefined) return x === undefined ? -1 : 1;
		if (typeof x !== typeof y) return typeof x === 'number' ? 1 : -1;
		if (x !== y) return x < y ? -1 : 1;
	}
	return 0;
}

export function compareVersionText(a: string, b: string): number {
	const x = parseVersion(a);
	const y = parseVersion(b);
	if (!x || !y) return x ? 1 : y ? -1 : a.localeCompare(b);
	return compareVersions(x, y);
}

// ---------------------------------------------------------------------------
// Version specifiers
// ---------------------------------------------------------------------------

export interface Specifier {
	op: '==' | '!=' | '<' | '<=' | '>' | '>=' | '~=' | '===';
	version: string;
}

/** `>=2.5, <4` or `(>=2.5,<4)`; null when any part is not a specifier. */
export function parseSpecifiers(text: string): Specifier[] | null {
	const t = text.trim().replace(/^\((.*)\)$/s, '$1').trim();
	if (!t) return [];
	const out: Specifier[] = [];
	for (const part of t.split(',')) {
		const p = part.trim();
		if (!p) continue;
		const m = /^(===|==|!=|<=|>=|~=|<|>)\s*([A-Za-z0-9_.*+!-]+)$/.exec(p);
		if (!m) return null;
		out.push({ op: m[1] as Specifier['op'], version: m[2]! });
	}
	return out;
}

export const specifiersText = (specs: Specifier[]) => specs.map((s) => `${s.op}${s.version}`).join(',');

function matchesOne(v: Version, s: Specifier): boolean {
	if (s.op === '===') return v.raw.toLowerCase() === s.version.toLowerCase();
	if ((s.op === '==' || s.op === '!=') && s.version.endsWith('.*')) {
		const prefix = parseVersion(s.version.slice(0, -2));
		if (!prefix) return false;
		const head = v.release.slice(0, prefix.release.length);
		while (head.length < prefix.release.length) head.push(0);
		const same = v.epoch === prefix.epoch && compareRelease(head, prefix.release) === 0;
		return s.op === '==' ? same : !same;
	}
	const target = parseVersion(s.version);
	if (!target) return false;
	switch (s.op) {
		case '==': return compareVersions(v, target, target.local !== null) === 0;
		case '!=': return compareVersions(v, target, target.local !== null) !== 0;
		case '<=': return compareVersions(v, target, false) <= 0;
		case '>=': return compareVersions(v, target, false) >= 0;
		case '<': {
			if (compareVersions(v, target, false) >= 0) return false;
			// <3 leaves out 3.0rc1 unless the bound is a pre-release itself.
			if (!isPrerelease(target) && isPrerelease(v) && compareRelease(v.release, target.release) === 0 && v.epoch === target.epoch) return false;
			return true;
		}
		case '>': {
			if (compareVersions(v, target, false) <= 0) return false;
			// >3 leaves out 3.post1 unless the bound is a post-release itself.
			if (target.post === null && v.post !== null && compareRelease(v.release, target.release) === 0 && v.epoch === target.epoch) return false;
			return true;
		}
		case '~=': {
			if (target.release.length < 2) return false;
			const prefix = target.release.slice(0, -1).join('.');
			return compareVersions(v, target, false) >= 0 && matchesOne(v, { op: '==', version: `${target.epoch ? `${target.epoch}!` : ''}${prefix}.*` });
		}
	}
	return false;
}

/**
 * Whether [version] meets every specifier. Pre-releases count only when
 * [allowPre] is set or a specifier names one, as pip has it.
 */
export function satisfies(version: string, specs: Specifier[], allowPre = false): boolean {
	const v = parseVersion(version);
	if (!v) return specs.length === 0;
	if (isPrerelease(v) && !allowPre && !specs.some((s) => { const t = parseVersion(s.version.replace(/\.\*$/, '')); return t && isPrerelease(t); })) return false;
	return specs.every((s) => matchesOne(v, s));
}

// ---------------------------------------------------------------------------
// Requirements and markers (PEP 508)
// ---------------------------------------------------------------------------

/**
 * The environment markers are evaluated in: a generic CPython on Linux, of
 * the version the plugin interpreter reports ([usePythonVersion] sets it
 * once the wasm has loaded; 3.12 until then). `extra` is true only for the
 * extras asked for.
 */
export const MARKER_ENV: Record<string, string> = {
	python_version: '3.12',
	python_full_version: '3.12.0',
	implementation_name: 'cpython',
	implementation_version: '3.12.0',
	platform_python_implementation: 'CPython',
	os_name: 'posix',
	sys_platform: 'linux',
	platform_system: 'Linux',
	platform_machine: 'aarch64',
	platform_release: '',
	platform_version: '',
};
export let PYTHON_VERSION = MARKER_ENV.python_full_version!;

/** Reads markers, `Requires-Python` and wheel tags as Python [full] (`"3.13.0"`) from now on. */
export function usePythonVersion(full: string): void {
	const m = /^(\d+)\.(\d+)\.(\d+)$/.exec(full);
	if (!m) return;
	MARKER_ENV.python_version = `${m[1]}.${m[2]}`;
	MARKER_ENV.python_full_version = full;
	MARKER_ENV.implementation_version = full;
	PYTHON_VERSION = full;
}
const VERSION_VARS = new Set(['python_version', 'python_full_version', 'implementation_version', 'platform_release']);

export type Marker =
	| { kind: 'and' | 'or'; left: Marker; right: Marker }
	| { kind: 'cmp'; op: string; left: MarkerValue; right: MarkerValue };
type MarkerValue = { variable: string } | { literal: string };

export interface Requirement {
	name: string;
	extras: string[];
	specifiers: Specifier[];
	url: string | null;
	marker: Marker | null;
	raw: string;
}

/** One `Requires-Dist` value, or what someone typed in the name box. Null when it does not parse. */
export function parseRequirement(text: string): Requirement | null {
	const raw = text.trim();
	let rest = raw;
	let marker: Marker | null = null;
	const semi = markerSplit(rest);
	if (semi >= 0) {
		marker = parseMarker(rest.slice(semi + 1));
		if (!marker) return null;
		rest = rest.slice(0, semi).trim();
	}
	const m = /^([A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?)\s*(\[[^\]]*\])?\s*(.*)$/s.exec(rest);
	if (!m) return null;
	const extras = m[2] ? m[2].slice(1, -1).split(',').map((e) => e.trim()).filter(Boolean) : [];
	if (extras.some((e) => !isValidName(e))) return null;
	let tail = m[3]!.trim();
	let url: string | null = null;
	if (tail.startsWith('@')) {
		url = tail.slice(1).trim();
		tail = '';
	}
	const specifiers = parseSpecifiers(tail);
	if (!specifiers) return null;
	return { name: m[1]!, extras, specifiers, url, marker, raw };
}

/** Where the marker starts: the first `;` outside quotes. */
function markerSplit(text: string): number {
	let quote: string | null = null;
	for (let i = 0; i < text.length; i++) {
		const c = text[i]!;
		if (quote) {
			if (c === quote) quote = null;
		} else if (c === '"' || c === "'") quote = c;
		else if (c === ';') return i;
	}
	return -1;
}

const MARKER_TOKEN = /\s*(?:(\()|(\))|("[^"]*"|'[^']*')|(===|==|!=|<=|>=|~=|<|>)|(not\s+in\b)|(in\b)|(and\b)|(or\b)|([A-Za-z_][A-Za-z0-9_.]*))/y;

export function parseMarker(text: string): Marker | null {
	const tokens: { t: string; v: string }[] = [];
	MARKER_TOKEN.lastIndex = 0;
	let at = 0;
	const src = text.trim();
	while (at < src.length) {
		MARKER_TOKEN.lastIndex = at;
		const m = MARKER_TOKEN.exec(src);
		if (!m || m[0].length === 0) return null;
		at = MARKER_TOKEN.lastIndex;
		if (m[1]) tokens.push({ t: '(', v: '(' });
		else if (m[2]) tokens.push({ t: ')', v: ')' });
		else if (m[3]) tokens.push({ t: 'str', v: m[3].slice(1, -1) });
		else if (m[4]) tokens.push({ t: 'op', v: m[4] });
		else if (m[5]) tokens.push({ t: 'op', v: 'not in' });
		else if (m[6]) tokens.push({ t: 'op', v: 'in' });
		else if (m[7]) tokens.push({ t: 'and', v: 'and' });
		else if (m[8]) tokens.push({ t: 'or', v: 'or' });
		else if (m[9]) tokens.push({ t: 'var', v: m[9] });
		while (at < src.length && /\s/.test(src[at]!)) at++;
	}
	let i = 0;
	const value = (): MarkerValue | null => {
		const tok = tokens[i];
		if (!tok) return null;
		if (tok.t === 'str') { i++; return { literal: tok.v }; }
		if (tok.t === 'var') { i++; return { variable: tok.v.replace(/^os\.name$/, 'os_name').replace(/^sys\.platform$/, 'sys_platform').replace(/^platform\.(\w+)$/, 'platform_$1').replace(/^python_implementation$/, 'platform_python_implementation') }; }
		return null;
	};
	const expr = (): Marker | null => {
		if (tokens[i]?.t === '(') {
			i++;
			const inner = or();
			if (!inner || tokens[i]?.t !== ')') return null;
			i++;
			return inner;
		}
		const left = value();
		const op = tokens[i];
		if (!left || op?.t !== 'op') return null;
		i++;
		const right = value();
		if (!right) return null;
		return { kind: 'cmp', op: op.v, left, right };
	};
	const and = (): Marker | null => {
		let left = expr();
		while (left && tokens[i]?.t === 'and') {
			i++;
			const right = expr();
			if (!right) return null;
			left = { kind: 'and', left, right };
		}
		return left;
	};
	const or = (): Marker | null => {
		let left = and();
		while (left && tokens[i]?.t === 'or') {
			i++;
			const right = and();
			if (!right) return null;
			left = { kind: 'or', left, right };
		}
		return left;
	};
	const out = or();
	return out && i === tokens.length ? out : null;
}

/** Whether [marker] holds in [env] with [extras] asked for. */
export function evaluateMarker(marker: Marker, extras: string[] = [], env: Readonly<Record<string, string>> = MARKER_ENV): boolean {
	if (marker.kind !== 'cmp') {
		return marker.kind === 'and'
			? evaluateMarker(marker.left, extras, env) && evaluateMarker(marker.right, extras, env)
			: evaluateMarker(marker.left, extras, env) || evaluateMarker(marker.right, extras, env);
	}
	const names = (v: MarkerValue) => ('variable' in v ? v.variable : null);
	const usesExtra = names(marker.left) === 'extra' || names(marker.right) === 'extra';
	if (usesExtra) {
		const asked = extras.length ? extras.map(normalizeName) : [''];
		return asked.some((extra) => compareMarker(marker.op, resolveValue(marker.left, env, extra), resolveValue(marker.right, env, extra), true, false));
	}
	const versionish = [marker.left, marker.right].some((v) => 'variable' in v && VERSION_VARS.has(v.variable));
	return compareMarker(marker.op, resolveValue(marker.left, env, ''), resolveValue(marker.right, env, ''), false, versionish);
}

function resolveValue(v: MarkerValue, env: Readonly<Record<string, string>>, extra: string): string {
	if ('literal' in v) return v.literal;
	if (v.variable === 'extra') return extra;
	return env[v.variable] ?? '';
}

function compareMarker(op: string, left: string, right: string, extra: boolean, versionish: boolean): boolean {
	if (extra) {
		const l = normalizeName(left);
		const r = normalizeName(right);
		if (op === '==' || op === '===') return l === r;
		if (op === '!=') return l !== r;
		if (op === 'in') return r.includes(l);
		if (op === 'not in') return !r.includes(l);
		return false;
	}
	if (op === 'in') return right.includes(left);
	if (op === 'not in') return !right.includes(left);
	if (op === '===') return left === right;
	// A version variable against a version literal: compare as versions.
	if (versionish && parseVersion(left) && parseVersion(right.replace(/\.\*$/, ''))) {
		return satisfies(left, [{ op: op as Specifier['op'], version: right }], true);
	}
	switch (op) {
		case '==': return left === right;
		case '!=': return left !== right;
		case '<': return left < right;
		case '<=': return left <= right;
		case '>': return left > right;
		case '>=': return left >= right;
		default: return false;
	}
}

/** Whether a requirement applies here: no marker, or one that holds. */
export function applies(req: Requirement, extras: string[] = []): boolean {
	return !req.marker || evaluateMarker(req.marker, extras);
}

// ---------------------------------------------------------------------------
// Wheels
// ---------------------------------------------------------------------------

export interface WheelName {
	name: string;
	version: string;
	pythons: string[];
	abis: string[];
	platforms: string[];
}

/** `{name}-{version}(-{build})?-{python}-{abi}-{platform}.whl`. */
export function parseWheelName(filename: string): WheelName | null {
	if (!filename.endsWith('.whl')) return null;
	const parts = filename.slice(0, -4).split('-');
	if (parts.length !== 5 && parts.length !== 6) return null;
	const [name, version] = parts;
	const [python, abi, platform] = parts.slice(-3);
	return { name: name!, version: version!, pythons: python!.split('.'), abis: abi!.split('.'), platforms: platform!.split('.') };
}

/**
 * A wheel the plugin interpreter can take: no compiled code (`none`, `any`)
 * and Python 3, up to the interpreter's minor version when one is named.
 */
export function isPureWheel(filename: string): boolean {
	const w = parseWheelName(filename);
	if (!w || !w.abis.includes('none') || !w.platforms.includes('any')) return false;
	const minor = Number(MARKER_ENV.python_version!.split('.')[1]);
	return w.pythons.some((p) => {
		const m = /^py3(\d*)$/.exec(p);
		return !!m && (m[1] === '' || Number(m[1]) <= minor);
	});
}

// ---------------------------------------------------------------------------
// PyPI's JSON API
// ---------------------------------------------------------------------------

export interface PypiFile {
	filename: string;
	url: string;
	packagetype: string;
	sha256: string;
	size: number;
	yanked: boolean;
	requiresPython: string | null;
}

export interface ProjectVersion {
	version: string;
	/** Has a wheel the interpreter can take. */
	pure: boolean;
	/** Has files, but only an sdist. */
	sdistOnly: boolean;
	yanked: boolean;
	pre: boolean;
}

export interface Project {
	name: string;
	latest: string;
	summary: string;
	/** Newest first. */
	versions: ProjectVersion[];
}

export interface Release {
	name: string;
	version: string;
	summary: string;
	requiresDist: string[];
	requiresPython: string | null;
	license: string | null;
	files: PypiFile[];
}

type Json = Record<string, unknown>;

function fileOf(raw: Json): PypiFile {
	const digests = (raw.digests ?? {}) as Json;
	return {
		filename: String(raw.filename ?? ''),
		url: String(raw.url ?? ''),
		packagetype: String(raw.packagetype ?? ''),
		sha256: String(digests.sha256 ?? '').toLowerCase(),
		size: Number(raw.size ?? 0),
		yanked: raw.yanked === true,
		requiresPython: typeof raw.requires_python === 'string' && raw.requires_python.trim() ? raw.requires_python : null,
	};
}

export class PypiError extends Error {
	constructor(public kind: 'not-found' | 'network' | 'sdist-only' | 'compiled' | 'no-version' | 'conflict' | 'too-many' | 'bad-wheel' | 'hash' | 'python', message: string) {
		super(message);
	}
}

async function getJson(url: string, signal?: AbortSignal): Promise<Json> {
	try {
		return JSON.parse(await fetchText(url, { maxBytes: 32 * 1024 * 1024, signal, accept: 'application/json' })) as Json;
	} catch (e) {
		if (e instanceof NetError && e.status === 404) throw new PypiError('not-found', 'not found');
		if (e instanceof NetError && e.kind === 'aborted') throw e;
		throw new PypiError('network', e instanceof Error ? e.message : String(e));
	}
}

const pythonFits = (requiresPython: string | null) => {
	if (!requiresPython) return true;
	const specs = parseSpecifiers(requiresPython);
	return !specs || satisfies(PYTHON_VERSION, specs, true);
};

/** `https://pypi.org/pypi/<name>/json`: every version, newest first, marked by what it ships. */
export async function fetchProject(name: string, signal?: AbortSignal): Promise<Project> {
	const raw = await getJson(`${PYPI_URL}/${encodeURIComponent(normalizeName(name))}/json`, signal);
	const info = (raw.info ?? {}) as Json;
	const releases = (raw.releases ?? {}) as Record<string, Json[]>;
	const versions: ProjectVersion[] = Object.entries(releases)
		.filter(([, files]) => Array.isArray(files) && files.length > 0)
		.map(([version, files]) => {
			const list = files.map(fileOf);
			const live = list.filter((f) => !f.yanked);
			return {
				version,
				pure: live.some((f) => f.packagetype === 'bdist_wheel' && isPureWheel(f.filename) && pythonFits(f.requiresPython)),
				sdistOnly: live.length > 0 && live.every((f) => f.packagetype === 'sdist'),
				yanked: live.length === 0,
				pre: (() => { const v = parseVersion(version); return !v || isPrerelease(v); })(),
			};
		})
		.sort((a, b) => compareVersionText(b.version, a.version));
	return { name: String(info.name ?? name), latest: String(info.version ?? versions[0]?.version ?? ''), summary: String(info.summary ?? ''), versions };
}

/** `https://pypi.org/pypi/<name>/<version>/json`: that release's metadata and files. */
export async function fetchRelease(name: string, version: string, signal?: AbortSignal): Promise<Release> {
	const raw = await getJson(`${PYPI_URL}/${encodeURIComponent(normalizeName(name))}/${encodeURIComponent(version)}/json`, signal);
	const info = (raw.info ?? {}) as Json;
	const classifiers = Array.isArray(info.classifiers) ? info.classifiers.map(String) : [];
	return {
		name: String(info.name ?? name),
		version: String(info.version ?? version),
		summary: String(info.summary ?? ''),
		requiresDist: Array.isArray(info.requires_dist) ? info.requires_dist.map(String) : [],
		requiresPython: typeof info.requires_python === 'string' && info.requires_python.trim() ? info.requires_python : null,
		license: licenseOf(stringOrNull(info.license_expression), stringOrNull(info.license), classifiers),
		files: (Array.isArray(raw.urls) ? (raw.urls as Json[]) : []).map(fileOf),
	};
}

const stringOrNull = (v: unknown) => (typeof v === 'string' && v.trim() ? v.trim() : null);

/** `License-Expression`, else a one-line `License`, else the first licence classifier. */
export function licenseOf(expression: string | null, license: string | null, classifiers: string[]): string | null {
	if (expression) return expression;
	if (license && !license.includes('\n') && license.length <= 100) return license;
	const c = classifiers.find((x) => x.startsWith('License ::') && x !== 'License :: OSI Approved');
	return c ? c.split('::').pop()!.trim() : null;
}

/** The wheel of [release] the interpreter takes, or why there is none. */
export function pickWheel(release: Release): PypiFile {
	const live = release.files.filter((f) => !f.yanked);
	const wheels = live.filter((f) => f.packagetype === 'bdist_wheel' && isPureWheel(f.filename));
	const fits = wheels.filter((f) => pythonFits(f.requiresPython));
	// py3-none-any before py2.py3-none-any: either runs, the first is the newer build.
	const best = fits.sort((a, b) => Number(b.filename.includes('-py3-')) - Number(a.filename.includes('-py3-')))[0];
	if (best) return best;
	if (wheels.length) throw new PypiError('python', release.name);
	if (live.length && live.every((f) => f.packagetype === 'sdist')) throw new PypiError('sdist-only', release.name);
	throw new PypiError('compiled', release.name);
}

// ---------------------------------------------------------------------------
// What a wheel holds
// ---------------------------------------------------------------------------

export interface WheelContents {
	/** The importable `.py` files, at the paths they import from. */
	modules: Record<string, Uint8Array>;
	/** Licence files, by their path inside the dist-info (`LICENSE`, `licenses/NOTICE`). */
	licenses: Record<string, Uint8Array>;
	/** Top-level module and package names. */
	roots: string[];
	requiresDist: string[];
	license: string | null;
	/**
	 * Other files inside its packages (JSON schemas, word lists), by path:
	 * they go into the plugin inside generated modules ([Py.pipDataModules]).
	 */
	data?: Record<string, Uint8Array>;
}

/** Files a package ships that are not data for it to read: compiled code, its sources, typing stubs. */
const NOT_DATA = /\.(pyc|pyi|so|pyd|dylib|dll|c|cc|cpp|cxx|h|pyx|pxd|rs|go)$|(^|\/)py\.typed$|\.so\./i;

const LICENSE_NAME = /^(licen[cs]e|copying|notice|authors)/i;

/** Unpacks a wheel: its modules where an installer would put them, and its licences. */
export function readWheel(bytes: Uint8Array): WheelContents {
	let entries: Record<string, Uint8Array>;
	try {
		entries = unzipSync(bytes);
	} catch {
		throw new PypiError('bad-wheel', 'not a zip');
	}
	const names = Object.keys(entries);
	const distInfo = names.map((n) => /^([^/]+\.dist-info)\/METADATA$/.exec(n)?.[1]).find(Boolean);
	if (!distInfo) throw new PypiError('bad-wheel', 'no dist-info');
	const meta = parseMetadata(new TextDecoder().decode(entries[`${distInfo}/METADATA`]!));
	const modules: Record<string, Uint8Array> = {};
	const licenses: Record<string, Uint8Array> = {};
	const dataFiles: Record<string, Uint8Array> = {};
	for (const [name, data] of Object.entries(entries)) {
		if (name.endsWith('/') || name.split('/').some((p) => p === '..' || p === '') || name.startsWith('/')) continue;
		if (name.startsWith(`${distInfo}/`)) {
			const inner = name.slice(distInfo.length + 1);
			if (inner.startsWith('licenses/') || (!inner.includes('/') && LICENSE_NAME.test(inner))) licenses[inner] = data;
			continue;
		}
		let path = name;
		const data_ = /^[^/]+\.data\/(purelib|platlib)\/(.+)$/.exec(name);
		if (data_) path = data_[2]!;
		else if (/^[^/]+\.data\//.test(name)) continue;
		const parts = path.split('/');
		if (parts.includes('__pycache__')) continue;
		if (!path.endsWith('.py')) {
			// A package's own data, not its tests' or its build's.
			if (parts.length > 1 && !NOT_DATA.test(path) && !parts.slice(1, -1).some((p) => p === 'tests' || p === 'test')) dataFiles[path] = data;
			continue;
		}
		modules[path] = data;
	}
	const roots = [...new Set(Object.keys(modules).map((p) => (p.includes('/') ? p.split('/')[0]! : p.replace(/\.py$/, ''))))].sort();
	return { modules, licenses, roots, requiresDist: meta.requiresDist, license: licenseOf(meta.licenseExpression, meta.license, meta.classifiers), data: dataFiles };
}

/** The headers of a core metadata file that matter here. */
export function parseMetadata(text: string): { requiresDist: string[]; license: string | null; licenseExpression: string | null; classifiers: string[] } {
	const head = text.replace(/\r\n?/g, '\n').split('\n\n')[0]!;
	const fields: [string, string][] = [];
	for (const line of head.split('\n')) {
		if (/^[ \t]/.test(line) && fields.length) fields[fields.length - 1]![1] += '\n' + line.trim();
		else {
			const colon = line.indexOf(':');
			if (colon > 0) fields.push([line.slice(0, colon).trim().toLowerCase(), line.slice(colon + 1).trim()]);
		}
	}
	const all = (key: string) => fields.filter(([k]) => k === key).map(([, v]) => v);
	return {
		requiresDist: all('requires-dist'),
		license: all('license')[0] ?? null,
		licenseExpression: all('license-expression')[0] ?? null,
		classifiers: all('classifier'),
	};
}

/** Downloads [file] and checks it against the sha256 PyPI lists. */
export async function downloadWheel(file: PypiFile, signal?: AbortSignal): Promise<Uint8Array> {
	let bytes: Uint8Array;
	try {
		bytes = await fetchBytes(file.url, { maxBytes: MAX_WHEEL_BYTES, signal });
	} catch (e) {
		if (e instanceof NetError && e.kind === 'aborted') throw e;
		throw new PypiError('network', e instanceof Error ? e.message : String(e));
	}
	const sha = await sha256Hex(bytes);
	if (!file.sha256 || sha !== file.sha256) throw new PypiError('hash', file.filename);
	return bytes;
}

// ---------------------------------------------------------------------------
// wmpy.lock
// ---------------------------------------------------------------------------

export type LockVerdict = 'ok' | 'warnings' | 'incompatible';

/** One vendored package, as `wmpy pip` writes it. */
export interface LockEntry {
	version: string;
	roots: string[];
	/** Every file it put in the plugin: modules and licence files. */
	files: string[];
	source: string;
	sha256: string;
	license: string | null;
	/** The packages it needs, by normalized name. */
	requires: string[];
	verdict: LockVerdict;
	problems: number;
	/** What was typed to install it, such as `python-dateutil>=2.8,<3`; absent for a package only another one needs. `update` stays inside it. */
	requested?: string;
	/** The SHA-256 of each file in `files`, as written, for `wmpy pip verify`. */
	hashes?: Record<string, string>;
}

export interface Lock {
	version: 1;
	packages: Record<string, LockEntry>;
}

export const emptyLock = (): Lock => ({ version: 1, packages: {} });

/** The lock in [text], or an empty one when there is none; null when it is not a lock. */
export function parseLock(text: string | null | undefined): Lock | null {
	if (text === null || text === undefined || !text.trim()) return emptyLock();
	try {
		const raw = JSON.parse(text) as { version?: unknown; packages?: unknown };
		if (raw.version !== 1 || !raw.packages || typeof raw.packages !== 'object') return null;
		const packages: Record<string, LockEntry> = {};
		for (const [name, e] of Object.entries(raw.packages as Record<string, Partial<LockEntry>>)) {
			const list = (v: unknown) => (Array.isArray(v) ? v.map(String) : []);
			packages[name] = {
				version: String(e.version ?? ''),
				roots: list(e.roots),
				files: list(e.files),
				source: String(e.source ?? ''),
				sha256: String(e.sha256 ?? ''),
				license: typeof e.license === 'string' ? e.license : null,
				requires: list(e.requires),
				verdict: e.verdict === 'ok' || e.verdict === 'incompatible' ? e.verdict : 'warnings',
				problems: Number(e.problems ?? 0) || 0,
			};
			if (typeof e.requested === 'string' && e.requested) packages[name]!.requested = e.requested;
			if (e.hashes && typeof e.hashes === 'object' && !Array.isArray(e.hashes)) {
				const hashes = Object.fromEntries(Object.entries(e.hashes).filter((kv): kv is [string, string] => typeof kv[1] === 'string'));
				if (Object.keys(hashes).length) packages[name]!.hashes = hashes;
			}
		}
		return { version: 1, packages };
	} catch {
		return null;
	}
}

/** The lock as the CLI writes it: keys in a fixed order, packages by name, two-space indent. */
export function lockText(lock: Lock): string {
	const packages: Record<string, LockEntry> = {};
	for (const name of Object.keys(lock.packages).sort()) {
		const e = lock.packages[name]!;
		const out: LockEntry = { version: e.version, roots: e.roots, files: e.files, source: e.source, sha256: e.sha256, license: e.license, requires: e.requires, verdict: e.verdict, problems: e.problems };
		if (e.requested) out.requested = e.requested;
		if (e.hashes && Object.keys(e.hashes).length) out.hashes = Object.fromEntries(Object.keys(e.hashes).sort().map((f) => [f, e.hashes![f]!]));
		packages[name] = out;
	}
	return JSON.stringify({ version: 1, packages }, null, 2) + '\n';
}

/** Top-level names every vendored package owns. */
export const lockRoots = (lock: Lock) => [...new Set(Object.values(lock.packages).flatMap((p) => p.roots))].sort();

// ---------------------------------------------------------------------------
// Resolving one Add into the packages it brings
// ---------------------------------------------------------------------------

export interface PlannedPackage {
	/** Normalized. */
	key: string;
	name: string;
	version: string;
	release: Release;
	wheel: PypiFile;
	extras: string[];
	/** Normalized names of the packages it needs that apply here. */
	requires: string[];
	/** Who asked for it: null for the package typed in. */
	requiredBy: string | null;
	/** What was typed, for the package typed in; what the lock kept, for one already there. */
	requested: string | null;
	/** It replaces this version already in the lock. */
	replaces: string | null;
}

export interface Plan {
	packages: PlannedPackage[];
	/** Requirements already met by the lock, as `name version`. */
	satisfied: string[];
}

export interface ResolveHooks {
	project: (name: string) => Promise<Project>;
	release: (name: string, version: string) => Promise<Release>;
	/**
	 * Where the keyboard's pure-Python stand-in for [key] comes from, when it
	 * keeps one: a release with no pure wheel is then planned with the
	 * stand-in ([STAND_IN]) in its place, as `wmpy pip install` does.
	 */
	standIn?: (key: string) => string | null;
}

/** The `packagetype` of the file planned for a package the keyboard's stand-in replaces. */
export const STAND_IN = 'stand-in';

/** The file planned for [key] when its stand-in ([source]) goes in: nothing to download. */
export function standInFile(key: string, source: string): PypiFile {
	return { filename: `${key} (stand-in)`, url: source, packagetype: STAND_IN, sha256: '', size: 0, yanked: false, requiresPython: null };
}

/**
 * Which packages, at which versions, adding [request] brings, newest that
 * fits first and without backtracking, as pip's resolver would pick when
 * nothing conflicts. A dependency the lock already meets is left as it is.
 */
export async function resolvePlan(request: Requirement, lock: Lock, hooks: ResolveHooks, pinned?: string): Promise<Plan> {
	const chosen = new Map<string, PlannedPackage>();
	const constraints = new Map<string, Specifier[]>();
	const satisfied = new Set<string>();
	const queue: { req: Requirement; by: string | null }[] = [{ req: pinned ? { ...request, specifiers: [{ op: '==', version: pinned }] } : request, by: null }];
	while (queue.length) {
		const { req, by } = queue.shift()!;
		const key = normalizeName(req.name);
		const specs = [...(constraints.get(key) ?? []), ...req.specifiers];
		constraints.set(key, specs);
		const held = lock.packages[key];
		const top = by === null;
		if (!top && held && !chosen.has(key) && satisfies(held.version, specs, true)) {
			satisfied.add(`${key} ${held.version}`);
			continue;
		}
		const already = chosen.get(key);
		if (already) {
			if (!satisfies(already.version, specs, true)) throw new PypiError('conflict', `${key}|${specifiersText(specs)}|${already.version}`);
			const added = req.extras.filter((e) => !already.extras.includes(e));
			if (added.length) {
				already.extras.push(...added);
				for (const dep of dependencies(already.release, added, true)) {
					already.requires.push(normalizeName(dep.name));
					queue.push({ req: dep, by: key });
				}
			}
			continue;
		}
		if (chosen.size >= MAX_PLAN_PACKAGES) throw new PypiError('too-many', String(MAX_PLAN_PACKAGES));
		const project = await hooks.project(req.name);
		const anyPre = specs.some((s) => { const t = parseVersion(s.version.replace(/\.\*$/, '')); return t && isPrerelease(t); });
		const fitting = project.versions.filter((v) => !v.yanked && satisfies(v.version, specs, anyPre));
		const standIn = hooks.standIn?.(key) ?? null;
		// With a stand-in, the newest release goes in whatever it ships.
		const takes = (v: ProjectVersion) => v.pure || standIn !== null;
		const usable = fitting.filter(takes);
		// Only pre-releases fit: take one, as pip does when nothing final is left.
		const pick = usable.find((v) => !v.pre) ?? usable[0] ?? (fitting.length === 0 ? project.versions.filter((v) => !v.yanked && takes(v) && satisfies(v.version, specs, true))[0] : undefined);
		if (!pick) {
			if (!fitting.length) throw new PypiError('no-version', `${project.name}|${specifiersText(specs)}`);
			throw new PypiError(fitting.every((v) => v.sdistOnly) ? 'sdist-only' : 'compiled', project.name);
		}
		const release = await hooks.release(project.name, pick.version);
		const replaced = !pick.pure && standIn !== null;
		const wheel = replaced ? standInFile(key, standIn) : pickWheel(release);
		// A stand-in needs nothing beside it.
		const deps = replaced ? [] : dependencies(release, req.extras, false);
		const planned: PlannedPackage = {
			key, name: release.name, version: release.version, release, wheel, extras: [...req.extras],
			requires: [...new Set(deps.map((d) => normalizeName(d.name)))], requiredBy: by, replaces: held && held.version !== release.version ? held.version : null,
			requested: top ? request.raw.trim() || request.name : (held?.requested ?? null),
		};
		if (held && held.version === release.version && !top) {
			satisfied.add(`${key} ${held.version}`);
			continue;
		}
		chosen.set(key, planned);
		for (const dep of deps) queue.push({ req: dep, by: key });
	}
	return { packages: [...chosen.values()], satisfied: [...satisfied].sort() };
}

/**
 * What [release] needs here: requirements with no marker or one that holds,
 * with [extras] asked for. [onlyExtras] keeps just the ones the extras add.
 */
export function dependencies(release: Release, extras: string[], onlyExtras: boolean): Requirement[] {
	const out: Requirement[] = [];
	for (const text of release.requiresDist) {
		const req = parseRequirement(text);
		if (!req || req.url) continue;
		const base = applies(req, []);
		const withExtras = extras.length > 0 && applies(req, extras);
		if (onlyExtras ? withExtras && !base : base || withExtras) out.push(req);
	}
	return out;
}

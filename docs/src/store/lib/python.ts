/**
 * The plugin language on the web: `native/wm-python-wasm`, the same Rust
 * interpreter and editor tooling the app runs through JNI, built to
 * `../wasm/` by that crate's build.sh. This module loads it once and gives
 * the editor typed answers; `plugin-runtime.ts` runs plugins on it.
 *
 * Every offset is a UTF-16 code unit, which is what JS strings index by.
 * Nothing here works until `ready()` has resolved.
 */
import init, * as wasm from '../wasm/wm_python.js';
import wasmUrl from '../wasm/wm_python_bg.wasm?url';

let loading: Promise<void> | null = null;
let loaded = false;

/** Loads the module; every later call returns the same promise. */
export function ready(): Promise<void> {
	if (!loading) {
		loading = init({ module_or_path: wasmUrl }).then(() => {
			loaded = true;
		});
		loading.catch(() => {
			loading = null;
		});
	}
	return loading;
}

export const isReady = () => loaded;

export const PySession = wasm.PySession;
export type PySession = wasm.PySession;

// ---------------------------------------------------------------------------
// Tokens (highlight.rs)
// ---------------------------------------------------------------------------

export const PyTokenKind = {
	WHITESPACE: 0,
	NEWLINE: 1,
	COMMENT: 2,
	STRING: 3,
	FSTRING: 4,
	NUMBER: 5,
	NAME: 6,
	KEYWORD: 7,
	SOFT_KEYWORD: 8,
	OPERATOR: 9,
	UNKNOWN: 10,
} as const;

/** How a name is coloured: what it is, read from the tokens alone. */
export const PyNameStyle = {
	PLAIN: 0,
	BUILTIN: 1,
	API: 2,
	FUNCTION: 3,
	PARAMETER: 4,
	CONSTANT: 5,
	ATTRIBUTE: 6,
} as const;

/** The lexer's tokens over one source, packed as `word, start, end` triples. */
export class PyTokens {
	constructor(readonly source: string, private readonly packed: Int32Array) {}

	get size(): number {
		return this.packed.length / 3;
	}

	kind(i: number): number {
		return this.packed[i * 3]! & 0xff;
	}

	unterminated(i: number): boolean {
		return (this.packed[i * 3]! & 0x100) !== 0;
	}

	nameStyle(i: number): number {
		return (this.packed[i * 3]! >> 12) & 0xf;
	}

	start(i: number): number {
		return this.packed[i * 3 + 1]!;
	}

	end(i: number): number {
		return this.packed[i * 3 + 2]!;
	}

	text(i: number): string {
		return this.source.substring(this.start(i), this.end(i));
	}

	isCode(i: number): boolean {
		const k = this.kind(i);
		return k !== PyTokenKind.WHITESPACE && k !== PyTokenKind.NEWLINE && k !== PyTokenKind.COMMENT;
	}
}

export function lex(source: string): PyTokens {
	return new PyTokens(source, loaded ? wasm.tokens(source) : new Int32Array(0));
}

// ---------------------------------------------------------------------------
// Checks, outline and folds (api_json.rs: analyse)
// ---------------------------------------------------------------------------

export type PySeverity = 'ERROR' | 'WARNING' | 'INFO';

/** What the checks know about the plugin beyond its code; every field optional. */
export interface PyHost {
	storage?: boolean;
	reminders?: boolean;
	selection?: boolean;
	panel?: boolean;
	actions?: string[];
	/** The whole plugin.json: its permissions, hosts, providers, settings and keyword feed the API 5 checks and suggestions. */
	manifest?: object;
}

/** What one tap does about a diagnostic: a change to the script, or to plugin.json. */
export type PyFix =
	| { kind: 'replace'; text: string }
	| { kind: 'insert'; at: number; text: string }
	| { kind: 'permission' | 'host' | 'action' | 'keyword' | 'setting'; value: string }
	| { kind: 'provider'; tool: string; id: string };

export interface PyDiagnostic {
	code: string;
	severity: PySeverity;
	start: number;
	end: number;
	args: string[];
	fix?: PyFix | null;
}

export interface PyOutlineItem {
	name: string;
	kind: 'function' | 'variable';
	depth: number;
	start: number;
	end: number;
	nameStart: number;
	nameEnd: number;
}

export interface PyAnalysis {
	diagnostics: PyDiagnostic[];
	outline: PyOutlineItem[];
	folds: Range[];
}

export interface Range {
	start: number;
	end: number;
}

export interface PyVerdict {
	message: string;
	kind: string;
	line: number | null;
}

/** Why the interpreter would refuse to load `source`, or null when it parses. */
export function check(source: string): PyVerdict | null {
	if (!loaded) return null;
	const v = wasm.check(source);
	return v ? (JSON.parse(v) as PyVerdict) : null;
}

const EMPTY_ANALYSIS: PyAnalysis = { diagnostics: [], outline: [], folds: [] };

export function analyse(source: string, host: PyHost | null): PyAnalysis {
	if (!loaded) return EMPTY_ANALYSIS;
	const shape = host ? { ...host, verdict: check(source) } : {};
	const raw = JSON.parse(wasm.analyse(source, JSON.stringify(shape))) as {
		diagnostics: { code: string; severity: string; start: number; end: number; args: string[]; fix: PyFix | null }[];
		outline: PyOutlineItem[];
		folds: [number, number][];
	};
	return {
		diagnostics: raw.diagnostics.map((d) => ({ ...d, severity: d.severity.toUpperCase() as PySeverity })),
		outline: raw.outline,
		folds: raw.folds.map(([start, end]) => ({ start, end })),
	};
}

/** The fold regions of `source`, in document order. */
export function folds(source: string): Range[] {
	if (!loaded) return [];
	return (JSON.parse(wasm.analyse(source, '{}')) as { folds: [number, number][] }).folds.map(([start, end]) => ({ start, end }));
}

let messages: Map<string, string> | null = null;

/** A diagnostic's English words, with its arguments in `{0}` and `{1}`. */
export function diagnosticText(d: PyDiagnostic): string {
	if (!messages) messages = new Map((JSON.parse(wasm.diagnosticCodes()) as { code: string; message: string }[]).map((c) => [c.code, c.message]));
	const template = messages.get(d.code) ?? d.code;
	return template.replace(/\{([01])\}/g, (_, i: string) => d.args[Number(i)] ?? '');
}

// ---------------------------------------------------------------------------
// Completion
// ---------------------------------------------------------------------------

export type PyCompletionKind = 'variable' | 'parameter' | 'function' | 'method' | 'module' | 'constant' | 'field' | 'keyword' | 'snippet' | 'value';

export interface PyCompletionItem {
	label: string;
	kind: PyCompletionKind;
	insert: string;
	/** Where the caret lands, as an offset into `insert`. */
	caret: number;
	detail: string | null;
	docPath: string | null;
	/** Its doc, one paragraph. */
	doc?: string | null;
	/** Choosing it leaves a blank with suggestions of its own: the list opens again. */
	reopen?: boolean;
}

export interface PyCompletions {
	replace: Range;
	items: PyCompletionItem[];
}

export function complete(source: string, caret: number, explicit: boolean, host: PyHost | null): PyCompletions | null {
	if (!loaded) return null;
	const raw = JSON.parse(wasm.complete(source, caret, explicit, JSON.stringify(host ?? {}))) as { replaceStart: number; replaceEnd: number; items: PyCompletionItem[] } | null;
	if (!raw) return null;
	return { replace: { start: raw.replaceStart, end: raw.replaceEnd }, items: raw.items };
}

// ---------------------------------------------------------------------------
// Formatting and navigation
// ---------------------------------------------------------------------------

export function format(source: string): string {
	return loaded ? wasm.format(source) : source;
}

export function definition(source: string, caret: number): Range | null {
	if (!loaded) return null;
	return JSON.parse(wasm.definition(source, caret)) as Range | null;
}

export function references(source: string, caret: number): Range[] {
	if (!loaded) return [];
	return (JSON.parse(wasm.references(source, caret)) as [number, number][]).map(([start, end]) => ({ start, end }));
}

export type RenameProblem = 'not_a_name' | 'keyword' | 'builtin' | 'taken' | 'api' | 'hook';

export interface RenamePlan {
	spans: Range[];
	problem: RenameProblem | null;
}

/** What renaming the name at `caret` to `newName` touches; an empty name asks for the places only. Null when no name is there. */
export function rename(source: string, caret: number, newName: string): RenamePlan | null {
	if (!loaded) return null;
	const raw = JSON.parse(wasm.rename(source, caret, newName)) as { spans: [number, number][]; problem: RenameProblem | null } | null;
	if (!raw) return null;
	return { spans: raw.spans.map(([start, end]) => ({ start, end })), problem: raw.problem };
}

// ---------------------------------------------------------------------------
// The plugin API model
// ---------------------------------------------------------------------------

export interface PyApiEntry {
	path: string;
	parent: string | null;
	name: string;
	kind: 'module' | 'function' | 'method' | 'constant' | 'builtin' | 'exception' | 'type';
	signature: string | null;
	doc: string;
	permission: string | null;
}

export interface PyApi {
	entries: PyApiEntry[];
	eventTypes: string[];
	hooks: string[];
}

let api: PyApi | null = null;

export function apiEntries(): PyApi {
	if (!loaded) return { entries: [], eventTypes: [], hooks: [] };
	if (!api) api = JSON.parse(wasm.apiEntries()) as PyApi;
	return api;
}

/** The API entry the caret is on, for the doc strip. */
export function apiAt(source: string, caret: number): PyApiEntry | null {
	if (!loaded) return null;
	return JSON.parse(wasm.apiAt(source, caret)) as PyApiEntry | null;
}

// ---------------------------------------------------------------------------
// Signature help, hover and the provider lists
// ---------------------------------------------------------------------------

export interface PySignature {
	/** `http.get(url, params=None, **options)`. */
	label: string;
	/** Each parameter's place in `label`. */
	params: [number, number][];
	names: string[];
	/** The parameter the caret is in. */
	active: number | null;
	doc: string;
	/** What the active parameter takes, in words. */
	paramDoc: string | null;
	values: string[];
	path: string | null;
}

/** The call around `caret`, with the argument being typed; null outside a call. */
export function signature(source: string, caret: number, host: PyHost | null): PySignature | null {
	if (!loaded || typeof wasm.signature !== 'function') return null;
	return JSON.parse(wasm.signature(source, caret, JSON.stringify(host ?? {}))) as PySignature | null;
}

export interface PyHover {
	start: number;
	end: number;
	title: string;
	doc: string;
	kind: string;
	path: string | null;
}

/** What the name at `offset` is: an API entry, the file's own function, a provider's request, a setting. */
export function hover(source: string, offset: number, host: PyHost | null): PyHover | null {
	if (!loaded || typeof wasm.hover !== 'function') return null;
	return JSON.parse(wasm.hover(source, offset, JSON.stringify(host ?? {}))) as PyHover | null;
}

export interface PyProviderTool {
	tool: string;
	label: string;
	/** The request's keys, each with what it holds. */
	request: [string, string][];
	/** A request as the keyboard sends one, as JSON. */
	sample: string;
	reply: string;
}

let tools: PyProviderTool[] | null = null;

/** The provider lists a plugin can answer. */
export function providerTools(): PyProviderTool[] {
	if (!loaded || typeof wasm.providerTools !== 'function') return [];
	if (!tools) tools = JSON.parse(wasm.providerTools()) as PyProviderTool[];
	return tools;
}

// ---------------------------------------------------------------------------
// Rules for typing
// ---------------------------------------------------------------------------

/** Whether a caret at `offset` is inside a comment or an open string, judged by lexing only the text before it. */
export function inInert(source: string, offset: number): boolean {
	return loaded && wasm.inInert(source, offset);
}

/** Whether `line` ends a block header: its last code token is a `:` outside brackets. */
export function opensBlock(line: string): boolean {
	return loaded && wasm.opensBlock(line);
}

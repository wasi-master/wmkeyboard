/* tslint:disable */
/* eslint-disable */

/**
 * A running plugin: `wm_python::host::Session`, with the result JSON its
 * docs describe, plus `"storage": {"key": "value", ...}` in the config to
 * start the in-memory store with.
 */
export class PySession {
    free(): void;
    [Symbol.dispose](): void;
    /**
     * Stops the call in flight. Only a host that runs the session off the
     * page's thread (a worker) can call it mid-call.
     */
    cancel(): void;
    /**
     * Hand an event to the plugin, then render (see `wm_python::host`).
     */
    event(event_json: string): string;
    /**
     * Load the plugin's source: panel mode runs the top level, then renders.
     */
    load(source: string): string;
    constructor(config_json: string);
    /**
     * Render the panel again.
     */
    render(): string;
    /**
     * Where API 5 host calls go: `(op, argsJson) => replyJson`, or null for none.
     */
    setBridge(bridge?: Function | null): void;
    storageRemove(key: string): void;
    /**
     * Write one key between calls, under the plugin's quotas. Returns why it
     * was refused, or nothing when it went through.
     */
    storageSet(key: string, value: string): string | undefined;
    /**
     * The plugin's store as `{"key": "value", ...}`, in insertion order;
     * `{}` without the storage permission.
     */
    storage(): string;
    /**
     * Run the plugin's `transform` on a selection.
     */
    transform(text: string, action_id: string): string;
    /**
     * The latest phase's usage.
     */
    usage(): string;
}

export function analyse(source: string, host: string): string;

export function apiAt(source: string, caret: number): string;

export function apiEntries(): string;

/**
 * The interpreter's "will it run" verdict: nothing, or `{"message", "kind", "line"}`.
 */
export function check(source: string): string | undefined;

export function complete(source: string, caret: number, explicit: boolean, host: string): string;

export function definition(source: string, caret: number): string;

export function diagnosticCodes(): string;

export function format(source: string): string;

export function inInert(source: string, offset: number): boolean;

export function opensBlock(line: string): boolean;

export function references(source: string, caret: number): string;

export function rename(source: string, caret: number, new_name: string): string;

/**
 * Triples `word, start, end`; `word = kind | unterminated << 8 | name_style << 12`.
 */
export function tokens(source: string): Int32Array;

export type InitInput = RequestInfo | URL | Response | BufferSource | WebAssembly.Module;

export interface InitOutput {
    readonly memory: WebAssembly.Memory;
    readonly __wbg_pysession_free: (a: number, b: number) => void;
    readonly analyse: (a: number, b: number, c: number, d: number) => [number, number];
    readonly apiAt: (a: number, b: number, c: number) => [number, number];
    readonly apiEntries: () => [number, number];
    readonly check: (a: number, b: number) => [number, number];
    readonly complete: (a: number, b: number, c: number, d: number, e: number, f: number) => [number, number];
    readonly definition: (a: number, b: number, c: number) => [number, number];
    readonly diagnosticCodes: () => [number, number];
    readonly format: (a: number, b: number) => [number, number];
    readonly inInert: (a: number, b: number, c: number) => number;
    readonly opensBlock: (a: number, b: number) => number;
    readonly pysession_cancel: (a: number) => void;
    readonly pysession_event: (a: number, b: number, c: number) => [number, number];
    readonly pysession_load: (a: number, b: number, c: number) => [number, number];
    readonly pysession_new: (a: number, b: number) => [number, number, number];
    readonly pysession_render: (a: number) => [number, number];
    readonly pysession_setBridge: (a: number, b: number) => void;
    readonly pysession_storage: (a: number) => [number, number];
    readonly pysession_storageRemove: (a: number, b: number, c: number) => void;
    readonly pysession_storageSet: (a: number, b: number, c: number, d: number, e: number) => [number, number];
    readonly pysession_transform: (a: number, b: number, c: number, d: number, e: number) => [number, number];
    readonly pysession_usage: (a: number) => [number, number];
    readonly references: (a: number, b: number, c: number) => [number, number];
    readonly rename: (a: number, b: number, c: number, d: number, e: number) => [number, number];
    readonly tokens: (a: number, b: number) => [number, number];
    readonly __wbindgen_malloc: (a: number, b: number) => number;
    readonly __wbindgen_realloc: (a: number, b: number, c: number, d: number) => number;
    readonly __wbindgen_free: (a: number, b: number, c: number) => void;
    readonly __wbindgen_exn_store: (a: number) => void;
    readonly __externref_table_alloc: () => number;
    readonly __wbindgen_externrefs: WebAssembly.Table;
    readonly __externref_table_dealloc: (a: number) => void;
    readonly __wbindgen_start: () => void;
}

export type SyncInitInput = BufferSource | WebAssembly.Module;

/**
 * Instantiates the given `module`, which can either be bytes or
 * a precompiled `WebAssembly.Module`.
 *
 * @param {{ module: SyncInitInput }} module - Passing `SyncInitInput` directly is deprecated.
 *
 * @returns {InitOutput}
 */
export function initSync(module: { module: SyncInitInput } | SyncInitInput): InitOutput;

/**
 * If `module_or_path` is {RequestInfo} or {URL}, makes a request and
 * for everything else, calls `WebAssembly.instantiate` directly.
 *
 * @param {{ module_or_path: InitInput | Promise<InitInput> }} module_or_path - Passing `InitInput` directly is deprecated.
 *
 * @returns {Promise<InitOutput>}
 */
export default function __wbg_init (module_or_path?: { module_or_path: InitInput | Promise<InitInput> } | InitInput | Promise<InitInput>): Promise<InitOutput>;

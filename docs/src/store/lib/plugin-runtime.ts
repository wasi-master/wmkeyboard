/**
 * The plugin editor's runtime on the web (PluginPreviewSession.kt): plugins run
 * in the app's own interpreter, `wm-python` built to wasm (see `python.ts`),
 * which also checks and repairs the widget tree the way the keyboard does.
 * This file keeps what the editor shows: the panel the plugin drew, the
 * console, what each phase spent, why it failed, and the preview storage.
 *
 * Calls run on the page's thread. A browser has no wall clock the interpreter
 * can poll, so each phase's step budget is what stops a runaway loop.
 */
import { signal, type Signal } from '@preact/signals';
import { PySession } from './python';
import { S } from '../ui/creators/ide/strings';

export const MAIN_CHUNK = 'main.py';

// ---------------------------------------------------------------------------
// Phases and what they spent
// ---------------------------------------------------------------------------

export type Phase = 'LOAD' | 'EVENT' | 'RENDER';

export interface Usage {
	steps: number;
	stepLimit: number;
	elapsedMillis: number;
	wallLimitMillis: number;
	running: boolean;
}

// ---------------------------------------------------------------------------
// Widgets (PluginWidget.kt), as the interpreter's codec hands them over
// ---------------------------------------------------------------------------

export type LabelStyle = 'TITLE' | 'BODY' | 'CAPTION';

export type PluginWidget =
	| { kind: 'column'; children: PluginWidget[] }
	| { kind: 'row'; children: PluginWidget[] }
	| { kind: 'label'; text: string; style: LabelStyle }
	| { kind: 'output'; id: string; text: string; mono: boolean; insertable: boolean; copyable: boolean }
	| { kind: 'button'; id: string; text: string; primary: boolean; enabled: boolean }
	| { kind: 'toggle'; id: string; label: string; checked: boolean }
	| { kind: 'input'; id: string; label: string; placeholder: string }
	| { kind: 'date'; id: string; label: string; value: number | null; time: boolean; step: number }
	| { kind: 'spacer'; height: number }
	| { kind: 'divider' }
	| { kind: 'progress' }
	| { kind: 'tabs'; id: string; pages: { title: string; children: PluginWidget[] }[] };

export type RepairCode = 'NOT_A_WIDGET' | 'TOO_DEEP' | 'TOO_MANY_WIDGETS' | 'NO_TYPE' | 'UNKNOWN_TYPE' | 'TABS_NO_PAGES' | 'TOO_MANY_TABS' | 'TEXT_BUDGET' | 'TEXT_SHORTENED';

export interface RenderedUi {
	root: PluginWidget[];
	repairs: string[];
	repairCodes: RepairCode[];
}

export const EMPTY_UI: RenderedUi = { root: [], repairs: [], repairCodes: [] };

type WireWidget = Record<string, unknown> & { type: string };

function widgetOf(w: WireWidget): PluginWidget | null {
	const str = (k: string) => String(w[k] ?? '');
	const kids = (v: unknown) => (Array.isArray(v) ? (v as WireWidget[]).map(widgetOf).filter((x): x is PluginWidget => x !== null) : []);
	switch (w.type) {
		case 'column':
		case 'row': return { kind: w.type, children: kids(w.children) };
		case 'label': return { kind: 'label', text: str('text'), style: (str('style').toUpperCase() as LabelStyle) || 'BODY' };
		case 'output': return { kind: 'output', id: str('id'), text: str('text'), mono: !!w.mono, insertable: !!w.insertable, copyable: !!w.copyable };
		case 'button': return { kind: 'button', id: str('id'), text: str('text'), primary: !!w.primary, enabled: !!w.enabled };
		case 'toggle': return { kind: 'toggle', id: str('id'), label: str('label'), checked: !!w.checked };
		case 'input': return { kind: 'input', id: str('id'), label: str('label'), placeholder: str('placeholder') };
		case 'date': return { kind: 'date', id: str('id'), label: str('label'), value: typeof w.value === 'number' ? w.value : null, time: !!w.time, step: Number(w.step) || 15 };
		case 'spacer': return { kind: 'spacer', height: Number(w.height) || 0 };
		case 'divider': return { kind: 'divider' };
		case 'progress': return { kind: 'progress' };
		case 'tabs': return { kind: 'tabs', id: str('id'), pages: (Array.isArray(w.pages) ? (w.pages as { title?: string; children?: unknown }[]) : []).map((p) => ({ title: String(p.title ?? ''), children: kids(p.children) })) };
		default: return null;
	}
}

function repairText(code: RepairCode, arg: string | null): string {
	switch (code) {
		case 'NOT_A_WIDGET': return S.repairNotAWidget;
		case 'TOO_DEEP': return S.repairTooDeep;
		case 'TOO_MANY_WIDGETS': return S.repairTooManyWidgets(Number(arg) || 256);
		case 'NO_TYPE': return S.repairNoType;
		case 'UNKNOWN_TYPE': return S.repairUnknownType(arg ?? '');
		case 'TABS_NO_PAGES': return S.repairTabsNoPages;
		case 'TOO_MANY_TABS': return S.repairTooManyTabs(Number(arg) || 8);
		case 'TEXT_BUDGET': return S.repairTextBudget;
		case 'TEXT_SHORTENED': return S.repairTextShortened;
	}
}

/** The finished tree the interpreter wrote, in the editor's terms. */
export function uiOf(wire: { root: WireWidget[]; repairs: { code: RepairCode; arg: string | null }[] }): RenderedUi {
	const root = wire.root.map(widgetOf).filter((x): x is PluginWidget => x !== null);
	return { root, repairs: wire.repairs.map((r) => repairText(r.code, r.arg)), repairCodes: wire.repairs.map((r) => r.code) };
}

export interface PluginTargets {
	buttons: { id: string; enabled: boolean }[];
	toggles: { id: string; checked: boolean }[];
	inputs: { id: string }[];
	tabs: { id: string; pages: number }[];
	dates: { id: string; value: number | null; time: boolean }[];
}

export const EMPTY_TARGETS: PluginTargets = { buttons: [], toggles: [], inputs: [], tabs: [], dates: [] };

export const targetsEmpty = (t: PluginTargets) => !t.buttons.length && !t.toggles.length && !t.inputs.length && !t.tabs.length && !t.dates.length;

/** Every button, toggle, input and tab strip in this tree, in document order. */
export function targetsOf(ui: RenderedUi): PluginTargets {
	const out: PluginTargets = { buttons: [], toggles: [], inputs: [], tabs: [], dates: [] };
	const walk = (list: PluginWidget[]) => {
		for (const w of list) {
			switch (w.kind) {
				case 'column':
				case 'row': walk(w.children); break;
				case 'button': out.buttons.push({ id: w.id, enabled: w.enabled }); break;
				case 'toggle': out.toggles.push({ id: w.id, checked: w.checked }); break;
				case 'input': out.inputs.push({ id: w.id }); break;
				case 'date': out.dates.push({ id: w.id, value: w.value, time: w.time }); break;
				case 'tabs':
					out.tabs.push({ id: w.id, pages: w.pages.length });
					for (const p of w.pages) walk(p.children);
					break;
				default: break;
			}
		}
	};
	walk(ui.root);
	return out;
}

// ---------------------------------------------------------------------------
// Events
// ---------------------------------------------------------------------------

export type PluginEvent =
	| { type: 'click'; id: string }
	| { type: 'toggle'; id: string; value: boolean }
	| { type: 'input_changed'; id: string; value: string }
	| { type: 'tab_selected'; id: string; index: number }
	| { type: 'date_changed'; id: string; value: number | null };

// ---------------------------------------------------------------------------
// Dates (PluginDates.kt): what each button on a ui.date does, in local time
// ---------------------------------------------------------------------------

export const Dates = {
	initial(nowMillis: number, time: boolean): number {
		const d = new Date(nowMillis);
		if (!time) return Math.floor(new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime() / 1000);
		d.setMinutes(0, 0, 0);
		d.setHours(d.getHours() + 1);
		return Math.floor(d.getTime() / 1000);
	},
	addDays(value: number, days: number): number {
		const d = new Date(value * 1000);
		d.setDate(d.getDate() + days);
		return Math.floor(d.getTime() / 1000);
	},
	addMinutes: (value: number, minutes: number): number => value + minutes * 60,
	today(value: number, nowMillis: number): number {
		const from = new Date(value * 1000);
		const d = new Date(nowMillis);
		d.setHours(from.getHours(), from.getMinutes(), from.getSeconds(), 0);
		return Math.floor(d.getTime() / 1000);
	},
};

// ---------------------------------------------------------------------------
// Storage (PluginStorage.kt)
// ---------------------------------------------------------------------------

/** The app's quotas, in UTF-16 units as Kotlin's String.length counts them. */
export const STORAGE = { MAX_KEYS: 128, MAX_KEY_LENGTH: 64, MAX_VALUE_LENGTH: 8 * 1024, MAX_TOTAL: 64 * 1024 };

/** What a running session lets the storage pane write through. */
interface LiveStorage {
	set(key: string, value: string): string | undefined;
	remove(key: string): void;
}

/**
 * A draft's preview storage, kept in localStorage under one key. A run starts
 * from it and writes back to it after every call; while a run is live, the
 * storage pane's edits go to the running plugin first, under its quotas.
 */
export class PreviewStorage {
	private map = new Map<string, string>();
	/** The running session's store, while there is one. */
	live: LiveStorage | null = null;

	constructor(private readonly storageKey: string | null) {
		if (storageKey) {
			try {
				const raw = localStorage.getItem(storageKey);
				if (raw) for (const [k, v] of Object.entries(JSON.parse(raw) as Record<string, string>)) if (typeof v === 'string') this.map.set(k, v);
			} catch {
				/* a fresh store */
			}
		}
	}

	private persist() {
		if (!this.storageKey) return;
		try {
			localStorage.setItem(this.storageKey, JSON.stringify(Object.fromEntries(this.map)));
		} catch {
			/* quota: the preview keeps going in memory */
		}
	}

	keys(): string[] {
		return [...this.map.keys()];
	}

	get(key: string): string | null {
		return this.map.get(key) ?? null;
	}

	entries(): Record<string, string> {
		return Object.fromEntries(this.map);
	}

	/** Takes what the running plugin's store holds now. */
	replaceAll(entries: Record<string, string>) {
		const next = new Map(Object.entries(entries).filter(([, v]) => typeof v === 'string'));
		if (next.size === this.map.size && [...next].every(([k, v]) => this.map.get(k) === v)) return;
		this.map = next;
		this.persist();
	}

	/** Null when written, else the reason the plugin itself would be told. */
	set(key: string, value: string): string | null {
		if (this.live) {
			const refused = this.live.set(key, value);
			if (refused) return refused;
		} else {
			const refused = this.refusal(key, value);
			if (refused) return refused;
		}
		this.map.set(key, value);
		this.persist();
		return null;
	}

	private refusal(key: string, value: string): string | null {
		if (!key) return "storage keys can't be empty";
		if (key.length > STORAGE.MAX_KEY_LENGTH) return `storage keys can't be longer than ${STORAGE.MAX_KEY_LENGTH} characters`;
		if (value.length > STORAGE.MAX_VALUE_LENGTH) return `that value is larger than the ${STORAGE.MAX_VALUE_LENGTH / 1024} KB limit for one key`;
		if (!this.map.has(key) && this.map.size >= STORAGE.MAX_KEYS) return `this plugin already has the maximum of ${STORAGE.MAX_KEYS} stored keys`;
		let total = key.length + value.length;
		for (const [k, v] of this.map) if (k !== key) total += k.length + v.length;
		if (total > STORAGE.MAX_TOTAL) return `this plugin has used all ${STORAGE.MAX_TOTAL / 1024} KB of its storage`;
		return null;
	}

	remove(key: string) {
		this.live?.remove(key);
		if (this.map.delete(key)) this.persist();
	}

	clear() {
		for (const key of this.keys()) this.live?.remove(key);
		this.map.clear();
		this.persist();
	}

	get size() {
		return this.map.size;
	}
}

// ---------------------------------------------------------------------------
// The interpreter's results (wm_python::host)
// ---------------------------------------------------------------------------

export interface PluginIdentity {
	id: string;
	name: string;
	version: string;
	storage: boolean;
	reminders?: boolean;
	/** The manifest's apiVersion: 5 loads the wm package, anything lower the API 4 globals. */
	apiVersion?: number;
}

type AbortWire = 'steps' | 'deadline' | 'cancelled' | 'recursion' | 'container' | 'string';

interface WireResult {
	ok: boolean;
	ui?: { root: WireWidget[]; repairs: { code: RepairCode; arg: string | null }[] };
	error?: { kind: 'compile' | 'runtime' | 'abort' | 'no_render' | 'no_transform' | 'internal'; message: string; line: number | null; abort: AbortWire | null; exception: string | null };
	log: string[];
	inputs: [string, string][];
	remindersChanged: boolean;
	phases: { phase: 'load' | 'event' | 'render' | 'action'; steps: number; stepsLimit: number; elapsedMicros: number; millisLimit: number }[];
}

function configOf(plugin: PluginIdentity, storage: Record<string, string> | null): string {
	const permissions = [...(plugin.storage ? ['storage'] : []), ...(plugin.reminders ? ['reminders'] : [])];
	return JSON.stringify({
		id: plugin.id, version: plugin.version, permissions, mode: 'panel', storage: storage ?? {},
		...(plugin.apiVersion ? { apiVersion: plugin.apiVersion } : {}),
	});
}

// ---------------------------------------------------------------------------
// The session (PluginPreviewSession.kt + the runtime's flow)
// ---------------------------------------------------------------------------

export type FailureKind = 'COMPILE' | 'RUNTIME' | 'STEPS' | 'DEADLINE' | 'CANCELLED' | 'RECURSION' | 'MEMORY' | 'UNEXPECTED';

export interface Failure {
	kind: FailureKind;
	text: string;
	line: number | null;
	phase: Phase;
}

export type Status = 'IDLE' | 'RUNNING' | 'READY' | 'FAILED' | 'STOPPED';

export type ConsoleKind = 'STARTED' | 'PRINT' | 'REPAIR' | 'ERROR' | 'STOPPED' | 'CANCELLED';

export interface ConsoleEntry {
	at: number;
	kind: ConsoleKind;
	text: string;
	message?: string;
	/** The script's line (1-based) the entry points at. */
	line?: number | null;
	failure?: FailureKind;
	repair?: RepairCode;
}

export interface PreviewState {
	status: Status;
	draftId: string | null;
	ui: RenderedUi;
	busy: boolean;
	failure: Failure | null;
	usage: Partial<Record<Phase, Usage>>;
	targets: PluginTargets;
	inputs: Record<string, string>;
	consoleRevision: number;
	runs: number;
}

export const MAX_CONSOLE = 500;
export const MAX_EVENTS = 200;
export const MAX_ERROR_TEXT = 8 * 1024;
export const MAX_INPUT = 8 * 1024;

interface Session {
	draftId: string;
	py: PySession;
	storage: PreviewStorage | null;
	loaded: boolean;
}

const PHASES: Record<string, Phase> = { load: 'LOAD', event: 'EVENT', render: 'RENDER' };

export class PluginPreviewSession {
	readonly state: Signal<PreviewState> = signal({ status: 'IDLE', draftId: null, ui: EMPTY_UI, busy: false, failure: null, usage: {}, targets: EMPTY_TARGETS, inputs: {}, consoleRevision: 0, runs: 0 });
	private entries: ConsoleEntry[] = [];
	private events: PluginEvent[] = [];
	private runStartedAt = 0;
	private lastRepairs: string[] = [];
	private current: Session | null = null;
	private storageOwner: string | null = null;
	private sharedStorage: PreviewStorage | null = null;

	constructor(private readonly storageKeyOf: (draftId: string) => string | null) {}

	private update(patch: Partial<PreviewState> | ((s: PreviewState) => Partial<PreviewState>)) {
		const s = this.state.value;
		this.state.value = { ...s, ...(typeof patch === 'function' ? patch(s) : patch) };
	}

	// ---- control ---------------------------------------------------------

	run(draftId: string, source: string, plugin: PluginIdentity) {
		this.closeCurrent(true);
		const storage = plugin.storage ? this.storageOf(draftId) : null;
		this.runStartedAt = performance.now();
		this.lastRepairs = [];
		this.update((s) => ({ status: 'RUNNING', draftId, failure: null, usage: {}, inputs: s.draftId === draftId ? s.inputs : {}, runs: s.runs + 1 }));
		this.append({ at: 0, kind: 'STARTED', text: '' });
		let py: PySession;
		try {
			py = new PySession(configOf(plugin, storage?.entries() ?? null));
		} catch (e) {
			this.append({ at: this.elapsed(), kind: 'ERROR', text: String((e as Error)?.message ?? e), failure: 'UNEXPECTED' });
			this.update({ status: 'FAILED' });
			return;
		}
		const session: Session = { draftId, py, storage, loaded: false };
		this.current = session;
		if (storage) storage.live = { set: (k, v) => py.storageSet(k, v), remove: (k) => py.storageRemove(k) };
		this.call(session, 'LOAD', () => py.load(source));
	}

	send(event: PluginEvent) {
		const sent: PluginEvent = event.type === 'input_changed' ? { ...event, value: event.value.substring(0, MAX_INPUT) } : event;
		if (sent.type === 'input_changed') this.update((s) => ({ inputs: { ...s.inputs, [sent.id]: sent.value } }));
		const session = this.current;
		if (!session || !session.loaded) return;
		this.events.push(sent);
		while (this.events.length > MAX_EVENTS) this.events.shift();
		this.call(session, 'EVENT', () => session.py.event(JSON.stringify(sent)));
	}

	/** Ends the current run. The panel stays as it last drew. */
	stop() {
		this.closeCurrent(false);
		this.update((s) => ({ status: s.status === 'IDLE' ? 'IDLE' : 'STOPPED', busy: false }));
	}

	shutdown() {
		this.closeCurrent(false);
	}

	private closeCurrent(replaced: boolean) {
		const c = this.current;
		if (!c) return;
		this.current = null;
		if (replaced) this.append({ at: this.elapsed(), kind: 'CANCELLED', text: '', failure: 'CANCELLED' });
		this.release(c);
	}

	private release(session: Session) {
		if (session.storage) session.storage.live = null;
		try {
			session.py.free();
		} catch {
			/* a trapped module: nothing left to free */
		}
	}

	storageOf(draftId: string): PreviewStorage {
		if (this.storageOwner !== draftId || !this.sharedStorage) {
			this.storageOwner = draftId;
			this.sharedStorage = new PreviewStorage(this.storageKeyOf(draftId));
		}
		return this.sharedStorage;
	}

	console(): ConsoleEntry[] {
		return this.entries.slice();
	}

	clearConsole() {
		this.entries = [];
		this.bumpConsole();
	}

	recordedEvents(): PluginEvent[] {
		return this.events.slice();
	}

	// ---- what the runtime reports ------------------------------------------

	private append(entry: ConsoleEntry) {
		this.entries.push(entry);
		while (this.entries.length > MAX_CONSOLE) this.entries.shift();
		this.bumpConsole();
	}

	private bumpConsole() {
		this.update((s) => ({ consoleRevision: s.consoleRevision + 1 }));
	}

	private elapsed(): number {
		return Math.max(Math.round(performance.now() - this.runStartedAt), 0);
	}

	/** One call into the interpreter, and everything its result says. */
	private call(session: Session, phase: Phase, body: () => string) {
		if (this.current !== session) return;
		this.update({ busy: true });
		let result: WireResult;
		try {
			result = JSON.parse(body()) as WireResult;
		} catch (e) {
			// The module trapped: the session may be half way through a change, so it goes.
			const name = (e as Error)?.constructor?.name ?? 'Error';
			const message = (e as Error)?.message;
			this.update({ failure: { kind: 'UNEXPECTED', text: message ? `${name}: ${message}` : name, line: null, phase }, busy: false });
			this.stopSession(session, S.stoppedUnexpected);
			return;
		}
		for (const line of result.log) this.append({ at: this.elapsed(), kind: 'PRINT', text: line });
		if (result.inputs.length) this.update((s) => ({ inputs: { ...s.inputs, ...Object.fromEntries(result.inputs.map(([id, text]) => [id, text.substring(0, MAX_INPUT)])) } }));
		const usage: Partial<Record<Phase, Usage>> = {};
		for (const p of result.phases) {
			const name = PHASES[p.phase];
			if (name) usage[name] = { steps: p.steps, stepLimit: p.stepsLimit, elapsedMillis: p.elapsedMicros / 1000, wallLimitMillis: p.millisLimit, running: false };
		}
		this.update((s) => ({ usage: { ...s.usage, ...usage }, busy: false }));
		if (session.storage) {
			try {
				session.storage.replaceAll(JSON.parse(session.py.storage()) as Record<string, string>);
			} catch {
				/* the store stays as it was */
			}
		}
		const error = result.error;
		if (phase === 'LOAD') session.loaded = !error || error.kind === 'no_render' || result.phases.some((p) => p.phase === 'render');
		if (result.ui) this.onUi(uiOf(result.ui));
		if (!error) return;
		const failedIn = PHASES[result.phases[result.phases.length - 1]?.phase ?? ''] ?? phase;
		switch (error.kind) {
			case 'compile':
			case 'runtime':
				this.scriptFailed(error.kind === 'compile' ? 'COMPILE' : 'RUNTIME', error.message, error.line, failedIn);
				return;
			case 'no_render':
				this.append({ at: this.elapsed(), kind: 'ERROR', text: '', message: S.errorNoRender });
				this.update({ status: 'FAILED' });
				return;
			case 'abort': {
				const [kind, message] = abortOf(error.abort);
				this.update({ failure: { kind, text: error.message, line: error.line, phase: failedIn } });
				if (kind === 'CANCELLED') this.append({ at: this.elapsed(), kind: 'CANCELLED', text: '', failure: kind });
				else this.stopSession(session, message);
				return;
			}
			default:
				this.update({ failure: { kind: 'UNEXPECTED', text: error.message, line: null, phase: failedIn } });
				this.stopSession(session, S.stoppedUnexpected);
		}
	}

	private onUi(ui: RenderedUi) {
		this.update((s) => ({ ui, targets: targetsOf(ui), status: s.status === 'FAILED' && s.failure?.kind === 'COMPILE' ? s.status : 'READY' }));
		if (ui.repairs.join('\n') !== this.lastRepairs.join('\n')) {
			this.lastRepairs = ui.repairs;
			ui.repairs.forEach((repair, index) => this.append({ at: this.elapsed(), kind: 'REPAIR', text: '', message: repair, repair: ui.repairCodes[index] }));
		}
	}

	private scriptFailed(kind: FailureKind, message: string, line: number | null, phase: Phase) {
		const text = message.substring(0, MAX_ERROR_TEXT);
		this.append({ at: this.elapsed(), kind: 'ERROR', text, line, failure: kind });
		this.update({ failure: { kind, text, line, phase }, status: 'FAILED' });
	}

	/** Ends the session for a reason the user should see. */
	private stopSession(session: Session, message: string) {
		if (this.current === session) {
			this.current = null;
			this.release(session);
		}
		this.append({ at: this.elapsed(), kind: 'STOPPED', text: '', message });
		this.update({ status: 'STOPPED', busy: false });
	}
}

function abortOf(abort: AbortWire | null): [FailureKind, string] {
	switch (abort) {
		case 'steps': return ['STEPS', S.stoppedSteps];
		case 'deadline': return ['DEADLINE', S.stoppedDeadline];
		case 'cancelled': return ['CANCELLED', S.stoppedCancelled];
		case 'recursion': return ['RECURSION', S.stoppedRecursion];
		case 'container':
		case 'string': return ['MEMORY', S.stoppedMemory];
		default: return ['UNEXPECTED', S.stoppedUnexpected];
	}
}

/** One load and draw of a script, for a template's thumbnail. Null when it fails. Never keeps a session. */
export function renderOnce(plugin: PluginIdentity, script: string): RenderedUi | null {
	let py: PySession | null = null;
	try {
		py = new PySession(configOf(plugin, null));
		const result = JSON.parse(py.load(script)) as WireResult;
		return result.ok && result.ui ? uiOf(result.ui) : null;
	} catch {
		return null;
	} finally {
		try {
			py?.free();
		} catch {
			/* trapped */
		}
	}
}

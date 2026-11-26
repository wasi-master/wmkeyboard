/**
 * `wmpy preview`'s page: the plugin's panel drawn by the web editor's own
 * renderer (V5Widgets.tsx and ide.css from docs/src/store), fed by wmpy
 * over server-sent events. The plugin runs in wmpy, not here: the page
 * only draws what it is sent and posts back what the person does.
 *
 * `?t=` is the token every request carries. `?theme=dark|light` picks the
 * look (and tells wmpy, so plugin.dark matches); `?embed=1` keeps only the
 * panel and a thin console strip, for an editor's side view.
 *
 * Copied from the web editor rather than imported, because importing them
 * would pull the whole editor and its wasm interpreter into the bundle:
 * the API 4 widgets of PluginIde.tsx (PluginPanel, Widget, DateWidget),
 * Dates from plugin-runtime.ts, and the wire decoding (widgetV5, widgetOf).
 */
import { render } from 'preact';
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import type { PluginEvent, PluginWidget } from '../../../docs/src/store/lib/plugin-runtime';
import { V5Widget } from '../../../docs/src/store/ui/creators/ide/V5Widgets';
import { S } from '../../../docs/src/store/ui/creators/ide/strings';
import '../../../docs/src/store/ui/creators/ide/ide.css';
import './preview.css';

const params = new URLSearchParams(location.search);
const token = params.get('t') ?? '';
const embed = params.get('embed') === '1';
const askedTheme = params.get('theme');

type Json = Record<string, unknown>;

interface Diag { severity: string; code: string | null; message: string; file: string | null; line: number | null; col: number | null; traceback: { file: string | null; line: number | null; function: string | null }[]; notes: string[]; help: string | null }
interface ConsoleEntry { kind: 'print' | 'error' | 'note' | 'repair' | 'reload' | 'sent'; text: string; at: number; diag: Diag | null }
interface Call { op: string; args: unknown; reply: string; source: string; millis: number }
interface HostMeta { title?: string | null; commands?: { id: string; label: string; icon: string | null }[]; canBack?: boolean }
interface State {
	version: number;
	plugin: { id: string; name: string; version: string; api: number; panel: boolean; dir: string };
	theme: 'light' | 'dark';
	status: 'ready' | 'failed' | 'no_panel';
	ui: { root: Json[]; repairs: unknown[] };
	host: HostMeta | null;
	inputs: Record<string, string>;
	console: ConsoleEntry[];
	storage: Record<string, string>;
	secrets: string[];
	field: { text: string; cursor: number; selection: [number, number] | null };
	clipboard: string;
	calls: Call[];
	sent: Json[];
	error: Diag | null;
	toasts: string[];
	dialog: { title: string; text: string | null; buttons: string[] } | null;
	usage: { phase: string; steps: number; stepsLimit: number; elapsedMicros: number; millisLimit: number }[];
	closed: boolean;
}

async function post(path: string, body: unknown): Promise<Json> {
	const r = await fetch(`/api/${path}?t=${encodeURIComponent(token)}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body ?? {}) });
	return (await r.json()) as Json;
}

// ---- the wire tree in the editor's terms (plugin-runtime.ts) -------------

type WireWidget = Json & { type: string };

function widgetV5(w: WireWidget): PluginWidget {
	const list = Array.isArray(w.children) ? w.children : Array.isArray(w.items) ? w.items : [];
	return { kind: 'v5', type: w.type, data: w, children: (list as WireWidget[]).map(widgetV5) };
}

function widgetOf(w: WireWidget): PluginWidget | null {
	const str = (k: string) => String(w[k] ?? '');
	const kids = (v: unknown) => (Array.isArray(v) ? (v as WireWidget[]).map(widgetOf).filter((x): x is PluginWidget => x !== null) : []);
	switch (w.type) {
		case 'column':
		case 'row': return { kind: w.type, children: kids(w.children) };
		case 'label': return { kind: 'label', text: str('text'), style: (str('style').toUpperCase() as 'TITLE' | 'BODY' | 'CAPTION') || 'BODY' };
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

// ---- the API 4 widgets (PluginIde.tsx) -----------------------------------

const Dates = {
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

interface PanelProps { widgets: PluginWidget[]; inputs: Record<string, string>; onEvent: (e: PluginEvent) => void; onInsert: (text: string) => void; onCopy: (text: string) => void; still?: boolean }

function PluginPanel({ widgets, inputs, onEvent, onInsert, onCopy, still }: PanelProps) {
	return <>{widgets.map((w, i) => <Widget key={i} w={w} inputs={inputs} onEvent={onEvent} onInsert={onInsert} onCopy={onCopy} still={!!still} />)}</>;
}

function Widget({ w, inputs, onEvent, onInsert, onCopy, still }: Omit<PanelProps, 'widgets'> & { w: PluginWidget; still: boolean }) {
	const [tab, setTab] = useState(0);
	const kids = (list: PluginWidget[]) => <PluginPanel widgets={list} inputs={inputs} onEvent={onEvent} onInsert={onInsert} onCopy={onCopy} still={still} />;
	switch (w.kind) {
		case 'v5': return <V5Widget w={w} inputs={inputs} onEvent={onEvent} onInsert={onInsert} onCopy={onCopy} still={still} />;
		case 'column': return <div class="wm-w-column">{kids(w.children)}</div>;
		case 'row': return <div class="wm-w-row">{kids(w.children)}</div>;
		case 'label': return <div class={`wm-w-label ${w.style === 'TITLE' ? 'wm-w-label-title' : w.style === 'CAPTION' ? 'wm-w-label-caption' : ''}`}>{w.text}</div>;
		case 'output': return (
			<div class="wm-w-output">
				<div class={`wm-w-output-text ${w.mono ? 'wm-w-output-mono' : ''}`}>{w.text}</div>
				{(w.insertable || w.copyable) && (
					<div class="wm-w-output-actions">
						{w.insertable && <button type="button" class="wm-w-button" onClick={() => onInsert(w.text)}>{S.insert}</button>}
						{w.copyable && <button type="button" class="wm-w-button" onClick={() => onCopy(w.text)}>{S.copy}</button>}
					</div>
				)}
			</div>
		);
		case 'button': return <button type="button" class={`wm-w-button ${w.primary ? 'wm-w-button-primary' : ''}`} disabled={!w.enabled} onClick={() => onEvent({ type: 'click', id: w.id })}>{w.text}</button>;
		case 'toggle': return (
			<label class="wm-w-toggle">
				<input type="checkbox" role="switch" checked={w.checked} onChange={(e) => onEvent({ type: 'toggle', id: w.id, value: (e.target as HTMLInputElement).checked })} />
				<span>{w.label}</span>
			</label>
		);
		case 'input': return (
			<div class="wm-w-input">
				{w.label && <label>{w.label}</label>}
				<input class="st-input" placeholder={w.placeholder} value={still ? '' : inputs[w.id] ?? ''} readOnly={still} onInput={(e) => onEvent({ type: 'input_changed', id: w.id, value: (e.target as HTMLInputElement).value })} />
			</div>
		);
		case 'date': return <DateWidget w={w} onEvent={onEvent} />;
		case 'spacer': return <div style={{ height: `${w.height}px` }} />;
		case 'divider': return <hr class="wm-w-divider" />;
		case 'progress': return <div class="wm-w-progress"><i /></div>;
		case 'tabs': {
			const current = Math.min(tab, w.pages.length - 1);
			return (
				<div class="wm-w-column">
					<div class="wm-w-tabs" role="tablist">
						{w.pages.map((p, i) => (
							<button key={i} type="button" role="tab" class={`st-chip ${i === current ? 'st-chip-on' : ''}`} aria-selected={i === current} onClick={() => { setTab(i); onEvent({ type: 'tab_selected', id: w.id, index: i }); }}>
								{p.title || S.tabDefaultTitle(i + 1)}
							</button>
						))}
					</div>
					{w.pages[current] && kids(w.pages[current]!.children)}
				</div>
			);
		}
	}
}

function DateWidget({ w, onEvent }: { w: Extract<PluginWidget, { kind: 'date' }>; onEvent: (e: PluginEvent) => void }) {
	const send = (value: number | null) => onEvent({ type: 'date_changed', id: w.id, value });
	const value = w.value;
	const step = (label: string, aria: string, onClick: () => void) => <button type="button" class="wm-w-button" aria-label={aria} onClick={onClick}>{label}</button>;
	return (
		<div class="wm-w-input">
			{w.label && <label>{w.label}</label>}
			{value === null ? (
				<div class="wm-w-row">
					<span class="wm-w-label wm-w-label-caption" style="flex:1 1 auto">{S.dateNone}</span>
					<button type="button" class="wm-w-button" onClick={() => send(Dates.initial(Date.now(), w.time))}>{S.dateSet}</button>
				</div>
			) : (
				<>
					<div class="wm-w-row">
						{step('‹', S.datePreviousDay, () => send(Dates.addDays(value, -1)))}
						<span class="wm-w-label" style="flex:1 1 auto;text-align:center">{new Date(value * 1000).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })}</span>
						{step('›', S.dateNextDay, () => send(Dates.addDays(value, 1)))}
					</div>
					{w.time && (
						<div class="wm-w-row">
							{step(S.dateHourEarlier, S.dateHourEarlier, () => send(Dates.addMinutes(value, -60)))}
							{step('‹', S.dateEarlier(w.step), () => send(Dates.addMinutes(value, -w.step)))}
							<span class="wm-w-label" style="flex:1 1 auto;text-align:center">{new Date(value * 1000).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })}</span>
							{step('›', S.dateLater(w.step), () => send(Dates.addMinutes(value, w.step)))}
							{step(S.dateHourLater, S.dateHourLater, () => send(Dates.addMinutes(value, 60)))}
						</div>
					)}
					<div class="wm-w-row">
						<button type="button" class="wm-w-button" onClick={() => send(Dates.today(value, Date.now()))}>{S.dateToday}</button>
						<button type="button" class="wm-w-button" onClick={() => send(null)}>{S.dateClear}</button>
					</div>
				</>
			)}
		</div>
	);
}

// ---- the page --------------------------------------------------------------

function diagText(d: Diag): string {
	const place = d.file ? ` (${d.file}${d.line ? `:${d.line}` : ''})` : '';
	return `${d.code ? `${d.code}: ` : ''}${d.message}${place}`;
}

function Panel({ state }: { state: State }) {
	const v5 = state.plugin.api >= 5;
	const widgets = useMemo(() => (v5 ? state.ui.root.map((w) => widgetV5(w as WireWidget)) : state.ui.root.map((w) => widgetOf(w as WireWidget)).filter((x): x is PluginWidget => x !== null)), [state.ui, v5]);
	const host = state.host;
	const title = host?.title ?? state.plugin.name;
	const send = (e: PluginEvent) => void post('event', e);
	return (
		<div class="wp-panel">
			<div class="wm-w-row wm-w5-header wp-panel-header">
				{host?.canBack && <button type="button" class="wm-w-button" aria-label={S.back} onClick={() => send({ type: 'back' })}>‹</button>}
				<span class="wm-w-label-title" style="flex:1">{title}</span>
				{(host?.commands ?? []).slice(0, 3).map((c) => (
					<button key={c.id} type="button" class="wm-w-button" onClick={() => send({ type: 'command', id: c.id })}>{c.label}</button>
				))}
			</div>
			{state.error && <div class="wm-error-text wp-error" title={state.error.help ?? ''}>{diagText(state.error)}</div>}
			{state.status === 'no_panel' && <div class="wm-caption">This plugin has no panel. Its selection actions run in <code>wmpy test</code>.</div>}
			{state.status !== 'no_panel' && state.ui.root.length === 0 && !state.error && <div class="wm-caption">{S.previewEmpty}</div>}
			<div class="wm-plugin-panel wp-panel-body">
				<PluginPanel widgets={widgets} inputs={state.inputs} onEvent={send} onInsert={(text) => void post('insert', { text })} onCopy={(text) => void post('copy', { text })} />
			</div>
			{state.toasts.length > 0 && <div class="wp-toast" role="status">{state.toasts[state.toasts.length - 1]}</div>}
			{state.dialog && (
				<div class="wm-dialog-backdrop wp-dialog-backdrop" onClick={() => void post('dialog', { button: null })}>
					<div class="wm-dialog" role="dialog" aria-modal="true" aria-label={state.dialog.title} onClick={(e) => e.stopPropagation()}>
						<div class="wm-caption">{state.plugin.name}</div>
						<h2>{state.dialog.title}</h2>
						{state.dialog.text && <div class="wm-dialog-body">{state.dialog.text}</div>}
						<div class="wm-dialog-actions">
							{state.dialog.buttons.map((b, i) => <button key={i} type="button" class="st-btn st-btn-sm st-btn-ghost" onClick={() => void post('dialog', { button: i })}>{b}</button>)}
						</div>
					</div>
				</div>
			)}
		</div>
	);
}

type Tab = 'console' | 'events' | 'storage' | 'field' | 'calls';
const TABS: [Tab, string][] = [['console', 'Console'], ['events', 'Events'], ['storage', 'Storage'], ['field', 'Field'], ['calls', 'Calls']];

function ConsolePane({ state }: { state: State }) {
	const list = useRef<HTMLDivElement>(null);
	useEffect(() => {
		list.current?.lastElementChild?.scrollIntoView({ block: 'nearest' });
	}, [state.console.length]);
	return (
		<div class="wp-pane-body">
			<div class="wm-row-between"><span class="wm-caption">{state.console.length === 0 ? S.consoleEmpty : ''}</span><button type="button" class="st-btn st-btn-sm st-btn-ghost" onClick={() => void post('console/clear', {})}>{S.consoleClear}</button></div>
			<div ref={list} class="wp-console">
				{state.console.map((c, i) => (
					<div key={i} class={`wp-line wp-${c.kind}`}>
						<span class="wp-at">{(c.at / 1000).toFixed(1)}s</span>
						<pre>{c.kind === 'reload' ? `── ${c.text}` : c.text}</pre>
					</div>
				))}
			</div>
		</div>
	);
}

function EventsPane({ state }: { state: State }) {
	const [text, setText] = useState('{"type": "click", "id": ""}');
	const [problem, setProblem] = useState('');
	const sendIt = () => {
		try {
			const e = JSON.parse(text);
			setProblem('');
			void post('event', e);
		} catch (err) {
			setProblem(String((err as Error).message));
		}
	};
	return (
		<div class="wp-pane-body">
			<textarea class="st-textarea wp-mono" rows={3} value={text} onInput={(e) => setText((e.target as HTMLTextAreaElement).value)} onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); sendIt(); } }} />
			<div class="wm-row-between"><span class="wm-error-text">{problem}</span><button type="button" class="st-btn st-btn-sm" onClick={sendIt}>Send</button></div>
			<div class="wm-caption">Sent:</div>
			{state.sent.slice().reverse().map((e, i) => <button key={i} type="button" class="wp-chip-line wp-mono" onClick={() => setText(JSON.stringify(e))}>{JSON.stringify(e)}</button>)}
		</div>
	);
}

function StoragePane({ state }: { state: State }) {
	const entries = Object.entries(state.storage);
	return (
		<div class="wp-pane-body">
			{entries.length === 0 && <div class="wm-caption">Nothing stored yet.</div>}
			<table class="wp-table">
				<tbody>{entries.map(([k, v]) => <tr key={k}><th class="wp-mono">{k}</th><td class="wp-mono">{v}</td><td><button type="button" class="st-btn st-btn-sm st-btn-ghost" onClick={() => void post('storage', { remove: k })}>Delete</button></td></tr>)}</tbody>
			</table>
			{state.secrets.length > 0 && <div class="wm-caption">Secrets (values hidden): {state.secrets.join(', ')}</div>}
			<div class="wm-caption">Kept in {state.plugin.dir}/.wmpy/ across reloads.</div>
		</div>
	);
}

function FieldPane({ state }: { state: State }) {
	const [text, setText] = useState(state.field.text);
	const [clip, setClip] = useState(state.clipboard);
	const own = useRef(false);
	useEffect(() => {
		if (!own.current) setText(state.field.text);
		own.current = false;
	}, [state.field.text]);
	useEffect(() => setClip(state.clipboard), [state.clipboard]);
	return (
		<div class="wp-pane-body">
			<div class="wm-caption">The text field the plugin types into (field.*). Edit it here; the selection is what field.selection() reads.</div>
			<textarea class="st-textarea" rows={5} value={text} onInput={(e) => setText((e.target as HTMLTextAreaElement).value)} onSelect={(e) => {
				const t = e.target as HTMLTextAreaElement;
				own.current = true;
				void post('field', { text: t.value, cursor: t.selectionEnd, selection: t.selectionStart < t.selectionEnd ? [t.selectionStart, t.selectionEnd] : null });
			}} onBlur={(e) => {
				const t = e.target as HTMLTextAreaElement;
				own.current = true;
				void post('field', { text: t.value, cursor: t.selectionEnd, selection: null });
			}} />
			<div class="wm-caption">Clipboard</div>
			<textarea class="st-textarea" rows={2} value={clip} onInput={(e) => setClip((e.target as HTMLTextAreaElement).value)} onBlur={() => void post('clipboard', { text: clip })} />
		</div>
	);
}

function CallsPane({ state }: { state: State }) {
	return (
		<div class="wp-pane-body">
			{state.calls.length === 0 && <div class="wm-caption">No host calls yet.</div>}
			{state.calls.slice().reverse().map((c, i) => (
				<div key={i} class="wp-call">
					<div><b>{c.op}</b> <span class={`wp-source wp-source-${c.source}`}>{c.source}</span> <span class="wm-caption">{c.millis} ms</span></div>
					<div class="wp-mono wm-caption">{JSON.stringify(c.args)}</div>
					<div class="wp-mono">→ {c.reply}</div>
				</div>
			))}
		</div>
	);
}

function Side({ state }: { state: State }) {
	const [tab, setTab] = useState<Tab>('console');
	return (
		<section class="wp-side">
			<div class="wm-w-tabs wp-tabs" role="tablist">
				{TABS.map(([t, label]) => <button key={t} type="button" role="tab" class={`st-chip ${t === tab ? 'st-chip-on' : ''}`} aria-selected={t === tab} onClick={() => setTab(t)}>{label}{t === 'console' && state.error ? ' •' : ''}</button>)}
			</div>
			{tab === 'console' && <ConsolePane state={state} />}
			{tab === 'events' && <EventsPane state={state} />}
			{tab === 'storage' && <StoragePane state={state} />}
			{tab === 'field' && <FieldPane state={state} />}
			{tab === 'calls' && <CallsPane state={state} />}
		</section>
	);
}

function Strip({ state }: { state: State }) {
	const last = state.console[state.console.length - 1];
	const text = state.error ? diagText(state.error) : last ? last.text.split('\n')[0] : '';
	return <div class={`wp-strip ${state.error ? 'wp-strip-error' : ''}`} title={text}>{text}</div>;
}

function setTheme(theme: string) {
	document.documentElement.dataset.theme = theme;
}

function App() {
	const [state, setState] = useState<State | null>(null);
	const [connected, setConnected] = useState(true);
	useEffect(() => {
		if (askedTheme === 'dark' || askedTheme === 'light') setTheme(askedTheme);
		const source = new EventSource(`/api/events?t=${encodeURIComponent(token)}`);
		let told = false;
		source.addEventListener('state', (e) => {
			const next = JSON.parse((e as MessageEvent).data) as State;
			setConnected(true);
			setState(next);
			// The plugin sees the theme the page shows: plugin.dark.
			if (!told && (askedTheme === 'dark' || askedTheme === 'light') && next.theme !== askedTheme) {
				told = true;
				void post('reload', { theme: askedTheme });
			}
			if (!askedTheme) setTheme(next.theme);
		});
		source.onerror = () => setConnected(false);
		return () => source.close();
	}, []);
	if (!state) return <div class="wp-wait">{connected ? 'Starting…' : 'wmpy is not answering. Is `wmpy preview` still running?'}</div>;
	const theme = document.documentElement.dataset.theme ?? state.theme;
	const usage = state.usage.find((u) => u.phase === 'render');
	if (embed) {
		return (
			<div class="wp-app wp-embed">
				{!connected && <div class="wp-strip wp-strip-error">Disconnected from wmpy; retrying…</div>}
				<Panel state={state} />
				<Strip state={state} />
			</div>
		);
	}
	return (
		<div class="wp-app">
			<header class="wp-top">
				<b>{state.plugin.name}</b>
				<span class="wm-caption">{state.plugin.id} {state.plugin.version} · API {state.plugin.api}</span>
				<span class={`wp-status wp-status-${state.status}`}>{state.status === 'ready' ? 'ready' : state.status === 'failed' ? 'failed' : 'no panel'}</span>
				{usage && <span class="wm-caption">{S.usage(usage.steps.toLocaleString(), usage.stepsLimit.toLocaleString())}</span>}
				<span style="flex:1" />
				{!connected && <span class="wm-error-text">disconnected</span>}
				<button type="button" class="st-btn st-btn-sm st-btn-ghost" onClick={() => { const t = theme === 'dark' ? 'light' : 'dark'; setTheme(t); void post('reload', { theme: t }); }}>{theme === 'dark' ? 'Light' : 'Dark'}</button>
				<button type="button" class="st-btn st-btn-sm" onClick={() => void post('reload', {})}>Reload</button>
			</header>
			<main class="wp-main">
				<section class="wp-phone"><Panel state={state} /></section>
				<Side state={state} />
			</main>
		</div>
	);
}

render(<App />, document.getElementById('app')!);

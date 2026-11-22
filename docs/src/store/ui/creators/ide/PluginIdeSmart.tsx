/**
 * The editor's knowledge of the keyboard, on the web (PluginIdeHelp.kt,
 * PluginIdeTry.kt and PluginIdeManifestFields.kt): the strip that explains
 * the call being typed, the one-tap fix for the problem at the caret, the Try
 * pane that calls a provider or a selection action as the keyboard would, and
 * the form rows for plugin.json's providers, actions and settings.
 */
import type { ComponentChildren } from 'preact';
import { useEffect, useMemo, useState } from 'preact/hooks';
import * as Py from '../../../lib/python';
import type { PyDiagnostic, PyFix, PyHost } from '../../../lib/python';
import { tryOnce, type PluginIdentity, type TryOutcome } from '../../../lib/plugin-runtime';
import { PERMISSIONS, PACKAGE_API, type PluginManifest } from './ide-store';
import * as E from './edits';
import { S } from './strings';

// ---------------------------------------------------------------------------
// Help under the code
// ---------------------------------------------------------------------------

export type IdeHelp =
	| { kind: 'call'; signature: Py.PySignature }
	| { kind: 'name'; hover: Py.PyHover }
	| { kind: 'api'; entry: Py.PyApiEntry };

/** The call being typed, else the name at the caret, else the API entry around it. */
export function helpAt(text: string, caret: number, host: PyHost | null): IdeHelp | null {
	const signature = Py.signature(text, caret, host);
	if (signature) return { kind: 'call', signature };
	const hover = Py.hover(text, caret, host) ?? (caret > 0 ? Py.hover(text, caret - 1, host) : null);
	if (hover) return { kind: 'name', hover };
	const entry = Py.apiAt(text, caret);
	return entry ? { kind: 'api', entry } : null;
}

/** The strip under the code. A click opens the doc whole. */
export function HelpStrip({ help }: { help: IdeHelp }) {
	const key = help.kind === 'call' ? help.signature.label : help.kind === 'name' ? help.hover.title : help.entry.path;
	const [open, setOpen] = useState(false);
	useEffect(() => setOpen(false), [key]);
	let head;
	let hint: string | null = null;
	let doc: string;
	if (help.kind === 'call') {
		const s = help.signature;
		const active = s.active !== null ? s.params[s.active] : undefined;
		head = active ? (
			<>
				{s.label.substring(0, active[0])}
				<span class="wm-sig-active">{s.label.substring(active[0], active[1])}</span>
				{s.label.substring(active[1])}
			</>
		) : s.label;
		hint = s.paramDoc;
		doc = s.doc;
		const name = s.active !== null ? s.names[s.active] : undefined;
		if (hint && name) {
			return (
				<div class={`wm-doc ${open ? 'wm-doc-open' : ''}`} role="button" aria-label={open ? S.docLess : S.docMore} onClick={() => setOpen(!open)}>
					<div class="wm-doc-signature">{head}</div>
					<div class="wm-sig-param"><code>{name}</code>{hint}</div>
					{doc && <div class="wm-doc-body">{doc}</div>}
				</div>
			);
		}
	} else if (help.kind === 'name') {
		head = help.hover.title;
		doc = help.hover.doc;
	} else {
		const e = help.entry;
		head = e.signature ? (e.parent ? `${e.parent}.${e.signature}` : e.signature) : e.path;
		doc = e.doc;
	}
	return (
		<div class={`wm-doc ${open ? 'wm-doc-open' : ''}`} role="button" aria-label={open ? S.docLess : S.docMore} onClick={() => setOpen(!open)}>
			<div class="wm-doc-signature">{head}</div>
			{doc && <div class="wm-doc-body">{doc}</div>}
		</div>
	);
}

// ---------------------------------------------------------------------------
// Fixes
// ---------------------------------------------------------------------------

const RANK = { ERROR: 0, WARNING: 1, INFO: 2 };

/** The first diagnostic with a fix that covers [caret], errors first. */
export function fixableAt(diagnostics: PyDiagnostic[], caret: number): PyDiagnostic | null {
	return diagnostics.filter((d) => d.fix && caret >= d.start && caret <= d.end).sort((a, b) => RANK[a.severity] - RANK[b.severity])[0] ?? null;
}

/** What pressing Fix does, as the button says it. */
export function fixLabel(fix: PyFix): string {
	switch (fix.kind) {
		case 'replace': return S.fixReplace(fix.text.replace(/^['"]|['"]$/g, ''));
		case 'insert': return fix.text.trimStart().startsWith('@') ? S.fixStub : S.fixImport;
		case 'permission': return S.fixPermission(fix.value);
		case 'host': return S.fixHost(fix.value);
		case 'provider': return S.fixProvider;
		case 'action': return S.fixAction;
		case 'keyword': return S.fixKeyword(fix.value);
		case 'setting': return S.fixSetting(fix.value);
	}
}

/** The change a fix makes to the script, or null for one that changes plugin.json. */
export function fixEdit(d: PyDiagnostic, caret: number): E.CodeTextEdit | null {
	const fix = d.fix;
	if (!fix) return null;
	if (fix.kind === 'replace') return { range: { start: d.start, end: d.end }, text: fix.text, selection: E.rangeAt(d.start + fix.text.length) };
	if (fix.kind === 'insert') {
		const stub = fix.text.trimStart().startsWith('@');
		const after = stub ? fix.at + fix.text.length : caret >= fix.at ? caret + fix.text.length : caret;
		return { range: { start: fix.at, end: fix.at }, text: fix.text, selection: E.rangeAt(after) };
	}
	return null;
}

const titled = (key: string) => key.replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase());

/** [manifest] with [fix] made, or null for a fix that changes the script. */
export function manifestWith(manifest: PluginManifest, fix: PyFix): PluginManifest | null {
	switch (fix.kind) {
		case 'permission': {
			const wanted = [...new Set([...manifest.permissions, fix.value])];
			return { ...manifest, permissions: [...PERMISSIONS.filter((p) => wanted.includes(p)), ...wanted.filter((p) => !PERMISSIONS.includes(p))], apiVersion: Math.max(manifest.apiVersion, PACKAGE_API) };
		}
		case 'host': return { ...manifest, hosts: [...new Set([...(manifest.hosts ?? []), fix.value])] };
		case 'provider': return { ...manifest, providers: [...(manifest.providers ?? []), fix.id === fix.tool ? { tool: fix.tool } : { tool: fix.tool, id: fix.id }] };
		case 'action': return { ...manifest, actions: [...(manifest.actions ?? []), { id: fix.value, name: titled(fix.value) }] };
		case 'keyword': return { ...manifest, tool: { ...(manifest.tool ?? {}), keyword: fix.value, chip: true } };
		case 'setting': return { ...manifest, settings: [...(manifest.settings ?? []), { key: fix.value, type: 'text', label: titled(fix.value) }] };
		default: return null;
	}
}

/** The problem at the caret in words, and the button that settles it. */
export function FixStrip({ diagnostic, onFix }: { diagnostic: PyDiagnostic; onFix: () => void }) {
	if (!diagnostic.fix) return null;
	return (
		<div class="wm-fix">
			<span class={`wm-fix-text wm-fix-${diagnostic.severity.toLowerCase()}`} title={Py.diagnosticText(diagnostic)}>{Py.diagnosticText(diagnostic)}</span>
			<button type="button" class="st-btn st-btn-sm st-btn-ghost" onClick={onFix}>{fixLabel(diagnostic.fix)}</button>
		</div>
	);
}

// ---------------------------------------------------------------------------
// The Try pane
// ---------------------------------------------------------------------------

type TryTarget = { kind: 'provider'; tool: string; id: string; name: string; key: string } | { kind: 'action'; id: string; name: string; key: string };

function targetsOf(m: PluginManifest): TryTarget[] {
	return [
		...(m.providers ?? []).map((p): TryTarget => {
			const id = p.id?.trim() || p.tool;
			return { kind: 'provider', tool: p.tool, id, name: p.name?.trim() || id, key: `provider:${p.tool}/${id}` };
		}),
		...(m.actions ?? []).map((a): TryTarget => ({ kind: 'action', id: a.id, name: a.name || a.id, key: a.id })),
	];
}

const sampleOf = (t: TryTarget) => (t.kind === 'provider' ? Py.providerTools().find((x) => x.tool === t.tool)?.sample ?? '{}' : 'Hello, world');

/** The reply inside the prelude's `{"value": …}` envelope. */
function replyOf(value: string | null): unknown {
	if (value === null) return null;
	try {
		const parsed = JSON.parse(value) as { value?: unknown };
		return parsed && typeof parsed === 'object' && 'value' in parsed ? parsed.value : parsed;
	} catch {
		return value;
	}
}

/** How much of a reply the list [tool] would read: a rough count by the shapes the providers guide gives. */
function verdictOf(tool: string, reply: unknown, request: string): string {
	if (reply === null || reply === undefined) return S.tryNothing;
	const list = (v: unknown, key: string): unknown[] => (Array.isArray(v) ? v : v && typeof v === 'object' && Array.isArray((v as Record<string, unknown>)[key]) ? ((v as Record<string, unknown>)[key] as unknown[]) : []);
	const hasUrl = (item: unknown, ...keys: string[]) => !!item && typeof item === 'object' && keys.some((k) => /^https?:\/\//i.test(String((item as Record<string, unknown>)[k] ?? '')));
	const text = (v: unknown) => (typeof v === 'string' ? v : v && typeof v === 'object' ? String((v as Record<string, unknown>).text ?? '') : '');
	let images = false;
	try {
		images = (JSON.parse(request) as { kind?: string }).kind === 'images';
	} catch {
		/* not JSON */
	}
	let n: number | null;
	switch (tool) {
		case 'search': n = images ? list(reply, 'results').filter((i) => hasUrl(i, 'image', 'url')).length : list(reply, 'results').filter((i) => hasUrl(i, 'url')).length; break;
		case 'gif':
		case 'sticker': n = list(reply, 'results').filter((i) => hasUrl(i, 'url')).length; break;
		case 'dictionary':
		case 'grammar': n = list(reply, tool === 'dictionary' ? 'entries' : 'matches').length; break;
		case 'synonyms': n = Array.isArray(reply) ? reply.length : list(reply, 'groups').length; break;
		case 'translate':
		case 'ocr':
		case 'voice': n = text(reply).trim() ? 1 : 0; break;
		case 'currency_fiat': n = reply && typeof reply === 'object' && (reply as { rates?: object }).rates ? Object.keys((reply as { rates: object }).rates).length : 0; break;
		case 'currency_crypto': n = reply && typeof reply === 'object' ? Object.keys(reply).length : 0; break;
		default: n = null;
	}
	if (n === null) return S.tryReturned;
	return n === 0 ? S.tryUnread : S.tryRead(n);
}

/**
 * Calls the draft's providers and selection actions as the keyboard would: a
 * provider with the request its list sends, an action with selected text.
 * Shows what came back, whether the list can read it, and what was printed.
 */
export function TryPane({ manifest, script, identity, storage }: { manifest: PluginManifest; script: () => string; identity: () => PluginIdentity; storage: () => Record<string, string> | null }) {
	const targets = useMemo(() => targetsOf(manifest), [manifest]);
	const [chosenKey, setChosenKey] = useState<string | null>(null);
	const chosen = targets.find((t) => t.key === chosenKey) ?? targets[0];
	const [inputs, setInputs] = useState<Record<string, string>>({});
	const [outcome, setOutcome] = useState<{ key: string; result: TryOutcome } | null>(null);
	if (!chosen) return <div class="wm-pane-empty">{S.tryEmpty}</div>;
	const input = inputs[chosen.key] ?? sampleOf(chosen);
	const run = () => {
		if (chosen.kind === 'provider') {
			try {
				const parsed: unknown = JSON.parse(input);
				if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error();
			} catch {
				return setOutcome({ key: chosen.key, result: { value: null, error: S.tryBadJson, line: null, log: [], millis: 0 } });
			}
		}
		setOutcome({ key: chosen.key, result: tryOnce(identity(), script(), chosen.key, input, storage()) });
	};
	const result = outcome?.key === chosen.key ? outcome.result : null;
	const reply = result && !result.error ? (chosen.kind === 'provider' ? replyOf(result.value) : result.value) : null;
	return (
		<div class="wm-pane wm-try" style="display:flex;flex-direction:column;gap:6px">
			<div class="wm-w-row" style="flex-wrap:wrap">
				{targets.map((t) => (
					<button key={t.key} type="button" class={`st-chip ${t.key === chosen.key ? 'st-chip-on' : ''}`} aria-pressed={t.key === chosen.key} onClick={() => setChosenKey(t.key)}>
						{t.kind === 'provider' ? `${t.tool} · ${t.name}` : t.name}
					</button>
				))}
			</div>
			<label class="st-field">
				<span>{chosen.kind === 'provider' ? S.tryRequest : S.tryText}</span>
				<textarea class="st-input" rows={chosen.kind === 'provider' ? 5 : 2} spellcheck={false} value={input} onInput={(e) => setInputs({ ...inputs, [chosen.key]: (e.target as HTMLTextAreaElement).value })} />
			</label>
			<div class="wm-w-row">
				<button type="button" class="st-btn st-btn-sm st-btn-primary" onClick={run}>{S.tryRun}</button>
				{result && <span class="wm-caption">{S.tryTook(result.millis)}</span>}
			</div>
			{result?.error && <div class="wm-error-text">{result.line ? `${result.error} (line ${result.line})` : result.error}</div>}
			{result && !result.error && (
				<>
					{chosen.kind === 'provider' && <div class="wm-label">{verdictOf(chosen.tool, reply, input)}</div>}
					<div class="wm-caption">{S.tryReply}</div>
					<pre>{chosen.kind === 'provider' ? JSON.stringify(reply, null, 2) : reply ?? 'None'}</pre>
				</>
			)}
			{result && result.log.length > 0 && (
				<>
					<div class="wm-caption">{S.consoleLabel}</div>
					<pre>{result.log.join('\n')}</pre>
				</>
			)}
		</div>
	);
}

// ---------------------------------------------------------------------------
// plugin.json's providers, actions and settings
// ---------------------------------------------------------------------------

const MAX_PROVIDERS = 8;
const MAX_ACTIONS = 16;
const MAX_SETTINGS = 24;
const SETTING_TYPES = ['text', 'switch', 'number', 'choice', 'host', 'secret'] as const;

function Group({ title, canAdd, onAdd, children }: { title: string; canAdd: boolean; onAdd: () => void; children: ComponentChildren }) {
	return (
		<div class="wm-rows">
			<div class="wm-row-between">
				<span class="wm-caption">{title}</span>
				<button type="button" class="st-btn st-btn-sm st-btn-ghost" disabled={!canAdd} onClick={onAdd}>{S.detailsAdd}</button>
			</div>
			{children}
		</div>
	);
}

const val = (e: Event) => (e.target as HTMLInputElement).value;

/** Providers, selection actions and the settings page, as rows of plugin.json. */
export function ManifestRows({ manifest, onChange }: { manifest: PluginManifest; onChange: (m: PluginManifest) => void }) {
	const api5 = manifest.apiVersion >= PACKAGE_API;
	const tools = Py.providerTools();
	const providers = manifest.providers ?? [];
	const actions = manifest.actions ?? [];
	const settings = manifest.settings ?? [];
	const setAt = <T,>(list: T[], i: number, item: T) => list.map((x, j) => (j === i ? item : x));
	const without = <T,>(list: T[], i: number) => list.filter((_, j) => j !== i);
	return (
		<>
			{api5 && (
				<label class="wm-w-toggle">
					<input type="checkbox" checked={manifest.panel !== false} onChange={(e) => onChange({ ...manifest, panel: (e.target as HTMLInputElement).checked })} />
					<span>{S.detailsPanel}</span>
				</label>
			)}
			{api5 && (
				<Group title={S.detailsProviders} canAdd={providers.length < MAX_PROVIDERS} onAdd={() => {
					const taken = new Set(providers.map((p) => p.tool));
					const tool = tools.map((t) => t.tool).find((t) => !taken.has(t)) ?? 'search';
					onChange({ ...manifest, providers: [...providers, { tool }] });
				}}>
					{providers.map((p, i) => (
						<div key={i} class="wm-rows-item">
							<select class="st-input" value={p.tool} onChange={(e) => onChange({ ...manifest, providers: setAt(providers, i, { ...p, tool: val(e), images: val(e) === 'search' ? p.images : undefined }) })}>
								{(tools.length ? tools.map((t) => [t.tool, `${t.label} (${t.tool})`]) : [[p.tool, p.tool]]).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
							</select>
							<input class="st-input" style="font-family:var(--wmc-mono)" placeholder={p.tool} aria-label={S.detailsProviderId} value={p.id ?? ''} onInput={(e) => onChange({ ...manifest, providers: setAt(providers, i, { ...p, id: val(e) || undefined }) })} />
							<input class="st-input" placeholder={S.detailsProviderName} aria-label={S.detailsProviderName} value={p.name ?? ''} onInput={(e) => onChange({ ...manifest, providers: setAt(providers, i, { ...p, name: val(e) || undefined }) })} />
							{p.tool === 'search' && (
								<label class="wm-w-toggle"><input type="checkbox" checked={!!p.images} onChange={(e) => onChange({ ...manifest, providers: setAt(providers, i, { ...p, images: (e.target as HTMLInputElement).checked || undefined }) })} /><span>{S.detailsProviderImages}</span></label>
							)}
							<button type="button" class="st-btn st-btn-sm st-btn-ghost" aria-label={S.detailsRemove} onClick={() => onChange({ ...manifest, providers: without(providers, i) })}>✕</button>
						</div>
					))}
				</Group>
			)}
			<Group title={S.detailsActions} canAdd={actions.length < MAX_ACTIONS} onAdd={() => {
				let n = actions.length + 1;
				while (actions.some((a) => a.id === `action${n}`)) n++;
				onChange({ ...manifest, actions: [...actions, { id: `action${n}`, name: `Action ${n}` }] });
			}}>
				{actions.map((a, i) => (
					<div key={i} class="wm-rows-item">
						<input class="st-input" style="font-family:var(--wmc-mono)" aria-label={S.detailsActionId} placeholder={S.detailsActionId} value={a.id} onInput={(e) => onChange({ ...manifest, actions: setAt(actions, i, { ...a, id: val(e) }) })} />
						<input class="st-input" aria-label={S.detailsActionName} placeholder={S.detailsActionName} value={a.name} onInput={(e) => onChange({ ...manifest, actions: setAt(actions, i, { ...a, name: val(e) }) })} />
						<button type="button" class="st-btn st-btn-sm st-btn-ghost" aria-label={S.detailsRemove} onClick={() => onChange({ ...manifest, actions: without(actions, i) })}>✕</button>
					</div>
				))}
			</Group>
			{api5 && (
				<Group title={S.detailsSettings} canAdd={settings.length < MAX_SETTINGS} onAdd={() => {
					let n = settings.length + 1;
					while (settings.some((s) => s.key === `setting${n}`)) n++;
					onChange({ ...manifest, settings: [...settings, { key: `setting${n}`, type: 'text', label: `Setting ${n}` }] });
				}}>
					{settings.map((s, i) => (
						<div key={i} class="wm-rows-item">
							<input class="st-input" style="font-family:var(--wmc-mono)" aria-label={S.detailsSettingKey} placeholder={S.detailsSettingKey} value={s.key} onInput={(e) => onChange({ ...manifest, settings: setAt(settings, i, { ...s, key: val(e) }) })} />
							<select class="st-input" value={s.type} onChange={(e) => onChange({ ...manifest, settings: setAt(settings, i, { ...s, type: val(e) }) })}>
								{SETTING_TYPES.map((t) => <option key={t} value={t}>{S.detailsSettingTypes[t]}</option>)}
							</select>
							<input class="st-input" aria-label={S.detailsSettingLabel} placeholder={S.detailsSettingLabel} value={s.label ?? ''} onInput={(e) => onChange({ ...manifest, settings: setAt(settings, i, { ...s, label: val(e) }) })} />
							{s.type === 'choice' && (
								<input class="st-input" aria-label={S.detailsSettingOptions} placeholder={S.detailsSettingOptions} value={(s.options ?? []).join(', ')} onInput={(e) => onChange({ ...manifest, settings: setAt(settings, i, { ...s, options: val(e).split(',').map((o) => o.trim()).filter(Boolean) }) })} />
							)}
							<button type="button" class="st-btn st-btn-sm st-btn-ghost" aria-label={S.detailsRemove} onClick={() => onChange({ ...manifest, settings: without(settings, i) })}>✕</button>
						</div>
					))}
				</Group>
			)}
		</>
	);
}

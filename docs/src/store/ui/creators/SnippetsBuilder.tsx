/** Snippet pack builder: writes the app's own .wmsnippets.json (format v2). */
import { useMemo, useState } from 'preact/hooks';
import { readSnippets, SNIPPETS_FORMAT, type Snippet, type SnippetFolder } from '../../lib/payloads';
import { slugify } from '../../lib/util';
import { CopyButton, Notice } from '../common';
import { IconDownload, IconPlus, IconTrash, IconWarn } from '../icons';
import { CommunitySubmit } from './CommunitySubmit';
import { Area, DropZone, ExportPanel, saveJson, Section, Select, Tags, Text, Toggle, Num, useDraft } from './shared';

interface Draft {
	name: string;
	snippets: Snippet[];
	folders: SnippetFolder[];
	nextId: number;
}

function blank(): Draft {
	return { name: 'my-snippets', snippets: [], folders: [], nextId: 1 };
}

function build(d: Draft) {
	const clean = (s: Snippet) => {
		const o: Record<string, unknown> = { id: s.id, label: s.label, text: s.text, createdAt: s.createdAt ?? 0, trigger: s.trigger || null };
		if (s.aliases?.length) o.aliases = s.aliases;
		if (s.propagateCase) o.propagateCase = true;
		if (s.uppercaseStyle && s.uppercaseStyle !== 'capitalize') o.uppercaseStyle = s.uppercaseStyle;
		if (s.triggerPattern) o.triggerPattern = s.triggerPattern;
		if (s.triggerWords) o.triggerWords = s.triggerWords;
		if (s.confirm) o.confirm = true;
		if (s.folderId) o.folderId = s.folderId;
		if (s.alternates?.length) o.alternates = s.alternates;
		if (s.children?.length) o.children = s.children;
		if (s.tags?.length) o.tags = s.tags;
		if (s.multiExpand && s.multiExpand !== 'default') o.multiExpand = s.multiExpand;
		return o;
	};
	const used = new Set(d.snippets.map((s) => s.folderId).filter(Boolean));
	return {
		format: SNIPPETS_FORMAT,
		version: 2,
		appVersion: 0,
		appVersionName: '',
		snippets: d.snippets.map(clean),
		folders: d.folders.filter((f) => used.has(f.id)).map((f) => ({ id: f.id, name: f.name, ...(f.enabled === false ? { enabled: false } : {}), ...(f.icon ? { icon: f.icon } : {}) })),
	};
}

export function SnippetsBuilder() {
	const [d, setD, reset] = useDraft<Draft>('snippets', blank);
	const [sel, setSel] = useState<number | null>(null);
	const [err, setErr] = useState<string | null>(null);
	const out = useMemo(() => build(d), [d]);
	const json = useMemo(() => JSON.stringify(out, null, 2), [out]);
	const cur = d.snippets.find((s) => s.id === sel) ?? null;
	const patch = (id: number, p: Partial<Snippet>) => setD({ ...d, snippets: d.snippets.map((s) => (s.id === id ? { ...s, ...p } : s)) });
	const add = () => {
		const s: Snippet = { id: d.nextId, label: '', text: '', trigger: '', createdAt: Date.now() };
		setD({ ...d, snippets: [...d.snippets, s], nextId: d.nextId + 1 });
		setSel(s.id);
	};
	const problems: string[] = [];
	if (d.snippets.length > 500) problems.push('More than 500 snippets; the app imports the first 500.');
	for (const s of d.snippets) {
		if (!s.text.trim()) problems.push(`"${s.label || s.id}" has no text; the app drops it.`);
		if (s.text.length > 20000) problems.push(`"${s.label}" is over 20 000 characters.`);
		if (s.triggerPattern) {
			try { new RegExp(s.triggerPattern); } catch { problems.push(`"${s.label}": the pattern does not compile.`); }
		}
	}
	const dupes = d.snippets.map((s) => s.trigger?.trim()).filter(Boolean);
	for (const t of new Set(dupes.filter((t, i) => dupes.indexOf(t) !== i))) problems.push(`Trigger "${t}" is used more than once; the first wins.`);

	return (
		<div class="st-creator-layout">
			<div style="display:flex;flex-direction:column;gap:0.8rem">
				<div>
					<span class="st-kicker">Snippet pack</span>
					<h1 style="font-size:1.5rem;font-weight:800">Triggers and expansions</h1>
					<p class="st-muted st-small" style="max-width:62ch;margin-top:0.3rem">
						A snippet expands when its trigger is typed as a word (or matches a pattern), or waits in the Snippets panel when it has none. Same file the app exports from Settings › Snippets.
					</p>
				</div>
				<Section title="Start from a file" open={false}>
					<DropZone accept=".json" multiple={false} onFiles={([f]) => { if (!f) return; try { const r = readSnippets(new TextDecoder().decode(f.bytes)); const max = Math.max(0, ...r.envelope.snippets.map((s) => s.id)); setD({ name: f.name.replace(/\.wmsnippets\.json$/, ''), snippets: r.envelope.snippets, folders: r.envelope.folders ?? [], nextId: max + 1 }); setErr(null); } catch (e) { setErr((e as Error).message); } }}>Drop a .wmsnippets.json to edit it</DropZone>
					{err && <Notice kind="err" icon={<IconWarn />}>{err}</Notice>}
				</Section>
				<Section title={`Snippets (${d.snippets.length})`}>
					<div class="st-entry-list">
						{d.snippets.map((s) => (
							<div class="st-entry" key={s.id} aria-selected={sel === s.id}>
								<span class="st-tag" style="font-family:var(--sl-font-mono)">{s.triggerPattern ? `/${s.triggerPattern}/` : s.trigger || '—'}</span>
								<button class="st-entry-text" style="background:none;border:0;padding:0;text-align:left;cursor:pointer" onClick={() => setSel(sel === s.id ? null : s.id)}>
									<span class="st-entry-name">{s.label || s.text.split('\n')[0] || '(empty)'}</span>
									<span class="st-entry-sub">{s.text.slice(0, 80)}{s.confirm ? ' · asks first' : ''}{s.folderId ? ` · ${d.folders.find((f) => f.id === s.folderId)?.name ?? ''}` : ''}</span>
								</button>
								<span class="st-entry-actions"><button class="st-btn st-btn-ghost st-btn-icon st-btn-sm" aria-label="Remove" onClick={() => { setD({ ...d, snippets: d.snippets.filter((x) => x.id !== s.id) }); if (sel === s.id) setSel(null); }}><IconTrash /></button></span>
							</div>
						))}
					</div>
					<button class="st-btn st-btn-sm" onClick={add}><IconPlus /> Add snippet</button>
				</Section>
				{cur && (
					<Section title={`Snippet: ${cur.label || cur.id}`}>
						<div class="st-grid2">
							<Text label="Trigger" mono value={cur.trigger ?? ''} onInput={(v) => patch(cur.id, { trigger: v })} placeholder="omw" help="Typed as a whole word. May start with punctuation (:shrug) or contain spaces." />
							<Text label="Label" value={cur.label} onInput={(v) => patch(cur.id, { label: v })} placeholder="On my way!" help="Blank: the first line of the text." />
						</div>
						<Area label="Expands to" value={cur.text} onInput={(v) => patch(cur.id, { text: v })} rows={3} />
						<div class="st-grid2">
							<Tags label="Aliases" value={cur.aliases ?? []} onInput={(v) => patch(cur.id, { aliases: v })} help="Extra exact spellings." />
							<Tags label="Alternates" value={cur.alternates ?? []} onInput={(v) => patch(cur.id, { alternates: v })} help="Other expansions; the text above is offered first." />
							<Text label="Pattern (regex)" mono value={cur.triggerPattern ?? ''} onInput={(v) => patch(cur.id, { triggerPattern: v || null })} placeholder="^(\\d+)c$" help="Over the trailing words; $1…$9 substitute into the text. A word trigger wins if both are set." />
							<Num label="Words the pattern may span" value={cur.triggerWords ?? 0} onInput={(v) => patch(cur.id, { triggerWords: v ?? 0 })} min={0} max={8} help="0 = the app's default." />
							<Select label="Folder" value={String(cur.folderId ?? 0)} onInput={(v) => patch(cur.id, { folderId: Number(v) })} options={[['0', '(none)'], ...d.folders.map((f) => [String(f.id), f.name] as [string, string])]} />
							<Select label="Multiple expansions" value={cur.multiExpand ?? 'default'} onInput={(v) => patch(cur.id, { multiExpand: v })} options={[['default', 'App default'], ['chips_only', 'Offer chips only'], ['insert_first', 'Insert the first']]} />
							<Select label="Uppercase style" value={cur.uppercaseStyle ?? 'capitalize'} onInput={(v) => patch(cur.id, { uppercaseStyle: v })} options={[['capitalize', 'Capitalize'], ['capitalize_words', 'Capitalize words'], ['uppercase', 'UPPERCASE']]} help="When the trigger was typed in caps and case propagation is on." />
							<Tags label="Tags" value={cur.tags ?? []} onInput={(v) => patch(cur.id, { tags: v })} />
						</div>
						<Toggle label="Ask before expanding (offer a chip instead of rewriting)" value={!!cur.confirm} onInput={(v) => patch(cur.id, { confirm: v })} />
						<Toggle label="Propagate case from the trigger" value={!!cur.propagateCase} onInput={(v) => patch(cur.id, { propagateCase: v })} />
					</Section>
				)}
				<Section title={`Folders (${d.folders.length})`} open={d.folders.length > 0}>
					{d.folders.map((f) => (
						<div class="st-row" key={f.id} style="flex-wrap:nowrap">
							<input class="st-input" value={f.name} onInput={(e) => setD({ ...d, folders: d.folders.map((x) => (x.id === f.id ? { ...x, name: (e.target as HTMLInputElement).value } : x)) })} />
							<label class="st-switch"><input type="checkbox" checked={f.enabled !== false} onChange={(e) => setD({ ...d, folders: d.folders.map((x) => (x.id === f.id ? { ...x, enabled: (e.target as HTMLInputElement).checked } : x)) })} /> armed</label>
							<button class="st-btn st-btn-ghost st-btn-icon st-btn-sm" onClick={() => setD({ ...d, folders: d.folders.filter((x) => x.id !== f.id), snippets: d.snippets.map((s) => (s.folderId === f.id ? { ...s, folderId: 0 } : s)) })}><IconTrash /></button>
						</div>
					))}
					<button class="st-btn st-btn-sm" onClick={() => setD({ ...d, folders: [...d.folders, { id: d.nextId, name: `Folder ${d.folders.length + 1}`, enabled: true }], nextId: d.nextId + 1 })}><IconPlus /> Add folder</button>
				</Section>
			</div>
			<aside class="st-creator-side">
				<ExportPanel title="Export" json={out}>
					<Text label="File name" value={d.name} onInput={(v) => setD({ ...d, name: v })} />
					<button class="st-btn st-btn-primary" disabled={!d.snippets.length} onClick={() => saveJson(`${slugify(d.name) || 'snippets'}.wmsnippets.json`, out)}><IconDownload /> .wmsnippets.json</button>
					<CopyButton text={json} label="Copy JSON" class="st-btn" />
					<button class="st-btn st-btn-ghost st-btn-sm" onClick={() => { if (confirm('Discard this draft?')) { reset(); setSel(null); } }}>Start over</button>
				</ExportPanel>
				<CommunitySubmit kind="snippets" name={d.name} json={json} disabled={!d.snippets.length} />
				{problems.length > 0 && (
					<Notice kind="warn" icon={<IconWarn />}><ul style="padding-left:1rem">{problems.map((p, i) => <li key={i}>{p}</li>)}</ul></Notice>
				)}
				<div class="st-panel st-small st-muted">Ids are re-minted on import, so they only need to be unique inside this file. <code>children</code> links between snippets are file-local too.</div>
			</aside>
		</div>
	);
}

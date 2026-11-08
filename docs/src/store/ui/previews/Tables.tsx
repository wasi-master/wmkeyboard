/** Table-shaped previews: snippets, espanso, dictionaries, emoji keywords, vocabulary, plugins. */
import { useMemo, useState } from 'preact/hooks';
import { fmtBytes } from '../../lib/net';
import type { DictionaryRead, EmojiKeywordRow, EspansoMatch, PluginRead, SnippetEnvelope } from '../../lib/payloads';
import { IconSearch, IconShield } from '../icons';
import { Notice } from '../common';
import { Problems } from './Preview';

function Filter({ value, onInput, placeholder }: { value: string; onInput: (v: string) => void; placeholder: string }) {
	return (
		<div class="st-search" style="max-width:none">
			<IconSearch />
			<input class="st-input" type="search" placeholder={placeholder} value={value} onInput={(e) => onInput((e.target as HTMLInputElement).value)} />
		</div>
	);
}

export function SnippetPreview({ read }: { read: { envelope: SnippetEnvelope; problems: string[] } }) {
	const [q, setQ] = useState('');
	const { snippets, folders = [] } = read.envelope;
	const folderName = new Map(folders.map((f) => [f.id, f.name]));
	const rows = snippets.filter((s) => !q || [s.label, s.text, s.trigger ?? '', s.triggerPattern ?? '', ...(s.aliases ?? []), ...(s.tags ?? [])].join('\n').toLowerCase().includes(q.toLowerCase()));
	const auto = snippets.filter((s) => (s.trigger || s.triggerPattern) && !s.confirm).length;
	return (
		<>
			<Problems items={read.problems} />
			<div class="st-pv-pad">
				<div class="st-row" style="justify-content:space-between;margin-bottom:0.6rem">
					<span class="st-small st-muted">
						{snippets.length} snippet{snippets.length === 1 ? '' : 's'}{folders.length ? ` in ${folders.length} folder${folders.length === 1 ? '' : 's'}` : ''} · {auto} expand automatically, {snippets.length - auto} offer a chip or wait for the panel
					</span>
					<Filter value={q} onInput={setQ} placeholder="Filter snippets" />
				</div>
			</div>
			<div class="st-table-wrap">
				<table class="st-table">
					<thead><tr><th>Trigger</th><th>Label</th><th>Expands to</th><th>How</th></tr></thead>
					<tbody>
						{rows.map((s) => (
							<tr key={s.id}>
								<td class="mono">
									{s.triggerPattern ? <span title="regex pattern">/{s.triggerPattern}/</span> : s.trigger ?? <span class="st-muted">—</span>}
									{s.aliases?.length ? <div class="st-muted">{s.aliases.join(', ')}</div> : null}
								</td>
								<td>{s.label}{s.folderId && folderName.get(s.folderId) ? <div class="st-muted st-small">{folderName.get(s.folderId)}</div> : null}</td>
								<td style="white-space:pre-wrap;max-width:28rem">{s.text.length > 240 ? s.text.slice(0, 240) + '…' : s.text}{s.alternates?.length ? <div class="st-muted st-small">+{s.alternates.length} alternate{s.alternates.length === 1 ? '' : 's'}</div> : null}</td>
								<td class="st-muted st-small">{s.trigger || s.triggerPattern ? (s.confirm ? 'asks (chip)' : 'auto') : 'panel only'}{s.propagateCase ? ' · case' : ''}{s.tags?.length ? ` · ${s.tags.map((t) => '#' + t).join(' ')}` : ''}</td>
							</tr>
						))}
					</tbody>
				</table>
			</div>
		</>
	);
}

export function EspansoPreview({ read }: { read: { matches: EspansoMatch[]; problems: string[] } }) {
	return (
		<>
			<Problems items={read.problems} />
			<div class="st-pv-pad st-small st-muted" style="padding-bottom:0">{read.matches.length} match{read.matches.length === 1 ? '' : 'es'}. The app converts these to snippets on install; variables and forms are dropped.</div>
			<div class="st-table-wrap">
				<table class="st-table">
					<thead><tr><th>Trigger</th><th>Replace</th></tr></thead>
					<tbody>
						{read.matches.map((m, i) => (
							<tr key={i}>
								<td class="mono">{m.regex ? `/${m.regex}/` : (m.triggers ?? [m.trigger]).filter(Boolean).join(', ')}</td>
								<td style="white-space:pre-wrap">{String(m.replace ?? '')}</td>
							</tr>
						))}
					</tbody>
				</table>
			</div>
		</>
	);
}

export function DictionaryPreview({ read }: { read: DictionaryRead }) {
	const [q, setQ] = useState('');
	const [mode, setMode] = useState<'cloud' | 'table'>('cloud');
	const rows = useMemo(() => read.words.filter((w) => !q || w.word.toLowerCase().includes(q.toLowerCase())), [read, q]);
	const hasFreq = read.words.some((w) => w.freq !== 1);
	return (
		<>
			<div class="st-pv-pad">
				<div class="st-row" style="justify-content:space-between;margin-bottom:0.4rem">
					<span class="st-small st-muted">
						{read.total.toLocaleString()} word{read.total === 1 ? '' : 's'}{read.skipped ? ` · ${read.skipped} malformed line${read.skipped === 1 ? '' : 's'} skipped` : ''}{read.truncated ? ` · showing the first ${read.words.length.toLocaleString()}` : ''}{hasFreq ? '' : ' · no frequencies (all 1)'}
					</span>
					<div class="st-row">
						<Filter value={q} onInput={setQ} placeholder="Find a word" />
						<div class="st-seg"><button aria-pressed={mode === 'cloud'} onClick={() => setMode('cloud')}>Cloud</button><button aria-pressed={mode === 'table'} onClick={() => setMode('table')}>Table</button></div>
					</div>
				</div>
				{read.comments.length > 0 && <p class="st-small st-muted" style="white-space:pre-wrap">{read.comments.join('\n')}</p>}
			</div>
			{mode === 'cloud' ? (
				<div class="st-word-cloud" style="max-height:24rem;overflow:auto">
					{rows.slice(0, 1500).map((w, i) => <span key={i} title={hasFreq ? `frequency ${w.freq}` : undefined}>{w.word}</span>)}
				</div>
			) : (
				<div class="st-table-wrap">
					<table class="st-table">
						<thead><tr><th>Word</th>{hasFreq && <th>Frequency</th>}</tr></thead>
						<tbody>{rows.slice(0, 3000).map((w, i) => <tr key={i}><td>{w.word}</td>{hasFreq && <td class="mono">{w.freq}</td>}</tr>)}</tbody>
					</table>
				</div>
			)}
		</>
	);
}

export function EmojiKeywordPreview({ read }: { read: { rows: EmojiKeywordRow[]; total: number; truncated: boolean } }) {
	const [q, setQ] = useState('');
	const rows = read.rows.filter((r) => !q || r.keywords.some((k) => k.includes(q.toLowerCase())) || r.name.toLowerCase().includes(q.toLowerCase()));
	return (
		<>
			<div class="st-pv-pad">
				<div class="st-row" style="justify-content:space-between">
					<span class="st-small st-muted">{read.total.toLocaleString()} emoji{read.truncated ? ` · showing ${read.rows.length}` : ''}</span>
					<Filter value={q} onInput={setQ} placeholder="Search a keyword" />
				</div>
			</div>
			<div class="st-table-wrap">
				<table class="st-table">
					<thead><tr><th>Emoji</th><th>Keywords</th><th>Name</th></tr></thead>
					<tbody>{rows.slice(0, 1000).map((r, i) => <tr key={i}><td style="font-size:1.4rem">{r.emoji}</td><td>{r.keywords.join(', ')}</td><td class="st-muted">{r.name}</td></tr>)}</tbody>
				</table>
			</div>
		</>
	);
}

export function VocabularyPreview({ read }: { read: { pack: Record<string, unknown>; words: { word: string; pos: string[]; senses: string[] }[]; total: number; problems: string[] } }) {
	const [q, setQ] = useState('');
	const rows = read.words.filter((w) => !q || w.word.toLowerCase().includes(q.toLowerCase()) || w.senses.some((s) => s.toLowerCase().includes(q.toLowerCase())));
	return (
		<>
			<Problems items={read.problems} />
			<div class="st-pv-pad">
				<div class="st-row" style="justify-content:space-between">
					<span class="st-small st-muted">{String(read.pack.name ?? 'Vocabulary pack')} · {read.total.toLocaleString()} words{read.total > read.words.length ? ` · showing ${read.words.length}` : ''}{read.pack.langId ? ` · ${read.pack.langId}` : ''}</span>
					<Filter value={q} onInput={setQ} placeholder="Search words or definitions" />
				</div>
			</div>
			<div class="st-table-wrap">
				<table class="st-table">
					<thead><tr><th>Word</th><th>Part of speech</th><th>Senses</th></tr></thead>
					<tbody>{rows.map((w, i) => <tr key={i}><td><b>{w.word}</b></td><td class="st-muted">{w.pos.join(', ')}</td><td>{w.senses.slice(0, 3).map((s, j) => <div key={j}>{s}</div>)}</td></tr>)}</tbody>
				</table>
			</div>
		</>
	);
}

export function PluginPreview({ read }: { read: PluginRead }) {
	const m = read.manifest;
	const textFiles = read.files.filter((f) => f.text != null);
	const [open, setOpen] = useState(textFiles.find((f) => f.name === m.entry)?.name ?? textFiles[0]?.name ?? '');
	const file = textFiles.find((f) => f.name === open);
	return (
		<>
			<Problems items={read.problems} />
			<div class="st-pv-pad">
				<Notice icon={<IconShield />} kind={m.permissions.length ? 'warn' : 'ok'}>
					<b>{m.permissions.length ? `Asks for: ${m.permissions.join(', ')}` : 'Asks for no permissions.'}</b>{' '}
					<span class="st-muted">Plugins run in a Python sandbox with no access to what you type, the clipboard or the network; <code>storage</code> is on-device only. Installed plugins start switched off.</span>
				</Notice>
				<dl class="st-kv" style="margin-top:0.9rem">
					<dt>Plugin id</dt><dd><code>{m.id}</code></dd>
					<dt>Version</dt><dd>{m.pluginVersion} <span class="st-muted">· API {m.apiVersion}</span></dd>
					{m.author && <><dt>Author</dt><dd>{m.author}</dd></>}
					{m.description && <><dt>About</dt><dd>{m.description}</dd></>}
					<dt>Entry</dt><dd><code>{m.entry}</code></dd>
					<dt>Files</dt><dd>{read.files.map((f) => `${f.name} (${fmtBytes(f.bytes)})`).join(', ')}</dd>
				</dl>
			</div>
			{textFiles.length > 0 && (
				<div style="border-top:1px solid var(--st-card-border)">
					<div class="st-file-tabs" role="tablist">
						{textFiles.map((f) => <button key={f.name} role="tab" aria-selected={open === f.name} onClick={() => setOpen(f.name)}>{f.name}</button>)}
					</div>
					<pre class="st-code"><code>{file?.text}</code></pre>
				</div>
			)}
		</>
	);
}

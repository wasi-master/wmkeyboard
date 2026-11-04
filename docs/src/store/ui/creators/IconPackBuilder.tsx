/** Icon pack builder: one SVG per slot, a .wmicons zip (pack.json + icons/<slot>.svg) out. */
import { useMemo, useState } from 'preact/hooks';
import { ICON_SLOT_GROUPS, ICON_SLOTS } from '../../lib/icon-slots';
import { readIconPack } from '../../lib/payloads';
import { slugify } from '../../lib/util';
import { writeZip } from '../../lib/zip';
import { Notice } from '../common';
import { IconDownload, IconTrash, IconWarn } from '../icons';
import { sanitizeSvg } from '../previews/Media';
import { CommunitySubmit } from './CommunitySubmit';
import { Area, DropZone, ExportPanel, idError, saveBytes, Section, Text, useDraft, type PickedFile } from './shared';
import { ICON_SLOT_IDS } from '../../lib/icon-slots';

interface Draft {
	id: string;
	name: string;
	author: string;
	description: string;
	version: string;
	/** slot id → svg text. Small enough to persist. */
	icons: Record<string, string>;
}

export function IconPackBuilder() {
	const [d, setD, reset] = useDraft<Draft>('icon_pack', () => ({ id: '', name: '', author: '', description: '', version: '1.0.0', icons: {} }));
	const [group, setGroup] = useState<string>(ICON_SLOT_GROUPS[0]);
	const [target, setTarget] = useState<string | null>(null);
	const [err, setErr] = useState<string | null>(null);
	const patch = (p: Partial<Draft>) => setD({ ...d, ...p });

	const addFiles = (picked: PickedFile[], forSlot?: string | null) => {
		const icons = { ...d.icons };
		let unmatched = 0;
		for (const f of picked) {
			if (!/\.svg$/i.test(f.name)) continue;
			const svg = new TextDecoder().decode(f.bytes);
			const slot = forSlot ?? f.name.replace(/\.svg$/i, '').toLowerCase();
			if (ICON_SLOT_IDS.has(slot)) icons[slot] = svg;
			else if (forSlot) icons[forSlot] = svg;
			else unmatched++;
		}
		setD({ ...d, icons });
		setErr(unmatched ? `${unmatched} file${unmatched === 1 ? '' : 's'} didn't match a slot by name (expected e.g. tool.emoji.svg, key.shift.svg). Click a slot to assign one directly.` : null);
	};

	const manifest = useMemo(() => ({
		format: 'wmkeyboard-icons',
		version: 1,
		appVersion: 0,
		appVersionName: '',
		pack: { id: d.id, name: d.name, author: d.author, description: d.description, version: d.version, slots: ICON_SLOTS.filter((s) => d.icons[s.id]).map((s) => s.id), createdAt: 0 },
	}), [d]);
	const filled = manifest.pack.slots.length;
	const slots = ICON_SLOTS.filter((s) => s.group === group);
	const bytes = Object.values(d.icons).reduce((n, s) => n + s.length, 0);
	const pack = () => writeZip([{ name: 'pack.json', data: JSON.stringify(manifest, null, 2) + '\n' }, ...manifest.pack.slots.map((s) => ({ name: `icons/${s}.svg`, data: d.icons[s]! }))]);

	return (
		<div class="st-creator-layout">
			<div style="display:flex;flex-direction:column;gap:0.8rem">
				<div>
					<span class="st-kicker">Icon pack</span>
					<h1 style="font-size:1.5rem;font-weight:800">One SVG per slot</h1>
					<p class="st-muted st-small" style="max-width:62ch;margin-top:0.3rem">
						{ICON_SLOTS.length} slots cover every tool, key, chrome glyph and emoji tab the app draws. Fill what you like; anything left empty keeps the built-in icon. Use <code>currentColor</code> so icons follow the theme, and a 24-unit viewBox.
					</p>
				</div>
				<Section title="Start from a pack" open={false}>
					<DropZone accept=".wmicons,.zip" multiple={false} onFiles={([f]) => { if (!f) return; try { const p = readIconPack(f.bytes, ICON_SLOT_IDS); const icons: Record<string, string> = {}; for (const i of p.icons) if (i.svg) icons[i.slot] = i.svg; setD({ id: p.id, name: p.name, author: p.author, description: p.description, version: p.version || '1.0.0', icons }); setErr(null); } catch (e) { setErr((e as Error).message); } }}>Drop a .wmicons to edit it</DropZone>
				</Section>
				<Section title="Pack">
					<div class="st-grid2">
						<Text label="Id" required mono value={d.id} onInput={(v) => patch({ id: v })} error={idError(d.id)} />
						<Text label="Name" required value={d.name} onInput={(v) => patch({ name: v })} />
						<Text label="Author" value={d.author} onInput={(v) => patch({ author: v })} />
						<Text label="Version" mono value={d.version} onInput={(v) => patch({ version: v })} />
					</div>
					<Area label="Description" value={d.description} onInput={(v) => patch({ description: v })} rows={2} />
				</Section>
				<Section title={`Icons (${filled} of ${ICON_SLOTS.length})`}>
					<DropZone accept=".svg" onFiles={(f) => addFiles(f)}>Drop SVGs named after their slot (tool.emoji.svg, key.enter.svg, chrome.toolbox.svg, emoji_tab.food.svg), or click a slot below to assign one.</DropZone>
					{err && <Notice kind="warn" icon={<IconWarn />}>{err}</Notice>}
					<div class="st-chips">
						{ICON_SLOT_GROUPS.map((g) => (
							<button key={g} class="st-chip" aria-pressed={group === g} onClick={() => setGroup(g)}>{g} <span class="st-count">{ICON_SLOTS.filter((s) => s.group === g && d.icons[s.id]).length}/{ICON_SLOTS.filter((s) => s.group === g).length}</span></button>
						))}
					</div>
					<div class="st-icon-grid" style="padding:0">
						{slots.map((s) => (
							<figure key={s.id} data-known="true" title={s.id} style={target === s.id ? 'border-color:var(--st-accent);box-shadow:0 0 0 3px var(--st-accent-soft)' : ''} onClick={() => setTarget(s.id)}>
								{d.icons[s.id] ? <span class="st-svg" dangerouslySetInnerHTML={{ __html: sanitizeSvg(d.icons[s.id]!) }} /> : <span class="st-svg st-muted" style="display:flex;align-items:center;justify-content:center;font-size:1.4rem;opacity:0.35">·</span>}
								<figcaption>{s.label}</figcaption>
								{d.icons[s.id] && <button class="st-btn st-btn-ghost st-btn-sm" style="padding:0.1rem 0.3rem" onClick={(e) => { e.stopPropagation(); const icons = { ...d.icons }; delete icons[s.id]; setD({ ...d, icons }); }}><IconTrash style="width:0.8rem;height:0.8rem" /></button>}
							</figure>
						))}
					</div>
					{target && (
						<div class="st-panel">
							<div class="st-row" style="justify-content:space-between"><b>Assign to <code>{target}</code></b><button class="st-btn st-btn-ghost st-btn-sm" onClick={() => setTarget(null)}>Done</button></div>
							<DropZone accept=".svg" multiple={false} onFiles={(f) => { addFiles(f, target); }}>Drop or choose one SVG for this slot</DropZone>
							<Area label="Or paste SVG" value={d.icons[target] ?? ''} onInput={(v) => setD({ ...d, icons: { ...d.icons, [target]: v } })} rows={4} mono />
						</div>
					)}
				</Section>
			</div>
			<aside class="st-creator-side">
				<ExportPanel title="Export" json={manifest}>
					<button class="st-btn st-btn-primary" disabled={!d.id || !filled} onClick={() => saveBytes(`${slugify(d.id || 'icons')}.wmicons`, pack())}><IconDownload /> .wmicons</button>
					<span class="st-small st-muted">{filled} icon{filled === 1 ? '' : 's'} · {Math.round(bytes / 1024)} KB of SVG</span>
					<button class="st-btn st-btn-ghost st-btn-sm" onClick={() => { if (confirm('Discard this draft?')) reset(); }}>Start over</button>
				</ExportPanel>
				<CommunitySubmit kind="icon_pack" name={d.name} file={pack} disabled={!d.id || !d.name || !filled} />
				<div class="st-panel st-small st-muted">The official packs (Lucide, Bootstrap, Boxicons, Font Awesome) are built this way; their SVGs keep an upstream licence comment on the first line. Add yours the same way when the licence asks for attribution.</div>
			</aside>
		</div>
	);
}

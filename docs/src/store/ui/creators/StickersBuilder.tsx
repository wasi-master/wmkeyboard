/** Sticker pack builder: images in, a .wmstickers zip (pack.json + stickers/) out. */
import { useMemo, useState } from 'preact/hooks';
import { fmtBytes } from '../../lib/net';
import { readStickerPack } from '../../lib/payloads';
import { slugify, uid } from '../../lib/util';
import { blobUrl, mimeFor, writeZip } from '../../lib/zip';
import { Notice } from '../common';
import { IconDownload, IconTrash, IconWarn } from '../icons';
import { CommunitySubmit } from './CommunitySubmit';
import { Area, DropZone, ExportPanel, idError, saveBytes, Section, Text, useDraft, type PickedFile } from './shared';

interface StickerDraft {
	key: string;
	id: string;
	name: string;
	fileName: string;
	emojis: string;
}

interface Draft {
	id: string;
	name: string;
	author: string;
	description: string;
	stickers: StickerDraft[];
}

const MAX_BYTES = 64 * 1024 * 1024;
/** The community repository's cap: the most an issue attachment carries. */
const COMMUNITY_MAX_BYTES = 24 * 1024 * 1024;

export function StickersBuilder() {
	const [d, setD, reset] = useDraft<Draft>('stickers', () => ({ id: '', name: '', author: '', description: '', stickers: [] }));
	// Image bytes live outside the draft (too big for localStorage); a reload keeps names but needs the files again.
	const [files] = useState(() => new Map<string, PickedFile & { url: string }>());
	const [, bump] = useState(0);
	const [err, setErr] = useState<string | null>(null);
	const patch = (p: Partial<Draft>) => setD({ ...d, ...p });

	const addFiles = (picked: PickedFile[]) => {
		const next: StickerDraft[] = [];
		for (const f of picked) {
			if (!/^image\//.test(f.type) && !/\.(png|jpe?g|gif|webp)$/i.test(f.name)) continue;
			const key = uid();
			files.set(key, { ...f, url: blobUrl(f.bytes, f.type || mimeFor(f.name)) });
			const base = f.name.replace(/\.[^.]+$/, '');
			next.push({ key, id: slugify(base), name: base.replace(/[_-]+/g, ' '), fileName: f.name, emojis: '' });
		}
		setD({ ...d, stickers: [...d.stickers, ...next] });
	};

	const total = useMemo(() => [...files.values()].reduce((n, f) => n + f.bytes.byteLength, 0), [files, d.stickers.length]);
	const missing = d.stickers.filter((s) => !files.has(s.key));
	const manifest = useMemo(() => ({
		format: 'wmkeyboard-stickers',
		version: 1,
		appVersion: 0,
		appVersionName: '',
		pack: {
			id: d.id,
			name: d.name,
			author: d.author,
			description: d.description,
			stickers: d.stickers.map((s) => {
				const f = files.get(s.key);
				return { id: s.id, fileName: s.fileName, mime: f?.type || mimeFor(s.fileName), name: s.name, emojis: s.emojis.split(/[\s,]+/).filter(Boolean), animated: /gif$/i.test(s.fileName), aspectRatio: 1, addedAt: 0 };
			}),
			createdAt: 0,
		},
	}), [d, files]);

	const pack = () => {
		const entries: { name: string; data: Uint8Array | string }[] = [{ name: 'pack.json', data: JSON.stringify(manifest, null, 2) + '\n' }];
		for (const s of d.stickers) {
			const f = files.get(s.key);
			if (f) entries.push({ name: `stickers/${s.fileName}`, data: f.bytes });
		}
		return writeZip(entries);
	};
	const exportZip = () => saveBytes(`${slugify(d.id || d.name || 'stickers')}.wmstickers`, pack());

	const dupIds = new Set(d.stickers.map((s) => s.id).filter((id, i, a) => a.indexOf(id) !== i));

	return (
		<div class="st-creator-layout">
			<div style="display:flex;flex-direction:column;gap:0.8rem">
				<div>
					<span class="st-kicker">Sticker pack</span>
					<h1 style="font-size:1.5rem;font-weight:800">Images become stickers</h1>
					<p class="st-muted st-small" style="max-width:62ch;margin-top:0.3rem">PNG, WebP, JPEG or GIF (animated GIFs stay animated). The app sends them as images into any field that accepts them. Cap: 500 stickers, 64 MB.</p>
				</div>
				<Section title="Start from a pack" open={false}>
					<DropZone accept=".wmstickers,.zip" multiple={false} onFiles={([f]) => { if (!f) return; try { const p = readStickerPack(f.bytes); files.clear(); const stickers = p.stickers.map((s) => { const key = uid(); if (s.url) { /* re-read bytes from the zip entry via fetch of the blob url */ } return { key, id: s.id, name: s.name, fileName: s.fileName.split('/').pop() ?? s.fileName, emojis: s.emojis.join(' ') }; }); setD({ id: p.id, name: p.name, author: p.author, description: p.description, stickers }); setErr('Loaded the pack manifest; drop the image files again to include them in the export.'); } catch (e) { setErr((e as Error).message); } }}>Drop a .wmstickers to edit its manifest</DropZone>
					{err && <Notice kind="warn" icon={<IconWarn />}>{err}</Notice>}
				</Section>
				<Section title="Pack">
					<div class="st-grid2">
						<Text label="Id" required mono value={d.id} onInput={(v) => patch({ id: v })} placeholder="my-stickers" error={idError(d.id)} />
						<Text label="Name" required value={d.name} onInput={(v) => patch({ name: v })} />
						<Text label="Author" value={d.author} onInput={(v) => patch({ author: v })} />
					</div>
					<Area label="Description" value={d.description} onInput={(v) => patch({ description: v })} rows={2} />
				</Section>
				<Section title={`Stickers (${d.stickers.length}) · ${fmtBytes(total)}`}>
					<DropZone accept="image/*" onFiles={addFiles}>Drop images</DropZone>
					{missing.length > 0 && <Notice kind="warn" icon={<IconWarn />}>{missing.length} sticker{missing.length === 1 ? '' : 's'} from a previous session need{missing.length === 1 ? 's' : ''} the image file dropped again (names match: {missing.slice(0, 5).map((m) => m.fileName).join(', ')}{missing.length > 5 ? '…' : ''}).</Notice>}
					<div class="st-sticker-grid" style="padding:0">
						{d.stickers.map((s) => {
							const f = files.get(s.key);
							return (
								<figure key={s.key} style="gap:0.4rem;padding:0.5rem" title={s.fileName}>
									{f ? <img src={f.url} alt="" /> : <span class="st-muted" style="aspect-ratio:1;display:flex;align-items:center;justify-content:center;width:100%">missing</span>}
									<input class="st-input" style="padding:0.25rem 0.4rem;font-size:0.72rem" value={s.name} placeholder="Name" onInput={(e) => setD({ ...d, stickers: d.stickers.map((x) => (x.key === s.key ? { ...x, name: (e.target as HTMLInputElement).value } : x)) })} />
									<input class="st-input" style="padding:0.25rem 0.4rem;font-size:0.72rem" value={s.emojis} placeholder="😀 emoji hints" onInput={(e) => setD({ ...d, stickers: d.stickers.map((x) => (x.key === s.key ? { ...x, emojis: (e.target as HTMLInputElement).value } : x)) })} />
									<input class="st-input" style={`padding:0.25rem 0.4rem;font-size:0.68rem;font-family:var(--sl-font-mono);${dupIds.has(s.id) ? 'border-color:var(--st-err)' : ''}`} value={s.id} onInput={(e) => setD({ ...d, stickers: d.stickers.map((x) => (x.key === s.key ? { ...x, id: (e.target as HTMLInputElement).value } : x)) })} />
									<button class="st-btn st-btn-ghost st-btn-sm" onClick={() => { files.delete(s.key); setD({ ...d, stickers: d.stickers.filter((x) => x.key !== s.key) }); bump((n) => n + 1); }}><IconTrash /></button>
								</figure>
							);
						})}
					</div>
				</Section>
			</div>
			<aside class="st-creator-side">
				<ExportPanel title="Export" json={manifest}>
					<button class="st-btn st-btn-primary" disabled={!d.id || !d.stickers.length || missing.length > 0 || total > MAX_BYTES} onClick={exportZip}><IconDownload /> .wmstickers</button>
					{total > MAX_BYTES && <span class="st-err-text">Over the 64 MB cap.</span>}
					{dupIds.size > 0 && <span class="st-err-text">Duplicate sticker ids: {[...dupIds].join(', ')}</span>}
					<button class="st-btn st-btn-ghost st-btn-sm" onClick={() => { if (confirm('Discard this draft?')) { reset(); files.clear(); } }}>Start over</button>
				</ExportPanel>
				{/* GitHub attaches at most 24 MB, under the app's own 64. */}
				<CommunitySubmit kind="stickers" name={d.name} file={pack} disabled={!d.id || !d.name || !d.stickers.length || missing.length > 0 || dupIds.size > 0 || total > COMMUNITY_MAX_BYTES} />
				{total > COMMUNITY_MAX_BYTES && total <= MAX_BYTES && <span class="st-small st-muted">Over 24 MB, the most GitHub attaches, so it cannot go to the community repository.</span>}
				<div class="st-panel st-small st-muted">The archive is <code>pack.json</code> plus <code>stickers/&lt;file&gt;</code>. Names inside the zip are never used as paths by the app.</div>
			</aside>
		</div>
	);
}

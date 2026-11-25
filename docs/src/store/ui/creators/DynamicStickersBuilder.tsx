/**
 * Dynamic sticker pack builder: Lottie documents in, a .wmdynstickers zip
 * (pack.json + templates/ + fonts/ + images/) out, written the way
 * DynPackFile.write writes one. Each template gets the phrases and feelings it
 * answers, and plays here with sample text in its text layers.
 */
import { useMemo, useState } from 'preact/hooks';
import { fmtBytes } from '../../lib/net';
import {
	DYN_FORMAT,
	DYN_MAX_ENTRIES,
	DYN_MAX_FILE_BYTES,
	DYN_MAX_TEMPLATES,
	DYN_MAX_TOTAL_BYTES,
	dynId,
	lottieProps,
	lottieTextLayers,
	parseLottie,
	readDynStickerPack,
} from '../../lib/payloads';
import { uid } from '../../lib/util';
import { writeZip } from '../../lib/zip';
import { Notice } from '../common';
import { IconDownload, IconTrash, IconWarn } from '../icons';
import { LottiePlayer, SAMPLE_TEXT } from '../previews/DynStickers';
import { Area, DropZone, ExportPanel, Num, saveBytes, Section, Select, Tags, Text, Toggle, useDraft, type PickedFile } from './shared';

type Doc = Record<string, unknown>;

interface TemplateDraft {
	key: string;
	id: string;
	name: string;
	/** The file it came from, so a reload can match the file dropped again. */
	source: string;
	kind: 'sticker' | 'meme';
	phrases: string[];
	concepts: string[];
	generic: boolean;
	maxWords: number | null;
	altText: string;
}

interface FontDraft {
	key: string;
	family: string;
	fileName: string;
}

interface Draft {
	id: string;
	name: string;
	author: string;
	version: string;
	license: string;
	description: string;
	templates: TemplateDraft[];
	fonts: FontDraft[];
	images: string[];
}

const blank = (): Draft => ({ id: '', name: '', author: '', version: '1.0.0', license: '', description: '', templates: [], fonts: [], images: [] });

const PACK_ID = /^[a-z0-9_-]{1,48}$/;

/** DynStickerStore.safeFileName: what an archive name becomes on the phone. */
const safeFileName = (raw: string) => raw.replace(/[^A-Za-z0-9._-]/g, '_').replace(/^\.+/, '') || 'file';

const titleCase = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

/** Font families a document's text asks for. */
function familiesOf(doc: Doc): string[] {
	const list = (doc.fonts as { list?: unknown } | undefined)?.list;
	if (!Array.isArray(list)) return [];
	return [...new Set((list as Doc[]).map((f) => String(f.fFamily ?? f.fName ?? '')).filter(Boolean))];
}

/** Picture names a document refers to and does not embed. */
function picturesOf(doc: Doc): string[] {
	if (!Array.isArray(doc.assets)) return [];
	return (doc.assets as Doc[])
		.filter((a) => a && typeof a.p === 'string' && !Array.isArray(a.layers) && !String(a.p).startsWith('data:') && !String(a.id ?? '').startsWith('photo'))
		.map((a) => String(a.p).split('/').pop() ?? '');
}

export function DynamicStickersBuilder() {
	const [d, setD, reset] = useDraft<Draft>('dynamic_stickers', blank);
	// Documents, fonts and pictures live outside the draft (too big for
	// localStorage); a reload keeps the fields and asks for the files again.
	const [docs] = useState(() => new Map<string, Doc>());
	const [fontBytes] = useState(() => new Map<string, Uint8Array>());
	const [images] = useState(() => new Map<string, Uint8Array>());
	const [, bump] = useState(0);
	const [open, setOpen] = useState<string | null>(null);
	const [sample, setSample] = useState(SAMPLE_TEXT);
	const [notes, setNotes] = useState<string[]>([]);
	const patch = (p: Partial<Draft>) => setD({ ...d, ...p });
	const patchTemplate = (key: string, p: Partial<TemplateDraft>) => setD({ ...d, templates: d.templates.map((t) => (t.key === key ? { ...t, ...p } : t)) });

	const addFiles = (picked: PickedFile[]) => {
		const out: string[] = [];
		const templates = [...d.templates];
		const fonts = [...d.fonts];
		const pictures = new Set(d.images);
		const taken = new Set(templates.map((t) => t.id));
		for (const f of picked) {
			const lower = f.name.toLowerCase();
			if (f.bytes.byteLength > DYN_MAX_FILE_BYTES) {
				out.push(`${f.name} is over 12 MB, which the app skips.`);
				continue;
			}
			if (/\.(ttf|otf)$/.test(lower)) {
				const again = fonts.find((x) => x.fileName === f.name && !fontBytes.has(x.key));
				const key = again?.key ?? uid();
				fontBytes.set(key, f.bytes);
				if (!again) fonts.push({ key, family: f.name.replace(/\.[^.]+$/, '').split('-')[0] ?? f.name, fileName: f.name });
				continue;
			}
			if (/\.(png|jpe?g|webp)$/.test(lower)) {
				images.set(safeFileName(f.name), f.bytes);
				pictures.add(safeFileName(f.name));
				continue;
			}
			if (!/\.(json|tgs)$/.test(lower)) {
				out.push(`${f.name}: drop Lottie .json (or gzipped .tgs) documents, .ttf/.otf fonts and pictures.`);
				continue;
			}
			const doc = parseLottie(f.bytes);
			if (!doc) {
				out.push(`${f.name} is not a Lottie document.`);
				continue;
			}
			const layers = lottieTextLayers(doc);
			if (!layers.length) {
				out.push(`${f.name} has no text layer, so there is nowhere for typed words to go. Add a text layer named _txt_0 in After Effects and export again.`);
				continue;
			}
			const again = templates.find((t) => t.source === f.name && !docs.has(t.key));
			if (again) {
				docs.set(again.key, doc);
				continue;
			}
			if (templates.length >= DYN_MAX_TEMPLATES) {
				out.push(`${f.name}: a pack holds ${DYN_MAX_TEMPLATES} templates at most.`);
				continue;
			}
			const stem = f.name.replace(/\.[^.]+$/, '');
			let id = dynId(stem) || 'template';
			for (let n = 2; taken.has(id); n++) id = `${dynId(stem) || 'template'}_${n}`;
			taken.add(id);
			const props = lottieProps(doc);
			const key = uid();
			docs.set(key, doc);
			const photos = Array.isArray(doc.assets) && (doc.assets as Doc[]).some((a) => String(a?.id ?? '').startsWith('photo') && typeof a?.p === 'string');
			templates.push({
				key,
				id,
				name: props?.phrases[0] ? titleCase(props.phrases[0]) : titleCase(stem.replace(/(_still)?_v?\d+$/, '').replace(/[_-]+/g, ' ')),
				source: f.name,
				kind: photos ? 'meme' : 'sticker',
				phrases: props?.phrases ?? [],
				concepts: props?.concepts ?? [],
				generic: props?.generic ?? false,
				maxWords: null,
				altText: props?.altText ?? '',
			});
		}
		setNotes(out);
		setD({ ...d, templates, fonts, images: [...pictures] });
		bump((n) => n + 1);
	};

	const loadPack = (f: PickedFile | undefined) => {
		if (!f) return;
		try {
			const p = readDynStickerPack(f.bytes);
			docs.clear();
			fontBytes.clear();
			images.clear();
			const templates: TemplateDraft[] = [];
			let scenes = 0;
			for (const t of p.templates) {
				if (!t.doc) {
					scenes++;
					continue;
				}
				const key = uid();
				docs.set(key, t.doc);
				templates.push({ key, id: t.id, name: t.name, source: t.file.split('/').pop() ?? t.file, kind: t.kind, phrases: t.phrases, concepts: t.concepts, generic: t.generic, maxWords: t.maxWords || null, altText: t.altText });
			}
			const fonts: FontDraft[] = [];
			for (const font of p.fonts) {
				if (!font.bytes) continue;
				const key = uid();
				fontBytes.set(key, font.bytes);
				fonts.push({ key, family: font.family, fileName: font.file.split('/').pop() ?? font.file });
			}
			for (const [name, bytes] of p.images) images.set(safeFileName(name), bytes);
			setD({ id: dynId(p.id || f.name.replace(/\.[^.]+$/, '')), name: p.name, author: p.author, version: p.version || '1.0.0', license: p.license, description: p.description, templates, fonts, images: [...images.keys()] });
			setNotes([...p.problems, ...(scenes ? [`${scenes} template${scenes === 1 ? ' was' : 's were'} made in the app's builder and carr${scenes === 1 ? 'ies' : 'y'} no document; edit ${scenes === 1 ? 'it' : 'them'} in the app.`] : [])]);
			bump((n) => n + 1);
		} catch (e) {
			setNotes([(e as Error).message]);
		}
	};

	const fontList = useMemo(() => d.fonts.map((f) => ({ family: f.family, file: `fonts/${safeFileName(f.fileName)}`, bytes: fontBytes.get(f.key) ?? null })), [d.fonts, fontBytes.size]);

	const manifest = useMemo(() => {
		const templates = d.templates.map((t) => {
			const match: Record<string, unknown> = {};
			if (t.phrases.length) match.phrases = t.phrases;
			if (t.concepts.length) match.concepts = t.concepts;
			if (t.generic) match.generic = true;
			if (t.maxWords) match.maxWords = t.maxWords;
			return {
				id: t.id,
				name: t.name,
				file: `templates/${t.id}.json`,
				...(t.kind === 'meme' ? { kind: 'meme' } : {}),
				...(Object.keys(match).length ? { match } : {}),
				...(t.altText ? { altText: t.altText } : {}),
			};
		});
		const pack: Record<string, unknown> = { id: d.id, name: d.name };
		if (d.author) pack.author = d.author;
		if (d.version) pack.version = d.version;
		if (d.license) pack.license = d.license;
		if (d.description) pack.description = d.description;
		pack.templates = templates;
		if (d.fonts.length) pack.fonts = fontList.map((f) => ({ family: f.family, file: f.file }));
		return { format: DYN_FORMAT, version: 1, pack };
	}, [d, fontList]);

	const entries = (): { name: string; data: Uint8Array | string }[] => {
		const out: { name: string; data: Uint8Array | string }[] = [{ name: 'pack.json', data: JSON.stringify(manifest, null, 2) + '\n' }];
		for (const t of d.templates) {
			const doc = docs.get(t.key);
			if (doc) out.push({ name: `templates/${t.id}.json`, data: JSON.stringify(doc) });
		}
		for (const f of d.fonts) {
			const bytes = fontBytes.get(f.key);
			if (bytes) out.push({ name: `fonts/${safeFileName(f.fileName)}`, data: bytes });
		}
		for (const name of d.images) {
			const bytes = images.get(name);
			if (bytes) out.push({ name: `images/${name}`, data: bytes });
		}
		return out;
	};

	const enc = new TextEncoder();
	const total = useMemo(
		() =>
			[...docs.values()].reduce((n, doc) => n + enc.encode(JSON.stringify(doc)).byteLength, 0) +
			[...fontBytes.values()].reduce((n, b) => n + b.byteLength, 0) +
			[...images.values()].reduce((n, b) => n + b.byteLength, 0),
		// eslint-disable-next-line react-hooks/exhaustive-deps
		[d.templates.length, d.fonts.length, d.images.length, docs.size, fontBytes.size, images.size],
	);
	const fileCount = 1 + d.templates.length + d.fonts.length + d.images.length;
	const missing = d.templates.filter((t) => !docs.has(t.key));
	const missingFonts = d.fonts.filter((f) => !fontBytes.has(f.key));
	const dupIds = new Set(d.templates.map((t) => t.id).filter((id, i, a) => a.indexOf(id) !== i));
	const badIds = d.templates.filter((t) => !PACK_ID.test(t.id));
	const packIdError = !d.id ? 'Required.' : PACK_ID.test(d.id) ? null : 'Lowercase letters, digits, "_" and "-", up to 48. The app keys favourites to it.';

	// What the documents ask for that the pack does not carry.
	const asked = new Set<string>();
	const pictures = new Set<string>();
	for (const doc of docs.values()) {
		familiesOf(doc).forEach((f) => asked.add(f));
		picturesOf(doc).forEach((p) => pictures.add(p));
	}
	const carried = (family: string) => {
		const want = family.toLowerCase();
		return d.fonts.some((f) => f.family.toLowerCase() === want || f.fileName.replace(/\.[^.]+$/, '').toLowerCase() === want || f.family.toLowerCase() === want.split('-')[0]);
	};
	const uncarriedFonts = [...asked].filter((f) => !carried(f));
	const uncarriedPictures = [...pictures].filter((p) => !images.has(p));

	const problems = [
		...(fileCount > DYN_MAX_ENTRIES ? [`${fileCount} files; the app reads ${DYN_MAX_ENTRIES} at most.`] : []),
		...(total > DYN_MAX_TOTAL_BYTES ? ['Over the 64 MB cap.'] : []),
		...(dupIds.size ? [`Two templates share an id: ${[...dupIds].join(', ')}`] : []),
		...(badIds.length ? [`Template ids must be lowercase letters, digits, "_" and "-": ${badIds.map((t) => t.id || '(blank)').join(', ')}`] : []),
	];
	const blocked = !!packIdError || !d.name || !d.templates.length || missing.length > 0 || missingFonts.length > 0 || problems.length > 0;
	const exportZip = () => saveBytes(`${d.id || 'templates'}.wmdynstickers`, writeZip(entries()));

	return (
		<div class="st-creator-layout">
			<div style="display:flex;flex-direction:column;gap:0.8rem">
				<div>
					<span class="st-kicker">Dynamic stickers</span>
					<h1 style="font-size:1.5rem;font-weight:800">Templates that draw what you type</h1>
					<p class="st-muted st-small" style="max-width:62ch;margin-top:0.3rem">
						Drop Lottie animations exported from After Effects (Bodymovin) or LottieFiles, each with a text layer for the words. Layers named <code>_txt_0</code>, <code>_txt_1</code>… take the message one line each; any other text layer is a field of its own. Say which messages each one answers, and the keyboard offers it as a sticker when you type one. Cap: {DYN_MAX_TEMPLATES} templates, 64 MB.
					</p>
				</div>
				<Section title="Start from a pack" open={false}>
					<DropZone accept=".wmdynstickers,.zip" multiple={false} onFiles={([f]) => loadPack(f)}>Drop a .wmdynstickers (or a zip of Lottie files) to edit it</DropZone>
				</Section>
				{notes.length > 0 && (
					<Notice kind="warn" icon={<IconWarn />}>
						<ul style="padding-left:1rem">{notes.map((n, i) => <li key={i}>{n}</li>)}</ul>
					</Notice>
				)}
				<Section title="Pack">
					<div class="st-grid2">
						<Text label="Id" required mono value={d.id} onInput={(v) => patch({ id: v })} placeholder="everyday" error={packIdError} />
						<Text label="Name" required value={d.name} onInput={(v) => patch({ name: v })} />
						<Text label="Author" value={d.author} onInput={(v) => patch({ author: v })} />
						<Text label="Version" mono value={d.version} onInput={(v) => patch({ version: v })} placeholder="1.0.0" />
						<Text label="Licence" value={d.license} onInput={(v) => patch({ license: v })} placeholder="CC0-1.0" help="SPDX where one fits. State the licence of the animations and of every font you include." />
					</div>
					<Area label="Description" value={d.description} onInput={(v) => patch({ description: v })} rows={2} />
				</Section>
				<Section title={`Templates (${d.templates.length}) · fonts (${d.fonts.length}) · pictures (${d.images.length}) · ${fmtBytes(total)}`}>
					<DropZone accept=".json,.tgs,.ttf,.otf,.png,.jpg,.jpeg,.webp" onFiles={addFiles}>Drop Lottie .json files, and the fonts and pictures they use</DropZone>
					{missing.length > 0 && <Notice kind="warn" icon={<IconWarn />}>{missing.length} template{missing.length === 1 ? '' : 's'} from a previous visit need{missing.length === 1 ? 's' : ''} the file dropped again (names match: {missing.slice(0, 5).map((m) => m.source).join(', ')}{missing.length > 5 ? '…' : ''}).</Notice>}
					{missingFonts.length > 0 && <Notice kind="warn" icon={<IconWarn />}>Drop these fonts again: {missingFonts.map((f) => f.fileName).join(', ')}.</Notice>}
					{uncarriedFonts.length > 0 && <Notice kind="info">The templates ask for {uncarriedFonts.join(', ')}, which the pack does not carry. The phone uses an installed font of that name if it has one, and its system font if not. Drop the font file to include it.</Notice>}
					{uncarriedPictures.length > 0 && <Notice kind="warn" icon={<IconWarn />}>The templates use pictures the pack does not carry: {uncarriedPictures.join(', ')}. Drop them to include them; without them those parts draw blank.</Notice>}
					{d.templates.length > 0 && <Text label="Sample text" value={sample} onInput={setSample} help="What the template being edited plays with." />}
					<div style="display:flex;flex-direction:column;gap:0.5rem">
						{d.templates.map((t) => {
							const doc = docs.get(t.key);
							const isOpen = open === t.key;
							const layers = doc ? lottieTextLayers(doc) : [];
							return (
								<div key={t.key} class="st-panel" style="padding:0.7rem">
									<div class="st-row" style="justify-content:space-between;gap:0.5rem">
										<button class="st-btn st-btn-ghost st-btn-sm" style="flex:1;justify-content:flex-start" onClick={() => setOpen(isOpen ? null : t.key)} aria-expanded={isOpen}>
											<b>{t.name || t.id}</b>
											<span class="st-muted st-small" style="margin-left:0.5rem">{t.phrases.length ? t.phrases.slice(0, 3).join(', ') : t.generic ? 'any text' : 'no phrases'}</span>
											{!doc && <span class="st-pill st-pill-err" style="margin-left:0.5rem">file needed</span>}
										</button>
										<button class="st-btn st-btn-ghost st-btn-sm" aria-label={`Remove ${t.name || t.id}`} onClick={() => { docs.delete(t.key); setD({ ...d, templates: d.templates.filter((x) => x.key !== t.key) }); bump((n) => n + 1); }}><IconTrash /></button>
									</div>
									{isOpen && (
										<div class="st-grid2" style="margin-top:0.6rem;align-items:start">
											<div style="display:flex;flex-direction:column;gap:0.6rem">
												<Text label="Name" value={t.name} onInput={(v) => patchTemplate(t.key, { name: v })} />
												<Text label="Id" mono value={t.id} onInput={(v) => patchTemplate(t.key, { id: v })} error={dupIds.has(t.id) ? 'Another template has this id.' : PACK_ID.test(t.id) ? null : 'Lowercase letters, digits, "_" and "-".'} help="Keep it the same between versions: favourites are kept by it." />
												<Tags label="Phrases" value={t.phrases} onInput={(v) => patchTemplate(t.key, { phrases: v })} placeholder="thank you, thanks, thx" help="Offered when the message is one of these, or has one in it. Any language." />
												<Tags label="Feelings and things" value={t.concepts} onInput={(v) => patchTemplate(t.key, { concepts: v })} placeholder="grateful, heart" help="Matched from the emoji and words around the message." />
												<Toggle label="Takes any short text" value={t.generic} onInput={(v) => patchTemplate(t.key, { generic: v })} help="Offered after the templates that matched by meaning." />
												<Num label="Most words" value={t.maxWords} onInput={(v) => patchTemplate(t.key, { maxWords: v })} min={1} max={40} allowEmpty help="Leave empty for the user's own setting." />
												<Select label="Kind" value={t.kind} onInput={(v) => patchTemplate(t.key, { kind: v })} options={[['sticker', 'Sticker'], ['meme', 'Meme']]} help="Memes sit apart in the panel and stay out of the suggestion row unless the user asks for them." />
												<Text label="Description" value={t.altText} onInput={(v) => patchTemplate(t.key, { altText: v })} placeholder="Bouncing thank-you lettering" />
												<small class="st-muted">Text layers: {layers.map((l) => l.name).join(', ') || 'none'}</small>
											</div>
											{doc && <LottiePlayer doc={doc} fonts={fontList} images={images} text={sample || SAMPLE_TEXT} label={t.name || t.id} />}
										</div>
									)}
								</div>
							);
						})}
					</div>
					{d.fonts.length > 0 && (
						<div style="display:flex;flex-direction:column;gap:0.4rem">
							{d.fonts.map((f) => (
								<div key={f.key} class="st-row" style="gap:0.5rem">
									<Text label={f.fileName} value={f.family} onInput={(v) => setD({ ...d, fonts: d.fonts.map((x) => (x.key === f.key ? { ...x, family: v } : x)) })} help="The family the templates ask for." />
									<button class="st-btn st-btn-ghost st-btn-sm" aria-label={`Remove ${f.fileName}`} onClick={() => { fontBytes.delete(f.key); setD({ ...d, fonts: d.fonts.filter((x) => x.key !== f.key) }); bump((n) => n + 1); }}><IconTrash /></button>
								</div>
							))}
						</div>
					)}
				</Section>
			</div>
			<aside class="st-creator-side">
				<ExportPanel title="Export" json={manifest}>
					<button class="st-btn st-btn-primary" disabled={blocked} onClick={exportZip}><IconDownload /> .wmdynstickers</button>
					{problems.map((p, i) => <span key={i} class="st-err-text">{p}</span>)}
					<button class="st-btn st-btn-ghost st-btn-sm" onClick={() => { if (confirm('Discard this draft?')) { reset(); docs.clear(); fontBytes.clear(); images.clear(); setNotes([]); } }}>Start over</button>
				</ExportPanel>
				<div class="st-panel st-small st-muted">
					The archive is <code>pack.json</code> plus <code>templates/&lt;id&gt;.json</code>, <code>fonts/</code> and <code>images/</code>. Open it on the phone to add the pack, or list it in a repository as <code>dynamic_stickers</code>. No path inside it is ever used outside the pack's own folder.
				</div>
			</aside>
		</div>
	);
}

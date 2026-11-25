/**
 * Dynamic sticker previews: a few templates played with lottie-web, their
 * typed text replaced by a sample word the way the keyboard fills them, and
 * the rest listed with the phrases each one answers.
 *
 * The player is lottie-web's light build: SVG only and no expressions, so a
 * document from a stranger's repository never gets to run script on this
 * page. It draws inside a shadow root, because the site's stylesheets
 * otherwise override the font size lottie puts on each text layer (its
 * canvas renderer would dodge that, but draws text only from glyph shapes,
 * which most templates do not carry). Every font and picture it names is pointed at the archive's own
 * bytes or a blank, so playing one fetches nothing from anywhere.
 */
import { useEffect, useRef, useState } from 'preact/hooks';
import { fmtBytes } from '../../lib/net';
import { lottieTextLayers, type DynPackRead, type DynTemplateInfo } from '../../lib/payloads';
import { blobUrl, mimeFor } from '../../lib/zip';
import { Problems } from './Preview';

/** How many templates play at once; the rest are listed. */
const PLAYING = 3;

/** What the keyboard would draw before anything is typed, in the preview. */
export const SAMPLE_TEXT = 'HELLO';

const BLANK_PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=';

type Doc = Record<string, unknown>;
type Font = DynPackRead['fonts'][number];

const fontFaces = new WeakMap<Uint8Array, Promise<string | null>>();
let faceSeq = 0;

/** Registers a pack font with the page under a name of its own, once per file. */
function registerFont(bytes: Uint8Array): Promise<string | null> {
	let p = fontFaces.get(bytes);
	if (!p) {
		const family = `wm-dyn-${++faceSeq}`;
		p = new FontFace(family, bytes as BufferSource)
			.load()
			.then((face) => {
				document.fonts.add(face);
				return family;
			})
			.catch(() => null);
		fontFaces.set(bytes, p);
	}
	return p;
}

const imageUrls = new WeakMap<Uint8Array, string>();
function imageUrl(name: string, bytes: Uint8Array): string {
	let u = imageUrls.get(bytes);
	if (!u) {
		u = blobUrl(bytes, mimeFor(name));
		imageUrls.set(bytes, u);
	}
	return u;
}

/** The pack font a document's font entry asks for: by family, then by file name (DynStickerStore.fontFile). */
function packFontFor(fonts: Font[], name: string): Font | undefined {
	const want = name.toLowerCase();
	const stem = (f: Font) => (f.file.split('/').pop() ?? '').replace(/\.[^.]+$/, '').toLowerCase();
	return (
		fonts.find((f) => f.bytes && f.family.toLowerCase() === want) ??
		fonts.find((f) => f.bytes && stem(f) === want) ??
		fonts.find((f) => f.bytes && f.family.toLowerCase() === want.split('-')[0])
	);
}

/**
 * A copy of [doc] ready to play: [text] in the main slot and the other lines
 * of it emptied (one word fills one line), every font pointed at the pack's
 * file or the page's fallback, and every picture at the pack's file or a
 * blank. Nothing in it can reach the network.
 */
export async function playableDoc(doc: Doc, fonts: Font[], images: Map<string, Uint8Array>, text: string): Promise<Doc> {
	const copy = structuredClone(doc) as Doc;
	const layers = lottieTextLayers(copy);
	const lines = layers.filter((l) => l.line != null).sort((a, b) => a.line! - b.line!);
	const fill = new Map<string, string>();
	if (lines.length) lines.forEach((l, i) => fill.set(l.name, i === 0 ? text : ''));
	else if (layers[0]) fill.set(layers[0].name, text);
	const patch = (list: unknown) => {
		if (!Array.isArray(list)) return;
		for (const l of list as Doc[]) {
			if (l?.ty !== 5 || typeof l.nm !== 'string' || !fill.has(l.nm)) continue;
			const k = (l.t as { d?: { k?: unknown } } | undefined)?.d?.k;
			if (Array.isArray(k)) for (const key of k as { s?: Doc }[]) if (key?.s) key.s.t = fill.get(l.nm);
		}
	};
	patch(copy.layers);
	const assets = Array.isArray(copy.assets) ? (copy.assets as Doc[]) : [];
	for (const a of assets) {
		patch(a?.layers);
		if (a && typeof a.p === 'string' && !Array.isArray(a.layers)) {
			if (!a.p.startsWith('data:')) {
				const name = a.p.split('/').pop() ?? '';
				const bytes = String(a.id ?? '').startsWith('photo') ? undefined : images.get(name);
				a.p = bytes ? imageUrl(name, bytes) : BLANK_PNG;
			}
			a.u = '';
			a.e = 1;
		}
	}
	const list = ((copy.fonts as { list?: unknown } | undefined)?.list ?? []) as Doc[];
	for (const f of Array.isArray(list) ? list : []) {
		const own = packFontFor(fonts, String(f.fFamily ?? '')) ?? packFontFor(fonts, String(f.fName ?? ''));
		const family = own?.bytes ? await registerFont(own.bytes) : null;
		if (family) f.fFamily = family;
		delete f.fPath;
		delete f.fOrigin;
		delete f.origin;
	}
	return copy;
}

/** One template, playing. */
export function LottiePlayer({ doc, fonts, images, text = SAMPLE_TEXT, label }: { doc: Doc; fonts: Font[]; images: Map<string, Uint8Array>; text?: string; label: string }) {
	const box = useRef<HTMLDivElement>(null);
	const [failed, setFailed] = useState(false);
	useEffect(() => {
		let anim: { destroy(): void } | null = null;
		let cancelled = false;
		setFailed(false);
		(async () => {
			try {
				const [{ default: lottie }, data] = await Promise.all([
					import('lottie-web/build/player/lottie_light'),
					playableDoc(doc, fonts, images, text),
				]);
				if (cancelled || !box.current) return;
				const root = box.current.shadowRoot ?? box.current.attachShadow({ mode: 'open' });
				const stage = document.createElement('div');
				stage.style.cssText = 'width:100%;height:100%';
				root.replaceChildren(stage);
				anim = lottie.loadAnimation({ container: stage, renderer: 'svg', loop: true, autoplay: true, animationData: data, rendererSettings: { title: label, preserveAspectRatio: 'xMidYMid meet' } });
			} catch {
				if (!cancelled) setFailed(true);
			}
		})();
		return () => {
			cancelled = true;
			anim?.destroy();
		};
	}, [doc, fonts, images, text, label]);
	return (
		<div class="st-dyn-stage">
			<div ref={box} class="st-dyn-canvas" />
			{failed && <span class="st-small st-muted">This document does not play here.</span>}
		</div>
	);
}

function TemplateMeta({ t }: { t: DynTemplateInfo }) {
	const bits: string[] = [];
	if (t.phrases.length) bits.push(`for ${t.phrases.slice(0, 4).map((p) => `“${p}”`).join(', ')}${t.phrases.length > 4 ? '…' : ''}`);
	if (t.concepts.length) bits.push(`feels ${t.concepts.slice(0, 4).join(', ')}`);
	if (t.generic) bits.push('takes any short text');
	if (t.maxWords) bits.push(`up to ${t.maxWords} word${t.maxWords === 1 ? '' : 's'}`);
	return <small class="st-muted" style="display:block;line-height:1.35">{bits.join(' · ') || 'no phrases: offered from the panel only'}</small>;
}

export function DynStickerPreview({ read, sample }: { read: DynPackRead; sample?: string }) {
	const playable = read.templates.filter((t) => t.doc && t.textLayers.length).slice(0, PLAYING);
	const memes = read.templates.filter((t) => t.kind === 'meme').length;
	const fonts = read.fonts.filter((f) => f.bytes).length;
	return (
		<>
			<Problems items={read.problems} />
			<div class="st-pv-pad st-small st-muted" style="padding-bottom:0">
				{read.templates.length} template{read.templates.length === 1 ? '' : 's'}
				{memes ? ` (${memes} meme${memes === 1 ? '' : 's'})` : ''}
				{fonts ? ` · ${fonts} font${fonts === 1 ? '' : 's'}` : ''} · {fmtBytes(read.totalBytes)}
				{read.author ? ` · by ${read.author}` : ''}
				{read.loose ? ' · read as loose Lottie files (no pack.json)' : ''}
			</div>
			{playable.length > 0 && (
				<div class="st-dyn-grid">
					{playable.map((t) => (
						<figure key={t.id}>
							<LottiePlayer doc={t.doc!} fonts={read.fonts} images={read.images} text={sample || SAMPLE_TEXT} label={t.name} />
							<figcaption>{t.name}</figcaption>
						</figure>
					))}
				</div>
			)}
			<ul class="st-dyn-list">
				{read.templates.map((t) => (
					<li key={t.id}>
						<b>{t.name}</b>
						{t.kind === 'meme' && <span class="st-pill st-pill-muted" style="margin-left:0.4rem">meme</span>}
						{!t.doc && <span class="st-pill st-pill-muted" style="margin-left:0.4rem">builder scene, plays in the app only</span>}
						<TemplateMeta t={t} />
					</li>
				))}
			</ul>
		</>
	);
}

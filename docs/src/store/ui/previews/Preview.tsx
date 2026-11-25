/**
 * The "what's inside" section of an addon page. Fetches the payload (capped
 * like the app's preview: 12 MB or the type's limit, whichever is smaller),
 * reads it with the matching payload reader, and hands the result to a
 * renderer. Nothing is fetched until the user asks, except for small
 * text-ish payloads which load on their own.
 */
import { useEffect, useState } from 'preact/hooks';
import { fetchBytes, fmtBytes, NetError, PREVIEW_MAX_BYTES } from '../../lib/net';
import { typeInfo, type AddonEntry, type LoadedRepo } from '../../lib/types';
import { payloadFileName, payloadUrl } from '../../lib/util';
import { sha256Hex } from '../../lib/util';
import {
	readDictionary,
	readDynStickerPack,
	readEmojiKeywords,
	readEspanso,
	readFont,
	readIconPack,
	readLayout,
	readPlugin,
	readSnippets,
	readSound,
	readSoundPack,
	readStickerPack,
	readTheme,
	readVocabulary,
} from '../../lib/payloads';
import { ICON_SLOT_IDS } from '../../lib/icon-slots';
import { Notice, Spinner } from '../common';
import { IconCheck, IconDownload, IconWarn } from '../icons';
import { ThemeMock, LayoutViewer } from './KeyboardMock';
import { FontPreview, IconPackPreview, SoundPackPreview, SoundPreview, StickerPreview } from './Media';
import { DynStickerPreview } from './DynStickers';
import { DictionaryPreview, EmojiKeywordPreview, EspansoPreview, PluginPreview, SnippetPreview, VocabularyPreview } from './Tables';

type State =
	| { s: 'idle' }
	| { s: 'loading'; received: number; total: number | null }
	| { s: 'error'; message: string }
	| { s: 'ready'; bytes: Uint8Array; sha: string | null; shaOk: boolean | null };

const AUTO_LOAD_BYTES = 1.5 * 1024 * 1024;

export function Preview({ repo, entry }: { repo: LoadedRepo; entry: AddonEntry }) {
	const url = payloadUrl(repo.ref.url, entry);
	const info = typeInfo(entry.type);
	const cap = Math.min(info.maxBytes || PREVIEW_MAX_BYTES, PREVIEW_MAX_BYTES);
	const [state, setState] = useState<State>({ s: 'idle' });
	const [ctl, setCtl] = useState<AbortController | null>(null);
	const auto = entry.sizeBytes != null && entry.sizeBytes <= AUTO_LOAD_BYTES;

	useEffect(() => {
		setState({ s: 'idle' });
		ctl?.abort();
		setCtl(null);
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [url]);

	const load = async () => {
		if (!url) return;
		const c = new AbortController();
		setCtl(c);
		setState({ s: 'loading', received: 0, total: entry.sizeBytes ?? null });
		try {
			const bytes = await fetchBytes(url, { maxBytes: cap, signal: c.signal, onProgress: (received, total) => setState({ s: 'loading', received, total: total ?? entry.sizeBytes ?? null }) });
			let sha: string | null = null;
			let shaOk: boolean | null = null;
			try {
				sha = await sha256Hex(bytes);
				shaOk = entry.sha256 ? sha === entry.sha256 : null;
			} catch {
				/* no subtle crypto (http) */
			}
			setState({ s: 'ready', bytes, sha, shaOk });
		} catch (e) {
			if (e instanceof NetError && e.kind === 'aborted') setState({ s: 'idle' });
			else setState({ s: 'error', message: e instanceof NetError ? e.message : String(e) });
		}
	};

	useEffect(() => {
		if (auto && state.s === 'idle' && url) void load();
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [url, auto]);

	if (!url) return null;
	const over = entry.sizeBytes != null && entry.sizeBytes > cap;

	return (
		<section class="st-preview">
			<div class="st-preview-head">
				<h2>What's inside</h2>
				{state.s === 'ready' && (
					<span class="st-small st-muted">
						{fmtBytes(state.bytes.byteLength)}
						{state.shaOk === true && <span class="st-pill st-pill-ok" style="margin-left:0.5rem"><IconCheck style="width:0.8rem;height:0.8rem" /> checksum matches</span>}
						{state.shaOk === false && <span class="st-pill st-pill-err" style="margin-left:0.5rem">checksum mismatch</span>}
						{state.shaOk === null && entry.sha256 == null && <span class="st-pill st-pill-muted" style="margin-left:0.5rem">unverified</span>}
					</span>
				)}
			</div>
			<div class="st-preview-body">
				{state.s === 'idle' && (
					<div class="st-pv-pad st-row" style="justify-content:space-between">
						<span class="st-small st-muted">
							{over ? `The file is ${fmtBytes(entry.sizeBytes)}, over the ${fmtBytes(cap)} preview cap; download it instead.` : `Fetch the ${info.singular.toLowerCase()} file (${entry.sizeBytes != null ? fmtBytes(entry.sizeBytes) : 'size unknown'}) into this page to look inside. Nothing is installed.`}
						</span>
						{!over && (
							<button class="st-btn st-btn-sm" onClick={load}><IconDownload /> Load preview</button>
						)}
					</div>
				)}
				{state.s === 'loading' && (
					<div class="st-pv-pad">
						<Spinner label={`Fetching ${payloadFileName(entry)}… ${fmtBytes(state.received)}${state.total ? ` of ${fmtBytes(state.total)}` : ''}`} />
						{state.total && (
							<div class="st-progress" style="margin-top:0.6rem"><i style={{ width: `${Math.min(100, (state.received / state.total) * 100)}%` }} /></div>
						)}
						<div style="margin-top:0.6rem"><button class="st-btn st-btn-sm st-btn-ghost" onClick={() => ctl?.abort()}>Cancel</button></div>
					</div>
				)}
				{state.s === 'error' && (
					<div class="st-pv-pad">
						<Notice kind="err" icon={<IconWarn />}>
							<b>Couldn't load the file.</b> {state.message}
						</Notice>
						<div style="margin-top:0.6rem"><button class="st-btn st-btn-sm" onClick={load}>Try again</button></div>
					</div>
				)}
				{state.s === 'ready' && <PayloadView entry={entry} bytes={state.bytes} />}
			</div>
		</section>
	);
}

/** Fetch + render a payload with no section chrome; Compare cells use it. */
export function PayloadLoader({ repo, entry, sample }: { repo: LoadedRepo; entry: AddonEntry; sample?: string }) {
	const url = payloadUrl(repo.ref.url, entry);
	const info = typeInfo(entry.type);
	const cap = Math.min(info.maxBytes || PREVIEW_MAX_BYTES, PREVIEW_MAX_BYTES);
	const [state, setState] = useState<State>({ s: 'idle' });
	useEffect(() => {
		if (!url) return;
		const c = new AbortController();
		setState({ s: 'loading', received: 0, total: entry.sizeBytes ?? null });
		fetchBytes(url, { maxBytes: cap, signal: c.signal, onProgress: (received, total) => setState({ s: 'loading', received, total }) })
			.then((bytes) => setState({ s: 'ready', bytes, sha: null, shaOk: null }))
			.catch((e) => { if (!(e instanceof NetError && e.kind === 'aborted')) setState({ s: 'error', message: e instanceof NetError ? e.message : String(e) }); });
		return () => c.abort();
	}, [url, cap, entry.sizeBytes]);
	if (!url) return <div class="st-pv-pad st-small st-muted">Payload path refused.</div>;
	if (state.s === 'loading') return <div class="st-pv-pad"><Spinner label={`${fmtBytes(state.received)}${state.total ? ` of ${fmtBytes(state.total)}` : ''}`} /></div>;
	if (state.s === 'error') return <div class="st-pv-pad"><Notice kind="err" icon={<IconWarn />}>{state.message}</Notice></div>;
	if (state.s === 'ready') return <PayloadView entry={entry} bytes={state.bytes} sample={sample} />;
	return null;
}

function PayloadView({ entry, bytes, sample }: { entry: AddonEntry; bytes: Uint8Array; sample?: string }) {
	const [node, setNode] = useState<preact.ComponentChildren>(<div class="st-pv-pad"><Spinner label="Reading…" /></div>);
	useEffect(() => {
		let cancelled = false;
		(async () => {
			try {
				const name = payloadFileName(entry);
				const text = () => new TextDecoder('utf-8').decode(bytes);
				let out: preact.ComponentChildren;
				switch (entry.type) {
					case 'theme': out = <ThemeMock read={readTheme(text())} />; break;
					case 'layout': out = <LayoutViewer read={readLayout(text())} />; break;
					case 'snippets': out = <SnippetPreview read={readSnippets(text())} />; break;
					case 'espanso': out = <EspansoPreview read={readEspanso(text())} />; break;
					case 'stickers': out = <StickerPreview read={readStickerPack(bytes)} />; break;
					case 'dynamic_stickers': out = <DynStickerPreview read={readDynStickerPack(bytes)} sample={sample} />; break;
					case 'sound_pack': out = <SoundPackPreview read={readSoundPack(bytes)} />; break;
					case 'sound': out = <SoundPreview read={readSound(bytes, name)} />; break;
					case 'icon_pack': out = <IconPackPreview read={readIconPack(bytes, ICON_SLOT_IDS)} />; break;
					case 'font':
					case 'emoji_font': out = <FontPreview read={readFont(bytes)} emoji={entry.type === 'emoji_font'} sample={sample} />; break;
					case 'plugin': out = <PluginPreview read={readPlugin(bytes)} />; break;
					case 'dictionary': out = <DictionaryPreview read={await readDictionary(bytes, name)} />; break;
					case 'emoji_keywords': out = <EmojiKeywordPreview read={await readEmojiKeywords(bytes, name)} />; break;
					case 'vocabulary': out = <VocabularyPreview read={await readVocabulary(bytes, name)} />; break;
					default: out = <div class="st-pv-pad st-muted st-small">No preview for this type.</div>;
				}
				if (!cancelled) setNode(out);
			} catch (e) {
				if (!cancelled) setNode(<div class="st-pv-pad"><Notice kind="err" icon={<IconWarn />}><b>The app would refuse this file.</b> {(e as Error).message}</Notice></div>);
			}
		})();
		return () => {
			cancelled = true;
		};
	}, [entry, bytes, sample]);
	return <>{node}</>;
}

export function Problems({ items }: { items: string[] }) {
	if (!items.length) return null;
	return (
		<div class="st-pv-pad" style="padding-bottom:0">
			<Notice kind="warn" icon={<IconWarn />}>
				<ul style="padding-left:1rem">{items.map((p, i) => <li key={i}>{p}</li>)}</ul>
			</Notice>
		</div>
	);
}

/**
 * Readers for every payload the store can preview, each mirroring what the
 * app's own importer accepts (see the codec noted on each). They return a
 * plain description of the content; rendering lives in ui/previews.
 */
import { gunzipMaybe } from './net';
import { readZip, textOf, zipFind, mimeFor, blobUrl, type ZipEntry } from './zip';

/* ---------- theme (.wmtheme.json, bare ThemeSpec) ---------- */

export interface Gradient {
	colors: number[];
	type?: 'LINEAR' | 'RADIAL' | 'SWEEP';
	angleDeg?: number;
}

export interface KeyOverride {
	background?: number | null;
	text?: number | null;
	border?: number | null;
	popupBackground?: number | null;
	popupText?: number | null;
}

export interface Decal {
	id: string;
	x?: number;
	y?: number;
	scale?: number;
	rotationDeg?: number;
	opacity?: number;
}

/** The ThemeSpec fields the mock renders; anything else rides along untouched. */
export interface ThemeSpec {
	id: string;
	name: string;
	dark?: boolean;
	boardBackground?: number;
	boardGradient?: Gradient | null;
	backgroundImageBase64?: string | null;
	backgroundImageOpacity?: number;
	backgroundImageBlur?: number;
	keyShape?: string;
	keyTextureScale?: string | null;
	keyTextureOpacity?: number;
	keyGradient?: Gradient | null;
	keyBackground?: number;
	keyText?: number;
	modifierKeyBackground?: number;
	modifierKeyText?: number | null;
	hintText?: number | null;
	enterKeyBackground?: number;
	enterKeyText?: number;
	pressedKeyBackground?: number | null;
	keyBorderColor?: number | null;
	keyBorderWidthDp?: number;
	accent?: number;
	gestureTrailColor?: number | null;
	popupBackground?: number | null;
	popupText?: number | null;
	toolbarIcon?: number | null;
	toolCircleBackground?: number | null;
	toolCircleActiveBackground?: number | null;
	toolBorderColor?: number | null;
	toolBorderWidthDp?: number;
	suggestionBarBackground?: number | null;
	chipBackground?: number | null;
	chipText?: number | null;
	suggestionText?: number | null;
	chipActiveBackground?: number | null;
	chipActiveText?: number | null;
	chipShape?: string | null;
	chipCornerRadiusDp?: number | null;
	keyCornerRadiusDp?: number | null;
	toolCircleRadiusDp?: number | null;
	toolShape?: string | null;
	popupShape?: string | null;
	fontScale?: number | null;
	boldKeyLabels?: boolean | null;
	keyGapScale?: number | null;
	animation?: string;
	animationSpeed?: number;
	fontId?: string | null;
	scriptFontIds?: Record<string, string>;
	soundStyle?: string | null;
	soundCustomId?: string | null;
	decals?: Decal[];
	keyEffect?: string | null;
	keyEffectParam?: string | null;
	keyEffectIntensity?: number;
	keyOverrides?: Record<string, KeyOverride>;
	assets?: Record<string, string>;
	familyName?: string | null;
	variants?: ThemeSpec[];
	[extra: string]: unknown;
}

export const KEY_SHAPES = ['NONE', 'ROUNDED', 'SHARP', 'PILL', 'CUT', 'SQUIRCLE', 'ARCH', 'LEAF', 'SLANT', 'HEXAGON', 'SCALLOP', 'TICKET', 'CIRCLE'] as const;
export const GRADIENT_TYPES = ['LINEAR', 'RADIAL', 'SWEEP'] as const;
export const THEME_ANIMATIONS = ['NONE', 'FLOW', 'HUE_CYCLE'] as const;
export const KEY_EFFECTS = ['STARS', 'HEARTS', 'SPARKLE', 'CONFETTI', 'EMOJI', 'CUSTOM_IMAGE'] as const;
export const KEY_EFFECT_COLORS = ['NATURAL', 'KEY_TEXT', 'ACCENT', 'GESTURE_TRAIL', 'CUSTOM', 'RANDOM'] as const;

export interface ThemeReadResult {
	spec: ThemeSpec;
	/** Things the app's strict codec would choke on (unknown enum constants). */
	problems: string[];
	variants: number;
	assetCount: number;
	assetBytes: number;
}

export function readTheme(text: string): ThemeReadResult {
	const raw = JSON.parse(text);
	if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new Error('Not a JSON object.');
	const spec = raw as ThemeSpec;
	const problems: string[] = [];
	if (typeof spec.id !== 'string' || !spec.id) problems.push('"id" is missing.');
	if (typeof spec.name !== 'string' || !spec.name) problems.push('"name" is missing.');
	if (spec.keyShape != null && !(KEY_SHAPES as readonly string[]).includes(spec.keyShape)) {
		problems.push(`"keyShape": "${spec.keyShape}" is not a shape the app knows; the app refuses the whole theme.`);
	}
	if (spec.animation != null && !(THEME_ANIMATIONS as readonly string[]).includes(spec.animation)) {
		problems.push(`"animation": "${spec.animation}" is unknown; the app refuses the whole theme.`);
	}
	for (const g of [spec.boardGradient, spec.keyGradient]) {
		if (g && g.type != null && !(GRADIENT_TYPES as readonly string[]).includes(g.type)) {
			problems.push(`gradient type "${g.type}" is unknown; the app refuses the whole theme.`);
		}
	}
	const assets = spec.assets && typeof spec.assets === 'object' ? Object.values(spec.assets) : [];
	const assetBytes = assets.reduce((n, b) => n + (typeof b === 'string' ? Math.floor((b.length * 3) / 4) : 0), 0) + (spec.backgroundImageBase64 ? Math.floor((spec.backgroundImageBase64.length * 3) / 4) : 0);
	return { spec, problems, variants: Array.isArray(spec.variants) ? spec.variants.length : 0, assetCount: assets.length + (spec.backgroundImageBase64 ? 1 : 0), assetBytes };
}

/* ---------- layout (.wmlayout.json) ---------- */

export interface LayoutKey {
	label: string;
	output?: string | null;
	shiftLabel?: string | null;
	action?: { type: string; [k: string]: unknown };
	width?: number;
	rowSpan?: number;
	longPress?: string[];
	actionAlternates?: { action: { type: string; [k: string]: unknown }; label?: string; icon?: string | null }[];
	icon?: string | null;
	iconHint?: string | null;
	hideHint?: boolean;
	forceHint?: boolean;
	repeatOnHold?: boolean;
	flick?: Record<string, string>;
	labelScale?: number | null;
	letters?: string | null;
	role?: string | null;
	[extra: string]: unknown;
}

export interface LayerSpec {
	rows: LayoutKey[][];
	numberRow?: LayoutKey[] | null;
	rowHeights?: number[] | null;
	fontScale?: number | null;
	persistent?: boolean;
	themeId?: string | null;
}

export interface LayoutSpec {
	id: string;
	name: string;
	langId?: string;
	composer?: string | null;
	layers: Record<string, LayerSpec>;
	proximityRows?: string[] | null;
	tabletExpand?: boolean;
	secondary?: boolean;
	themeId?: string | null;
	version?: number;
	appearance?: { fontId?: string | null; fontScale?: number | null } | null;
	keyman?: { keyboardId: string; version?: string } | null;
	[extra: string]: unknown;
}

export interface LayoutEnvelope {
	format: 'wmkeyboard-layout';
	version: number;
	appVersion?: number;
	appVersionName?: string;
	layout: LayoutSpec;
}

export const LAYOUT_FORMAT = 'wmkeyboard-layout';
export const LAYER_KEYS = ['letters', 'symbols', 'symbols2', 'number', 'phone', 'date', 'time', 'datetime', 'fn'] as const;
export const PANEL_LAYER_KEYS = ['panel_emoji', 'panel_clipboard', 'panel_text_edit', 'panel_trackpad', 'panel_numpad'] as const;
export const LAYER_LABELS: Record<string, string> = {
	letters: 'Letters',
	symbols: 'Symbols',
	symbols2: 'Symbols 2',
	number: 'Number',
	phone: 'Phone',
	date: 'Date',
	time: 'Time',
	datetime: 'Date & time',
	fn: 'Fn',
	panel_emoji: 'Emoji panel',
	panel_clipboard: 'Clipboard panel',
	panel_text_edit: 'Text-edit panel',
	panel_trackpad: 'Trackpad panel',
	panel_numpad: 'Numpad panel',
};

export const KEY_ACTION_TYPES = [
	'text', 'shift', 'caps_lock', 'delete', 'forward_delete', 'space', 'enter', 'newline', 'symbols', 'letters',
	'language_switch', 'input_method_picker', 'emoji', 'numpad', 'fn', 'kana_variant', 'add_stroke', 'double_consonant', 'morse_dot', 'morse_dash', 'none',
	'tool', 'layout', 'mod', 'send_key', 'broadcast', 'braille_dot', 'keyman_key', 'field', 'edit',
] as const;

/** `KeyAction.fallbackLabel`: what the app draws on an unlabelled key. */
export function keyFallbackLabel(action: { type: string; [k: string]: unknown } | undefined): string {
	const t = action?.type ?? 'text';
	switch (t) {
		case 'space': return '';
		case 'symbols': return '?123';
		case 'letters': return 'ABC';
		case 'numpad': return '123';
		case 'fn': return 'Fn';
		case 'kana_variant': return '小';
		case 'add_stroke': return '획추가';
		case 'double_consonant': return '쌍자음';
		case 'morse_dot': return '·';
		case 'morse_dash': return '–';
		case 'newline': return '⏎';
		case 'shift': return '⇧';
		case 'caps_lock': return '⇪';
		case 'delete': return '⌫';
		case 'forward_delete': return '⌦';
		case 'enter': return '↵';
		case 'language_switch': return '🌐';
		case 'input_method_picker': return '⌨';
		case 'emoji': return '☺';
		case 'mod': return String(action?.key ?? 'Ctrl').charAt(0) + String(action?.key ?? 'ctrl').slice(1).toLowerCase();
		case 'send_key': {
			const code = Number(action?.keyCode);
			return ({ 61: '⇥', 111: 'esc', 19: '↑', 20: '↓', 21: '←', 22: '→' } as Record<number, string>)[code] ?? '⌨';
		}
		case 'braille_dot': return String(action?.dot ?? '');
		case 'broadcast': return '⚡';
		case 'layout': return '▦';
		case 'tool': return '🛠';
		case 'edit': return String(action?.op ?? 'edit').toLowerCase();
		case 'field': return String(action?.kind ?? 'field');
		case 'none': return '';
		default: return t === 'text' ? '' : '?';
	}
}

/** True for the key kinds the app draws in the modifier colour. */
export function isModifierAction(action: { type: string } | undefined): boolean {
	const t = action?.type ?? 'text';
	return !['text', 'space', 'enter', 'none', 'braille_dot', 'morse_dot', 'morse_dash'].includes(t);
}

export interface LayoutReadResult {
	envelope: LayoutEnvelope;
	layout: LayoutSpec;
	problems: string[];
	keyCount: number;
	layerKeys: string[];
}

export function readLayout(text: string): LayoutReadResult {
	const raw = JSON.parse(text);
	if (!raw || typeof raw !== 'object') throw new Error('Not a JSON object.');
	const problems: string[] = [];
	let envelope: LayoutEnvelope;
	if (raw.format === LAYOUT_FORMAT) {
		envelope = raw as LayoutEnvelope;
	} else if (raw.format === 'wmkeyboard-layer') {
		throw new Error('This is a single-layer clipboard export ("wmkeyboard-layer"), not a layout. Wrap it: the app installs only "wmkeyboard-layout" files.');
	} else if (raw.layers && raw.id) {
		problems.push('No envelope: a bare LayoutSpec. The app expects {"format":"wmkeyboard-layout","version":1,"layout":{…}}.');
		envelope = { format: LAYOUT_FORMAT, version: 1, layout: raw as LayoutSpec };
	} else {
		throw new Error(`"format" is ${JSON.stringify(raw.format)}, expected "${LAYOUT_FORMAT}".`);
	}
	const layout = envelope.layout;
	if (!layout || typeof layout !== 'object') throw new Error('"layout" is missing.');
	if (!layout.layers || typeof layout.layers !== 'object') throw new Error('"layout.layers" is missing.');
	if (!layout.layers.letters) problems.push('No "letters" layer: the only layer the app requires.');
	let keyCount = 0;
	for (const [k, layer] of Object.entries(layout.layers)) {
		if (!layer || !Array.isArray(layer.rows)) {
			problems.push(`Layer "${k}" has no rows.`);
			continue;
		}
		for (const row of layer.rows) if (Array.isArray(row)) keyCount += row.length;
	}
	return { envelope, layout, problems, keyCount, layerKeys: Object.keys(layout.layers) };
}

/* ---------- snippets (.wmsnippets.json) ---------- */

export interface Snippet {
	id: number;
	label: string;
	text: string;
	createdAt?: number;
	trigger?: string | null;
	aliases?: string[];
	propagateCase?: boolean;
	uppercaseStyle?: 'capitalize' | 'capitalize_words' | 'uppercase';
	triggerPattern?: string | null;
	triggerWords?: number;
	confirm?: boolean;
	folderId?: number;
	alternates?: string[];
	children?: number[];
	tags?: string[];
	multiExpand?: 'default' | 'chips_only' | 'insert_first';
}

export interface SnippetFolder {
	id: number;
	name: string;
	enabled?: boolean;
	createdAt?: number;
	icon?: string | null;
}

export interface SnippetEnvelope {
	format: 'wmkeyboard-snippets';
	version: number;
	appVersion?: number;
	appVersionName?: string;
	snippets: Snippet[];
	folders?: SnippetFolder[];
}

export const SNIPPETS_FORMAT = 'wmkeyboard-snippets';

export function readSnippets(text: string): { envelope: SnippetEnvelope; problems: string[] } {
	const raw = JSON.parse(text);
	if (!raw || typeof raw !== 'object') throw new Error('Not a JSON object.');
	if (raw.format !== SNIPPETS_FORMAT) throw new Error(`"format" is ${JSON.stringify(raw.format)}, expected "${SNIPPETS_FORMAT}".`);
	const problems: string[] = [];
	const snippets: Snippet[] = Array.isArray(raw.snippets) ? raw.snippets.filter((s: Snippet) => s && typeof s === 'object') : [];
	if (snippets.length > 500) problems.push(`${snippets.length} snippets; the app imports at most 500.`);
	for (const s of snippets) {
		if (typeof s.text !== 'string' || !s.text.trim()) problems.push(`Snippet ${s.id ?? '?'} has no text and will be dropped.`);
		if (s.triggerPattern) {
			try {
				new RegExp(s.triggerPattern);
			} catch {
				problems.push(`Snippet "${s.label}": pattern does not compile; the app keeps the snippet and drops the pattern.`);
			}
		}
	}
	return { envelope: { ...raw, snippets, folders: Array.isArray(raw.folders) ? raw.folders : [] }, problems };
}

/* ---------- espanso (yaml) ---------- */

export interface EspansoMatch {
	trigger?: string;
	triggers?: string[];
	replace?: string;
	regex?: string;
	word?: boolean;
	propagate_case?: boolean;
}

/** A small YAML subset reader: enough for `matches:` lists of scalar maps. Not a YAML parser. */
export function readEspanso(text: string): { matches: EspansoMatch[]; problems: string[] } {
	const problems: string[] = [];
	const matches: EspansoMatch[] = [];
	const lines = text.split(/\r?\n/);
	let inMatches = false;
	let current: EspansoMatch | null = null;
	for (const line of lines) {
		if (/^\s*#/.test(line) || !line.trim()) continue;
		if (/^matches\s*:/.test(line)) {
			inMatches = true;
			continue;
		}
		if (!inMatches) continue;
		if (/^\S/.test(line)) {
			inMatches = false;
			continue;
		}
		const item = line.match(/^\s*-\s+(.*)$/);
		const kv = (item ? item[1]! : line.trim()).match(/^([a-z_]+)\s*:\s*(.*)$/);
		if (item) {
			current = {};
			matches.push(current);
		}
		if (!current || !kv) continue;
		const [, key, rawVal] = kv;
		let val: unknown = rawVal!.trim();
		if (typeof val === 'string') {
			if ((val.startsWith('"') && val.endsWith('"')) || (val.startsWith("'") && val.endsWith("'"))) val = val.slice(1, -1).replace(/\\n/g, '\n');
			else if (val === 'true') val = true;
			else if (val === 'false') val = false;
			else if (val.startsWith('[') && val.endsWith(']')) val = val.slice(1, -1).split(',').map((s) => s.trim().replace(/^["']|["']$/g, '')).filter(Boolean);
			else if (val === '|' || val === '>' || val === '') val = '(multi-line)';
		}
		(current as Record<string, unknown>)[key!] = val;
	}
	if (!matches.length) problems.push('No "matches:" list found. Espanso packages need a matches list; the app converts those into snippets.');
	return { matches, problems };
}

/* ---------- stickers (.wmstickers zip) ---------- */

export interface StickerInfo {
	id: string;
	name: string;
	fileName: string;
	mime: string;
	url: string | null;
	bytes: number;
	emojis: string[];
}

export interface StickerPackRead {
	id: string;
	name: string;
	author: string;
	description: string;
	stickers: StickerInfo[];
	problems: string[];
	totalBytes: number;
}

export function readStickerPack(bytes: Uint8Array): StickerPackRead {
	const entries = readZip(bytes, 600);
	const manifest = zipFind(entries, 'pack.json');
	if (!manifest) throw new Error('No pack.json in the archive.');
	const raw = JSON.parse(textOf(manifest));
	const problems: string[] = [];
	if (raw.format !== 'wmkeyboard-stickers') problems.push(`"format" is ${JSON.stringify(raw.format)}, expected "wmkeyboard-stickers".`);
	const pack = raw.pack && typeof raw.pack === 'object' ? raw.pack : {};
	const list: Record<string, unknown>[] = Array.isArray(pack.stickers) ? pack.stickers : Array.isArray(raw.stickers) ? raw.stickers : [];
	const stickers: StickerInfo[] = [];
	let total = 0;
	for (const s of list) {
		const fileName = String(s.fileName ?? s.file ?? '');
		const base = fileName.split('/').pop() ?? '';
		const entry = entries.find((e) => e.name === fileName || e.name === `stickers/${base}` || e.name.endsWith('/' + base));
		if (!entry) {
			problems.push(`Sticker "${s.id ?? base}" names ${fileName || '(nothing)'} but the archive has no such file.`);
			continue;
		}
		const mime = String(s.mime ?? mimeFor(base));
		total += entry.data.byteLength;
		stickers.push({
			id: String(s.id ?? base),
			name: String(s.name ?? ''),
			fileName,
			mime,
			url: entry.data.byteLength <= 4 * 1024 * 1024 ? blobUrl(entry.data, mime) : null,
			bytes: entry.data.byteLength,
			emojis: Array.isArray(s.emojis) ? s.emojis.map(String) : [],
		});
	}
	if (!stickers.length) problems.push('The pack lists no stickers the archive contains.');
	if (entries.length > 500) problems.push(`${entries.length} archive entries; the app caps at 500.`);
	return { id: String(pack.id ?? ''), name: String(pack.name ?? ''), author: String(pack.author ?? ''), description: String(pack.description ?? ''), stickers, problems, totalBytes: total };
}

/* ---------- sound pack (.wmsoundpack zip) ---------- */

export interface SoundSample {
	path: string;
	url: string | null;
	bytes: number;
}

export interface SoundPackRead {
	id: string;
	name: string;
	author: string;
	description: string;
	packVersion: string;
	gain: number;
	press: SoundSample[];
	release: SoundSample[];
	roles: Record<string, { press: SoundSample[]; release: SoundSample[]; gain: number | null }>;
	/** Per-key sets, keyed by the text the key types (lowercased). */
	keys: Record<string, { press: SoundSample[]; release: SoundSample[]; gain: number | null }>;
	problems: string[];
}

export const SOUND_ROLES = ['default', 'space', 'enter', 'delete', 'modifier'] as const;

/** `SoundPackFile.MAX_SAMPLES`. */
export const SOUND_PACK_MAX_SAMPLES = 512;

/** `SoundPackFile.MAX_KEYS`. */
export const SOUND_PACK_MAX_KEYS = 256;

/** `SoundPackFile.MAX_VARIANTS`, per list. */
export const SOUND_PACK_MAX_VARIANTS = 32;

export function readSoundPack(bytes: Uint8Array): SoundPackRead {
	// 1024 is the importer's own entry cap; a per-key pack is one recording per
	// letter, so the old 200 would have thrown on a pack the app accepts.
	const entries = readZip(bytes, 1024);
	const manifest = zipFind(entries, 'pack.json');
	if (!manifest) throw new Error('No pack.json in the archive.');
	const raw = JSON.parse(textOf(manifest));
	const problems: string[] = [];
	if (raw.format !== 'wmkeyboard-sound-pack') problems.push(`"format" is ${JSON.stringify(raw.format)}, expected "wmkeyboard-sound-pack".`);
	if (typeof raw.version === 'number' && raw.version > 1) problems.push(`"version": ${raw.version} is newer than the app understands (1); the app refuses it.`);
	const sample = (p: unknown): SoundSample => {
		const path = String(p);
		const e = entries.find((x) => x.name === path || x.name === path.replace(/^\.?\//, ''));
		if (!e) {
			problems.push(`"${path}" is listed but not in the archive.`);
			return { path, url: null, bytes: 0 };
		}
		return { path, url: blobUrl(e.data, mimeFor(path)), bytes: e.data.byteLength };
	};
	const list = (v: unknown): SoundSample[] => (Array.isArray(v) ? v.map(sample) : []);
	const press = list(raw.press);
	const release = list(raw.release);
	const roles: SoundPackRead['roles'] = {};
	if (raw.roles && typeof raw.roles === 'object') {
		for (const [k, v] of Object.entries(raw.roles as Record<string, Record<string, unknown>>)) {
			if (k === 'default' || !(SOUND_ROLES as readonly string[]).includes(k)) {
				problems.push(`Role "${k}" is ignored by the app${k === 'default' ? ' (the top-level press/release is the default)' : ''}.`);
				continue;
			}
			roles[k] = { press: list(v?.press), release: list(v?.release), gain: typeof v?.gain === 'number' ? v.gain : null };
		}
	}
	// Per-key sets, keyed by the text a key types. Lowercased here because the
	// importer lowercases them, so a pack shipping "A" and "a" ends up with one
	// entry on the device and should read as one here too.
	const keys: SoundPackRead['keys'] = {};
	if (raw.keys && typeof raw.keys === 'object') {
		const named = Object.entries(raw.keys as Record<string, Record<string, unknown>>);
		if (named.length > SOUND_PACK_MAX_KEYS) {
			problems.push(`"keys" names ${named.length} keys; the app keeps the first ${SOUND_PACK_MAX_KEYS} and drops the rest.`);
		}
		for (const [rawKey, v] of named.slice(0, SOUND_PACK_MAX_KEYS)) {
			const k = rawKey.toLowerCase();
			if (!k) {
				problems.push('"keys" has an empty key name; the app drops it.');
				continue;
			}
			if (keys[k]) {
				problems.push(`"keys" names "${k}" twice once cased down; the app keeps one of them.`);
			}
			keys[k] = { press: list(v?.press), release: list(v?.release), gain: typeof v?.gain === 'number' ? v.gain : null };
		}
	}
	// The app refuses a pack only when nothing plays on key-down anywhere: a
	// per-key pack with no board-wide set is legal, though keys it does not
	// name then fall through to the system click.
	const anyPress = press.some((s) => s.url) || Object.values(keys).some((k) => k.press.some((s) => s.url));
	if (!anyPress) {
		problems.push('Nothing resolves to a playable key-down sample; the app refuses the pack.');
	} else if (!press.some((s) => s.url) && Object.keys(keys).length) {
		problems.push('No top-level "press": keys this pack does not name play the system click rather than the pack.');
	}
	const setsOf = (m: SoundPackRead['keys']) => Object.values(m).reduce((n, r) => n + r.press.length + r.release.length, 0);
	const count = press.length + release.length + setsOf(roles) + setsOf(keys);
	if (count > SOUND_PACK_MAX_SAMPLES) problems.push(`${count} samples; the app caps at ${SOUND_PACK_MAX_SAMPLES}.`);
	return {
		id: String(raw.id ?? ''),
		name: String(raw.name ?? ''),
		author: String(raw.author ?? ''),
		description: String(raw.description ?? ''),
		packVersion: String(raw.packVersion ?? '1.0.0'),
		gain: typeof raw.gain === 'number' ? raw.gain : 1,
		press,
		release,
		roles,
		keys,
		problems,
	};
}

/* ---------- icon pack (.wmicons zip) ---------- */

export interface IconPackRead {
	id: string;
	name: string;
	author: string;
	description: string;
	version: string;
	slots: string[];
	/**
	 * One per icon the archive holds. `svg` is the source of a vector icon and
	 * empty for a picture; `url` is a blob: URL for a picture and null for a
	 * vector. `known` is whether the app has the slot (a variant key,
	 * `tool.gif@text`, counts by its slot).
	 */
	icons: { slot: string; svg: string; url: string | null; bytes: number; known: boolean }[];
	problems: string[];
}

/** `RasterIcons.FILE_EXTENSIONS`: what an icon file may be, vectors first. */
export const ICON_FILE_EXTENSIONS = ['svg', 'png', 'webp', 'jpg', 'gif'] as const;

/**
 * `IconPackFile.slotForEntry`: an entry's slot key from its base name, so an
 * icon at the archive root counts as well as one under icons/. Lowercased, as
 * the importer does.
 */
function iconSlotForEntry(name: string): { slot: string; ext: string } | null {
	const base = name.split('/').pop()?.split('\\').pop() ?? '';
	const ext = ICON_FILE_EXTENSIONS.find((e) => base.toLowerCase().endsWith(`.${e}`));
	if (!ext) return null;
	return { slot: base.slice(0, -(ext.length + 1)).toLowerCase(), ext };
}

export function readIconPack(bytes: Uint8Array, knownSlots: ReadonlySet<string>): IconPackRead {
	const entries = readZip(bytes, 450);
	const manifest = zipFind(entries, 'pack.json');
	if (!manifest) throw new Error('No pack.json in the archive.');
	const raw = JSON.parse(textOf(manifest));
	const problems: string[] = [];
	if (raw.format !== 'wmkeyboard-icons') problems.push(`"format" is ${JSON.stringify(raw.format)}, expected "wmkeyboard-icons".`);
	const pack = raw.pack && typeof raw.pack === 'object' ? raw.pack : {};
	const slots: string[] = Array.isArray(pack.slots) ? pack.slots.map(String) : [];
	// One file per slot; where a hand-built pack has two, the vector wins, as on the phone.
	const bySlot = new Map<string, { entry: ZipEntry; ext: string }>();
	for (const entry of entries) {
		const hit = entry.name === 'pack.json' ? null : iconSlotForEntry(entry.name);
		if (!hit) continue;
		const had = bySlot.get(hit.slot);
		if (!had || ICON_FILE_EXTENSIONS.indexOf(hit.ext as never) < ICON_FILE_EXTENSIONS.indexOf(had.ext as never)) bySlot.set(hit.slot, { entry, ext: hit.ext });
	}
	const order = [...slots, ...[...bySlot.keys()].filter((s) => !slots.includes(s))];
	const icons = order
		.map((slot) => {
			const found = bySlot.get(slot);
			if (!found) {
				problems.push(`Slot "${slot}" is listed but the archive has no icon for it.`);
				return null;
			}
			const { entry, ext } = found;
			const vector = ext === 'svg';
			return {
				slot,
				svg: vector ? textOf(entry) : '',
				url: vector ? null : blobUrl(entry.data, mimeFor(`x.${ext}`)),
				bytes: entry.data.byteLength,
				known: knownSlots.has(slot.split('@')[0]!),
			};
		})
		.filter((x): x is NonNullable<typeof x> => !!x);
	const unknown = icons.filter((i) => !i.known).length;
	if (unknown) problems.push(`${unknown} icon${unknown === 1 ? '' : 's'} name${unknown === 1 ? 's' : ''} a slot this app version does not have (ignored on install).`);
	if (entries.length > 400) problems.push(`${entries.length} archive entries; the app caps at 400.`);
	return { id: String(pack.id ?? ''), name: String(pack.name ?? ''), author: String(pack.author ?? ''), description: String(pack.description ?? ''), version: String(pack.version ?? ''), slots, icons, problems };
}

/* ---------- plugin (.wmplugin zip) ---------- */

export interface PluginRead {
	manifest: {
		id: string;
		name: string;
		pluginVersion: string;
		author: string;
		description: string;
		apiVersion: number;
		entry: string;
		permissions: string[];
		language: string;
		actions: { id: string; name: string }[];
	};
	files: { name: string; bytes: number; text: string | null }[];
	problems: string[];
}

export const PLUGIN_API_VERSION = 4;
/** Why the app refuses a Lua plugin (PluginManifestCodec, API 4). */
export const PLUGIN_LUA_REFUSAL = 'This plugin is written in Lua, which WM Keyboard no longer runs; look for its Python version.';
export const PLUGIN_PERMISSIONS = ['storage', 'reminders', 'selection'] as const;
const PLUGIN_ACTION_ID = /^[a-z0-9_-]{1,32}$/;
export const PLUGIN_ID_PATTERN = /^[a-z0-9][a-z0-9._-]{2,63}$/;

export function readPlugin(bytes: Uint8Array): PluginRead {
	const entries = readZip(bytes, 32);
	const manifestEntry = zipFind(entries, 'plugin.json');
	if (!manifestEntry) throw new Error('No plugin.json in the archive.');
	const raw = JSON.parse(textOf(manifestEntry));
	const problems: string[] = [];
	if (raw.format !== 'wmkeyboard-plugin') problems.push(`"format" is ${JSON.stringify(raw.format)}, expected "wmkeyboard-plugin".`);
	const id = String(raw.id ?? '').trim().toLowerCase();
	if (!PLUGIN_ID_PATTERN.test(id)) problems.push(`"id": ${JSON.stringify(raw.id)} must match ${PLUGIN_ID_PATTERN}.`);
	if (!String(raw.name ?? '').trim()) problems.push('"name" is empty.');
	if (!String(raw.pluginVersion ?? '').trim()) problems.push('"pluginVersion" is empty.');
	const apiVersion = typeof raw.apiVersion === 'number' && raw.apiVersion > 0 ? raw.apiVersion : 1;
	if (apiVersion > PLUGIN_API_VERSION) problems.push(`"apiVersion": ${apiVersion} is newer than the app's ${PLUGIN_API_VERSION}; the app refuses it.`);
	const permissions: string[] = Array.isArray(raw.permissions) ? raw.permissions.map(String) : [];
	for (const p of permissions) if (!(PLUGIN_PERMISSIONS as readonly string[]).includes(p)) problems.push(`Permission "${p}" is unknown; the app refuses the plugin.`);
	// PluginManifestCodec (API 4): "language" may be left out, and the only one
	// the app runs is Python. A manifest that says Lua, or leaves the language
	// out at API 3 or lower, is a Lua plugin.
	const named = raw.language === undefined || raw.language === null ? null : String(raw.language).trim();
	const language = named ?? 'python';
	if (named === 'lua' || (named === null && apiVersion <= 3)) problems.push(PLUGIN_LUA_REFUSAL);
	else if (language !== 'python') problems.push(`"language": ${JSON.stringify(raw.language)} is not "python"; the app refuses it.`);
	// Actions need "selection", and "selection" needs actions.
	const actions: { id: string; name: string; input?: unknown }[] = Array.isArray(raw.actions) ? raw.actions : [];
	const selection = permissions.includes('selection');
	if (actions.length && !selection) problems.push('"actions" without the "selection" permission; the app refuses it.');
	if (!actions.length && selection) problems.push('"selection" permission without any "actions"; the app refuses it.');
	if (!actions.length && raw.panel === false) problems.push('No panel and no actions; the app refuses it.');
	if (actions.length > 16) problems.push(`${actions.length} actions; the app caps at 16.`);
	const actionIds = actions.map((a) => String(a?.id ?? '').trim());
	if (actionIds.some((a) => !PLUGIN_ACTION_ID.test(a)) || new Set(actionIds).size !== actionIds.length) problems.push(`Action ids must be unique and match ${PLUGIN_ACTION_ID}.`);
	if (actions.some((a) => !String(a?.name ?? '').trim())) problems.push('An action has no "name".');
	if (actions.some((a) => a?.input !== undefined && a.input !== 'selection' && a.input !== 'none')) problems.push('An action\'s "input" is not "selection" or "none".');
	const entry = String(raw.entry || 'main.py');
	if (!entries.some((e) => e.name === entry)) problems.push(`Entry "${entry}" is not in the archive.`);
	if (entries.length > 16) problems.push(`${entries.length} entries; the app caps at 16.`);
	const scriptBytes = entries.filter((e) => e.name.endsWith('.py')).reduce((n, e) => n + e.data.byteLength, 0);
	if (scriptBytes > 256 * 1024) problems.push(`Python totals ${Math.round(scriptBytes / 1024)} KB; the app caps at 256 KB.`);
	const files = entries.map((e) => ({
		name: e.name,
		bytes: e.data.byteLength,
		text: /\.(py|json|txt|md)$/i.test(e.name) && e.data.byteLength <= 256 * 1024 ? textOf(e) : null,
	}));
	return {
		manifest: {
			id, name: String(raw.name ?? ''), pluginVersion: String(raw.pluginVersion ?? ''), author: String(raw.author ?? ''), description: String(raw.description ?? ''), apiVersion, entry, permissions,
			language, actions: actions.map((a) => ({ id: String(a?.id ?? ''), name: String(a?.name ?? '') })),
		},
		files,
		problems,
	};
}

/* ---------- dictionary (word list) ---------- */

export interface DictionaryRead {
	words: { word: string; freq: number }[];
	total: number;
	skipped: number;
	comments: string[];
	truncated: boolean;
}

export async function readDictionary(bytes: Uint8Array, name: string, maxWords = 10_000): Promise<DictionaryRead> {
	const text = new TextDecoder('utf-8').decode(await gunzipMaybe(bytes, name));
	const words: DictionaryRead['words'] = [];
	const comments: string[] = [];
	let total = 0;
	let skipped = 0;
	let truncated = false;
	for (const line of text.split(/\r?\n/)) {
		const t = line.trim();
		if (!t) continue;
		if (t.startsWith('#')) {
			if (comments.length < 5) comments.push(t.replace(/^#\s?/, ''));
			continue;
		}
		const sp = t.search(/\s/);
		const word = sp < 0 ? t : t.slice(0, sp);
		const freqStr = sp < 0 ? '' : t.slice(sp).trim();
		const freq = freqStr ? Number(freqStr) : 1;
		if (!word || !Number.isFinite(freq)) {
			skipped++;
			continue;
		}
		total++;
		if (words.length < maxWords) words.push({ word, freq });
		else truncated = true;
	}
	return { words, total, skipped, comments, truncated };
}

/* ---------- emoji keywords (tsv) ---------- */

export interface EmojiKeywordRow {
	emoji: string;
	keywords: string[];
	name: string;
}

export async function readEmojiKeywords(bytes: Uint8Array, name: string, maxRows = 2000): Promise<{ rows: EmojiKeywordRow[]; total: number; truncated: boolean }> {
	const text = new TextDecoder('utf-8').decode(await gunzipMaybe(bytes, name));
	const rows: EmojiKeywordRow[] = [];
	let total = 0;
	if (text.trimStart().startsWith('{') || text.trimStart().startsWith('[')) {
		const raw = JSON.parse(text);
		const obj: Record<string, unknown> = Array.isArray(raw) ? Object.fromEntries(raw.map((r: Record<string, unknown>) => [r.emoji, r])) : raw;
		for (const [emoji, v] of Object.entries(obj)) {
			total++;
			const kws = Array.isArray(v) ? v.map(String) : typeof v === 'string' ? v.split(',') : Array.isArray((v as Record<string, unknown>)?.keywords) ? ((v as Record<string, unknown>).keywords as unknown[]).map(String) : [];
			if (rows.length < maxRows) rows.push({ emoji, keywords: kws.map((k) => k.trim()).filter(Boolean), name: String((v as Record<string, unknown>)?.name ?? '') });
		}
	} else {
		for (const line of text.split(/\r?\n/)) {
			if (!line.trim() || line.startsWith('# ')) continue;
			const [emoji, kw, display] = line.split('\t');
			if (!emoji || !kw) continue;
			total++;
			if (rows.length < maxRows) rows.push({ emoji: emoji.trim(), keywords: kw.split(',').map((k) => k.trim()).filter(Boolean), name: (display ?? '').trim() });
		}
	}
	return { rows, total, truncated: total > rows.length };
}

/* ---------- vocabulary (.wmvocab.json[.gz]) ---------- */

export interface VocabWord {
	word: string;
	pos: string[];
	senses: string[];
}

export async function readVocabulary(bytes: Uint8Array, name: string, maxWords = 500): Promise<{ pack: Record<string, unknown>; words: VocabWord[]; total: number; problems: string[] }> {
	const text = new TextDecoder('utf-8').decode(await gunzipMaybe(bytes, name));
	const raw = JSON.parse(text);
	const problems: string[] = [];
	if (raw.format !== 'wmkeyboard-vocab') problems.push(`"format" is ${JSON.stringify(raw.format)}, expected "wmkeyboard-vocab".`);
	const list: Record<string, unknown>[] = Array.isArray(raw.words) ? raw.words : [];
	if (list.length > 5000) problems.push(`${list.length} words; the app caps at 5 000.`);
	const words = list.slice(0, maxWords).map((w) => ({
		word: String(w.word ?? ''),
		pos: Array.isArray(w.pos) ? w.pos.map(String) : [],
		senses: Array.isArray(w.senses) ? w.senses.map((s: unknown) => (typeof s === 'string' ? s : String((s as Record<string, unknown>)?.definition ?? (s as Record<string, unknown>)?.gloss ?? JSON.stringify(s)))) : [],
	}));
	return { pack: raw.pack && typeof raw.pack === 'object' ? raw.pack : {}, words, total: list.length, problems };
}

/* ---------- font ---------- */

export interface FontRead {
	family: string;
	fullName: string;
	style: string;
	version: string;
	glyphs: number | null;
	tables: string[];
	isColor: boolean;
	url: string;
}

/** Reads the sfnt `name` + `maxp` tables — enough to label the font and load it with FontFace. */
export function readFont(bytes: Uint8Array): FontRead {
	const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
	const tag = dv.getUint32(0);
	const isTtf = tag === 0x00010000 || tag === 0x74727565; // 'true'
	const isOtf = tag === 0x4f54544f; // 'OTTO'
	const isCollection = tag === 0x74746366; // 'ttcf'
	if (!isTtf && !isOtf && !isCollection) throw new Error('Not a TrueType/OpenType font (bad sfnt header).');
	const base = isCollection ? dv.getUint32(12) : 0;
	const numTables = dv.getUint16(base + 4);
	const tables: Record<string, { off: number; len: number }> = {};
	for (let i = 0; i < numTables; i++) {
		const rec = base + 12 + i * 16;
		const name = String.fromCharCode(bytes[rec]!, bytes[rec + 1]!, bytes[rec + 2]!, bytes[rec + 3]!);
		tables[name] = { off: dv.getUint32(rec + 8), len: dv.getUint32(rec + 12) };
	}
	const names: Record<number, string> = {};
	const nameT = tables['name'];
	if (nameT) {
		const count = dv.getUint16(nameT.off + 2);
		const strOff = nameT.off + dv.getUint16(nameT.off + 4);
		for (let i = 0; i < count; i++) {
			const r = nameT.off + 6 + i * 12;
			const platform = dv.getUint16(r);
			const nameId = dv.getUint16(r + 6);
			const len = dv.getUint16(r + 8);
			const off = strOff + dv.getUint16(r + 10);
			if (off + len > bytes.byteLength) continue;
			const slice = bytes.subarray(off, off + len);
			let s: string;
			if (platform === 3 || platform === 0) {
				let out = '';
				for (let j = 0; j + 1 < slice.length; j += 2) out += String.fromCharCode((slice[j]! << 8) | slice[j + 1]!);
				s = out;
			} else s = new TextDecoder('latin1').decode(slice);
			if (!names[nameId] || platform === 3) names[nameId] = s;
		}
	}
	const maxp = tables['maxp'];
	const glyphs = maxp ? dv.getUint16(maxp.off + 4) : null;
	const isColor = !!(tables['COLR'] || tables['CBDT'] || tables['sbix'] || tables['SVG ']);
	return {
		family: names[16] ?? names[1] ?? '',
		fullName: names[4] ?? '',
		style: names[17] ?? names[2] ?? '',
		version: names[5] ?? '',
		glyphs,
		tables: Object.keys(tables),
		isColor,
		url: blobUrl(bytes, isOtf ? 'font/otf' : 'font/ttf'),
	};
}

/* ---------- sound (single file) ---------- */

export function readSound(bytes: Uint8Array, name: string): { url: string; kind: string } {
	const b = bytes;
	let kind = 'audio';
	if (b[0] === 0x49 && b[1] === 0x44 && b[2] === 0x33) kind = 'mp3';
	else if (b[0] === 0xff && (b[1]! & 0xe0) === 0xe0) kind = 'mp3';
	else if (b[0] === 0x4f && b[1] === 0x67 && b[2] === 0x67) kind = 'ogg';
	else if (b[0] === 0x52 && b[1] === 0x49 && b[2] === 0x46) kind = 'wav';
	else throw new Error('Not an mp3, ogg or wav file (unrecognised header).');
	return { url: blobUrl(bytes, mimeFor(kind === 'audio' ? name : `x.${kind}`)), kind };
}

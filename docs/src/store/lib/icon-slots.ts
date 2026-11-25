/**
 * `IconSlots` (core/icons/IconSlots.kt) as data: every slot an icon pack can
 * fill, in the app's group order. The tool list mirrors `ToolbarTool` entry
 * order; a pack may name slots the app doesn't have yet (ignored on install).
 */

export interface IconSlot {
	id: string;
	label: string;
	group: 'Tools' | 'Keys' | 'Chrome' | 'Emoji tabs';
}

const TOOLS = [
	'EMOJI', 'CLIPBOARD', 'SNIPPETS', 'TEXT_EDIT', 'TRACKPAD', 'ONE_HANDED', 'SPLIT', 'FLOATING', 'SETTINGS', 'FLASHLIGHT',
	'COMPASS', 'LEVEL', 'UNDO', 'REDO', 'MOON_PHASE', 'WEATHER', 'CALENDAR', 'INCOGNITO', 'THEMES', 'AUTOCORRECT',
	'SOUND_HAPTICS', 'NUMPAD', 'HANDWRITING', 'CAMERA', 'DICTIONARY', 'VOCABULARY', 'TRANSLATE', 'GIF', 'STICKER',
	'WEB_SEARCH', 'IMAGE_SEARCH', 'OCR', 'QR_SCAN', 'DOC_SCAN', 'VOICE', 'GRAMMAR', 'WIKIPEDIA', 'SYMBOLS', 'CALCULATOR',
	'UNIT_CONVERT', 'CURRENCY', 'QR_GEN', 'PASSWORD_GEN', 'AI', 'MODES', 'TYPING_TEST', 'MEDIA_CONTROL', 'PLUGINS',
	'POWER_SAVING', 'APP_LAUNCHER', 'FANCY', 'CUSTOM_LAYOUT', 'RESIZE', 'CURSOR_LEFT', 'CURSOR_RIGHT', 'CURSOR_UP',
	'CURSOR_DOWN', 'CURSOR_HOME', 'CURSOR_END', 'PAGE_UP', 'PAGE_DOWN', 'CURSOR_WORD_LEFT', 'CURSOR_WORD_RIGHT',
	'SELECT_WORD', 'SELECT_LINE', 'SELECT_MODE', 'COPY', 'CUT', 'PASTE', 'HIDE_KEYBOARD', 'PERSISTENT', 'SELECTION_ACTIONS',
];

const TOOL_LABELS: Record<string, string> = {
	TEXT_EDIT: 'Text editing', ONE_HANDED: 'One-handed', MOON_PHASE: 'Moon phase', SOUND_HAPTICS: 'Sound & haptics',
	WEB_SEARCH: 'Web search', IMAGE_SEARCH: 'Image search', QR_SCAN: 'QR scanner', DOC_SCAN: 'Document scanner',
	UNIT_CONVERT: 'Unit converter', QR_GEN: 'QR generator', PASSWORD_GEN: 'Password generator', TYPING_TEST: 'Typing test',
	MEDIA_CONTROL: 'Media control', POWER_SAVING: 'Power saving', APP_LAUNCHER: 'App launcher', FANCY: 'Fancy text',
	CUSTOM_LAYOUT: 'Custom layout', CURSOR_WORD_LEFT: 'Cursor word left', CURSOR_WORD_RIGHT: 'Cursor word right',
	SELECT_MODE: 'Selection mode', HIDE_KEYBOARD: 'Hide keyboard', SELECTION_ACTIONS: 'Selection actions', AI: 'AI',
	OCR: 'OCR', GIF: 'GIF',
};

function humanize(name: string): string {
	return TOOL_LABELS[name] ?? name.charAt(0) + name.slice(1).toLowerCase().replace(/_/g, ' ');
}

const KEYS: [string, string][] = [
	['key.shift', 'Shift'], ['key.shift_on', 'Shift (on)'], ['key.shift_lock', 'Caps lock'], ['key.backspace', 'Backspace'],
	['key.forward_delete', 'Forward delete'], ['key.enter', 'Enter'], ['key.enter_search', 'Enter: search'],
	['key.enter_send', 'Enter: send'], ['key.enter_go', 'Enter: go'], ['key.enter_next', 'Enter: next'],
	['key.enter_previous', 'Enter: previous'], ['key.enter_done', 'Enter: done'], ['key.globe', 'Globe'],
	['key.input_method_picker', 'Input method picker'], ['key.emoji', 'Emoji key'],
];

const CHROME: [string, string][] = [
	['chrome.toolbox', 'Toolbox'], ['chrome.panel_back', 'Panel back'], ['chrome.suggestions_expand', 'Expand suggestions'],
	['chrome.emoji_shortcut', 'Emoji shortcut'], ['chrome.search_close', 'Close search'], ['chrome.incognito', 'Incognito'],
	['chrome.power_saving', 'Power saving'],
];

const EMOJI_TABS: [string, string][] = [
	['emoji_tab.search', 'Search'], ['emoji_tab.recent', 'Recent'], ['emoji_tab.most_used', 'Most used'], ['emoji_tab.suggested', 'Suggested'],
	...['smileys', 'people', 'animals', 'nature', 'food', 'travel', 'activities', 'objects', 'symbols', 'flags'].map(
		(c): [string, string] => [`emoji_tab.${c}`, c.charAt(0).toUpperCase() + c.slice(1)]
	),
];

export const ICON_SLOTS: IconSlot[] = [
	...TOOLS.map((t): IconSlot => ({ id: `tool.${t.toLowerCase()}`, label: humanize(t), group: 'Tools' })),
	...KEYS.map(([id, label]): IconSlot => ({ id, label, group: 'Keys' })),
	...CHROME.map(([id, label]): IconSlot => ({ id, label, group: 'Chrome' })),
	...EMOJI_TABS.map(([id, label]): IconSlot => ({ id, label, group: 'Emoji tabs' })),
];

export const ICON_SLOT_IDS: ReadonlySet<string> = new Set(ICON_SLOTS.map((s) => s.id));

export const ICON_SLOT_GROUPS = ['Tools', 'Keys', 'Chrome', 'Emoji tabs'] as const;

export function slotLabel(id: string): string {
	return ICON_SLOTS.find((s) => s.id === id)?.label ?? id;
}

/** `IconSlots.isWellFormed`. */
export function isWellFormedSlotId(id: string): boolean {
	return !!id && id.length <= 64 && /^[a-z]/.test(id) && !id.includes('..') && /^[a-z0-9._]+$/.test(id);
}

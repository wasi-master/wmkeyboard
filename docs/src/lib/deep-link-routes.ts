/**
 * Every screen a `wmkeyboard://settings/<route>` link can open, as data for
 * the link builder: the route pattern exactly as `SettingsRoutes.all`
 * (app/src/main/java/com/wasimaster/wmkeyboard/app/SettingsRoutes.kt) spells
 * it, a label, and what each `{arg}` segment wants.
 *
 * `node scripts/check-deep-link-routes.mjs` (docs/) parses the Kotlin list and
 * fails when this file and the app disagree, so a screen added to the app
 * shows up here as a red check rather than as a link that goes nowhere.
 */

export interface ArgSpec {
	/** The `{name}` in the pattern. */
	name: string;
	/** What to type, shown as the field's hint. */
	hint: string;
	/** Fixed choices when the app only accepts a known set. */
	options?: 'tools' | 'panels' | 'scripts' | 'storage' | 'licenses' | 'languages' | 'modes';
	/** Whether the app compares the value case-sensitively (arguments always are). */
	note?: string;
}

export interface RouteSpec {
	/** The pattern, `{arg}` segments included. */
	pattern: string;
	/** Where it lands, one line. */
	label: string;
	group: string;
	args?: ArgSpec[];
}

const PANEL_HINT = 'EMOJI, GIF, STICKER, CLIPBOARD, TEXT_EDIT, TRACKPAD, NUMPAD or HANDWRITING';

export const ROUTE_GROUPS = [
	'Getting in',
	'Typing',
	'Key press',
	'Appearance',
	'Layout and size',
	'Languages',
	'Emoji, clipboard and stickers',
	'Tools',
	'Advanced',
	'Privacy and backup',
	'About',
] as const;

export const ROUTES: RouteSpec[] = [
	{ pattern: 'home', label: 'The settings home list', group: 'Getting in' },
	{ pattern: 'search', label: 'Settings search', group: 'Getting in' },

	{ pattern: 'typing', label: 'Typing', group: 'Typing' },
	{ pattern: 'typing/corrections', label: 'Automatic corrections', group: 'Typing' },
	{ pattern: 'typing/suggestions', label: 'Suggestions', group: 'Typing' },
	{ pattern: 'typing/autopilot', label: 'Autopilot', group: 'Typing' },
	{ pattern: 'typing/octopus', label: 'Words on the keys', group: 'Typing' },
	{ pattern: 'typing/chips', label: 'Smart chips', group: 'Typing' },
	{ pattern: 'typing/codes', label: 'One-time codes', group: 'Typing' },
	{ pattern: 'typing/gestures', label: 'Gestures', group: 'Typing' },
	{ pattern: 'typing/hardware', label: 'Physical keyboard', group: 'Typing' },
	{ pattern: 'hwshortcuts', label: 'Tool shortcuts list', group: 'Typing' },
	{ pattern: 'dictionary', label: 'Personal dictionary', group: 'Typing' },
	{ pattern: 'customdictionaries', label: 'Custom dictionaries', group: 'Typing' },
	{ pattern: 'blacklist', label: 'Suggestion blacklist', group: 'Typing' },
	{ pattern: 'learnedcorrections', label: 'Learned corrections', group: 'Typing' },

	{ pattern: 'keypress', label: 'Key press', group: 'Key press' },
	{ pattern: 'keypress/haptics', label: 'Haptics and sound', group: 'Key press' },
	{ pattern: 'keypress/popup', label: 'Key popup', group: 'Key press' },
	{ pattern: 'keypress/shortcuts', label: 'Press and hold shortcuts', group: 'Key press' },

	{ pattern: 'appearance', label: 'Appearance', group: 'Appearance' },
	{ pattern: 'appearance/toolbar', label: 'Toolbar', group: 'Appearance' },
	{ pattern: 'appearance/toolbox', label: 'Toolbox', group: 'Appearance' },
	{ pattern: 'themes', label: 'Keyboard themes', group: 'Appearance' },
	{
		pattern: 'theme_edit/{themeId}',
		label: 'The theme editor for one of your themes',
		group: 'Appearance',
		args: [{ name: 'themeId', hint: 'The id of one of your own themes' }],
	},
	{
		pattern: 'theme_assets/{themeId}',
		label: 'Image sizes and templates for one of your themes',
		group: 'Appearance',
		args: [{ name: 'themeId', hint: 'The id of one of your own themes' }],
	},
	{ pattern: 'fonts', label: 'Keyboard font', group: 'Appearance' },
	{
		pattern: 'fonts/{script}',
		label: 'The font picker for one script',
		group: 'Appearance',
		args: [{ name: 'script', hint: 'A script name in capitals, LATIN or BENGALI', options: 'scripts' }],
	},
	{ pattern: 'icons', label: 'Icons', group: 'Appearance' },
	{ pattern: 'photos', label: 'Photo services', group: 'Appearance' },
	{ pattern: 'photo_browse', label: 'Find a photo online', group: 'Appearance' },
	{ pattern: 'photo_library', label: 'Photo collection', group: 'Appearance' },
	{ pattern: 'photo_rotation', label: 'Rotating background', group: 'Appearance' },

	{ pattern: 'layout', label: 'Layout and size', group: 'Layout and size' },
	{ pattern: 'layout/size', label: 'Size and position', group: 'Layout and size' },
	{ pattern: 'layout/onehanded', label: 'One-handed, split and floating', group: 'Layout and size' },
	{ pattern: 'rows', label: 'Rows and bars', group: 'Layout and size' },
	{ pattern: 'rows/symbol', label: 'Symbol row', group: 'Layout and size' },
	{ pattern: 'keymaps', label: 'Key layouts', group: 'Layout and size' },
	{
		pattern: 'keymap_edit/{layoutId}',
		label: 'The layout editor for one of your key layouts',
		group: 'Layout and size',
		args: [{ name: 'layoutId', hint: 'The id of one of your own key layouts' }],
	},
	{
		pattern: 'keymap_json/{layoutId}',
		label: 'That layout as JSON',
		group: 'Layout and size',
		args: [{ name: 'layoutId', hint: 'The id of one of your own key layouts' }],
	},
	{
		pattern: 'panel_edit/{panel}',
		label: "A panel's layout",
		group: 'Layout and size',
		args: [{ name: 'panel', hint: PANEL_HINT, options: 'panels' }],
	},
	{
		pattern: 'panel_json/{panel}',
		label: "That panel's layout as JSON",
		group: 'Layout and size',
		args: [{ name: 'panel', hint: PANEL_HINT, options: 'panels' }],
	},

	{ pattern: 'languages', label: 'Languages and layouts', group: 'Languages' },
	{ pattern: 'add_language', label: 'Add language', group: 'Languages' },
	{ pattern: 'double_pinyin_custom', label: 'Your own Double Pinyin scheme', group: 'Languages' },
	{
		pattern: 'language/{langId}',
		label: "One language's page",
		group: 'Languages',
		args: [{ name: 'langId', hint: 'A language id, en or bn', options: 'languages' }],
	},
	{
		pattern: 'language/{langId}/more',
		label: 'The full layout catalogue for that language',
		group: 'Languages',
		args: [{ name: 'langId', hint: 'A language id, en or bn', options: 'languages' }],
	},

	{ pattern: 'emoji', label: 'Emoji', group: 'Emoji, clipboard and stickers' },
	{ pattern: 'emoji/panel', label: 'Emoji panel', group: 'Emoji, clipboard and stickers' },
	{ pattern: 'kaomoji', label: 'Kaomoji', group: 'Emoji, clipboard and stickers' },
	{ pattern: 'kaomoji/faces', label: 'Your kaomoji, to add, change, sort and delete', group: 'Emoji, clipboard and stickers' },
	{ pattern: 'kaomoji/tags', label: 'Kaomoji tags, in the order the tag strip shows them', group: 'Emoji, clipboard and stickers' },
	{ pattern: 'emojikeywords', label: 'Emoji keywords', group: 'Emoji, clipboard and stickers' },
	{ pattern: 'emojicategories', label: 'Emoji categories', group: 'Emoji, clipboard and stickers' },
	{
		pattern: 'emojiorder/{category}',
		label: 'The emoji inside one category, in their order',
		group: 'Emoji, clipboard and stickers',
		args: [{ name: 'category', hint: 'A category id in lowercase, smileys or flags' }],
	},
	{ pattern: 'clipboard', label: 'Clipboard', group: 'Emoji, clipboard and stickers' },
	{ pattern: 'phoneformats', label: 'Phone number formats', group: 'Emoji, clipboard and stickers' },
	{ pattern: 'cliprules', label: 'Copy rules', group: 'Emoji, clipboard and stickers' },
	{ pattern: 'sticker_packs', label: 'Sticker packs', group: 'Emoji, clipboard and stickers' },
	{ pattern: 'signal_stickers', label: 'Signal sticker packs', group: 'Emoji, clipboard and stickers' },
	{ pattern: 'whatsapp_stickers', label: 'WhatsApp stickers', group: 'Emoji, clipboard and stickers' },
	{ pattern: 'telegram_stickers', label: 'Telegram sticker sets', group: 'Emoji, clipboard and stickers' },
	{
		pattern: 'sticker_pack/{packId}',
		label: 'One of your sticker packs',
		group: 'Emoji, clipboard and stickers',
		args: [{ name: 'packId', hint: 'The id of one of your sticker packs' }],
	},
	{
		pattern: 'sticker_pack/{packId}/add',
		label: 'Add stickers to one of your packs',
		group: 'Emoji, clipboard and stickers',
		args: [{ name: 'packId', hint: 'The id of one of your sticker packs' }],
	},

	{ pattern: 'tools', label: 'Tools', group: 'Tools' },
	{
		pattern: 'tool/{toolName}',
		label: "One tool's page",
		group: 'Tools',
		args: [{ name: 'toolName', hint: "The tool's own name in capitals, CLIPBOARD or TRANSLATE", options: 'tools' }],
	},
	{ pattern: 'vocab/packs', label: 'Vocabulary packs', group: 'Tools' },
	{ pattern: 'vocab/lists', label: 'My lists', group: 'Tools' },
	{
		pattern: 'vocab/list/{packId}',
		label: 'One of your vocabulary lists',
		group: 'Tools',
		args: [{ name: 'packId', hint: 'The id of one of your vocabulary lists' }],
	},
	{ pattern: 'vocab/review', label: 'Review', group: 'Tools' },
	{ pattern: 'vocab/browse', label: 'Browse words', group: 'Tools' },
	{
		pattern: 'vocab/word/{packId}/{word}',
		label: "One word's page in the vocabulary browser",
		group: 'Tools',
		args: [
			{ name: 'packId', hint: 'The id of the pack that holds the word' },
			{ name: 'word', hint: 'The word itself' },
		],
	},
	{ pattern: 'clock', label: 'Clock', group: 'Tools' },
	{ pattern: 'homeassistant/entities', label: 'Favourites and rooms', group: 'Tools' },
	{ pattern: 'notes', label: 'Notes', group: 'Tools' },
	{
		pattern: 'notes/edit/{noteId}',
		label: 'One note, open in the editor',
		group: 'Tools',
		args: [{ name: 'noteId', hint: 'A note id, or 0 for a new note' }],
	},
	{ pattern: 'notes/tags', label: 'Note tags', group: 'Tools' },
	{ pattern: 'notes/trash', label: 'Notes trash', group: 'Tools' },
	{ pattern: 'todo', label: 'To-do list', group: 'Tools' },
	{ pattern: 'voice', label: 'Voice typing', group: 'Tools' },
	{ pattern: 'expander', label: 'Text expansion', group: 'Tools' },
	{
		pattern: 'expander/folder/{folderId}',
		label: 'One snippet folder',
		group: 'Tools',
		args: [{ name: 'folderId', hint: 'A folder number; 0 is the top level' }],
	},
	{
		pattern: 'expander/folder/{folderId}/new',
		label: 'A new snippet inside that folder',
		group: 'Tools',
		args: [{ name: 'folderId', hint: 'A folder number; 0 is the top level' }],
	},
	{
		pattern: 'expander/edit/{snippetId}',
		label: 'One snippet',
		group: 'Tools',
		args: [{ name: 'snippetId', hint: 'A snippet number; 0 opens an empty new one' }],
	},
	{ pattern: 'musicapps', label: 'Music players', group: 'Tools' },
	{ pattern: 'launchercombos', label: 'Split screen pairs, for the app launcher', group: 'Tools' },
	{ pattern: 'kdeconnect/devices', label: 'KDE Connect devices', group: 'Tools' },
	{ pattern: 'send/instances', label: 'Send instances', group: 'Tools' },
	{ pattern: 'send/history', label: 'Send history', group: 'Tools' },
	{ pattern: 'ai_actions', label: 'AI actions', group: 'Tools' },
	{
		pattern: 'ai_action_edit/{actionId}',
		label: 'One AI action',
		group: 'Tools',
		args: [{ name: 'actionId', hint: 'The id of one of your AI actions' }],
	},
	{ pattern: 'ai_reply_tones', label: 'AI reply tones', group: 'Tools' },
	{ pattern: 'ai_history', label: 'AI history', group: 'Tools' },
	{ pattern: 'ai_tools', label: "The keyboard's own tools the AI can use", group: 'Tools' },
	{ pattern: 'ai_mcp', label: 'MCP servers for AI chat', group: 'Tools' },
	{
		pattern: 'ai_mcp_edit/{serverId}',
		label: 'One MCP server',
		group: 'Tools',
		args: [{ name: 'serverId', hint: 'The id of one of your MCP servers' }],
	},
	{ pattern: 'ai_chat', label: 'AI chat', group: 'Tools' },
	{
		pattern: 'ai_chat/{conversationId}',
		label: 'One saved conversation',
		group: 'Tools',
		args: [{ name: 'conversationId', hint: 'The id of a saved conversation' }],
	},
	{
		pattern: 'symbol_set_edit/{setId}',
		label: 'One symbol set',
		group: 'Tools',
		args: [{ name: 'setId', hint: 'The id of one of your symbol sets' }],
	},

	{ pattern: 'advanced', label: 'Advanced', group: 'Advanced' },
	{ pattern: 'floating_button', label: 'Floating button', group: 'Advanced' },
	{ pattern: 'share_sheet', label: 'Share sheet', group: 'Advanced' },
	{ pattern: 'modes', label: 'Keyboard modes', group: 'Advanced' },
	{
		pattern: 'mode_edit/{modeId}',
		label: 'One keyboard mode',
		group: 'Advanced',
		args: [{ name: 'modeId', hint: 'A built-in mode, or the id of one you made', options: 'modes' }],
	},
	{ pattern: 'addons', label: 'Add-ons', group: 'Advanced' },
	{
		pattern: 'addon_repo/{repoUrl}',
		label: 'One repository (prefer the wmkeyboard://repo form)',
		group: 'Advanced',
		args: [{ name: 'repoUrl', hint: 'A manifest URL, percent-encoded' }],
	},
	{
		pattern: 'addon/{repoUrl}/{addonId}',
		label: 'One addon (prefer the wmkeyboard://addon form)',
		group: 'Advanced',
		args: [
			{ name: 'repoUrl', hint: 'A manifest URL, percent-encoded' },
			{ name: 'addonId', hint: "The addon's id in that repository" },
		],
	},
	{ pattern: 'plugins', label: 'Plugins', group: 'Advanced' },
	{
		pattern: 'plugin/{pluginId}',
		label: 'One installed plugin',
		group: 'Advanced',
		args: [{ name: 'pluginId', hint: "An installed plugin's id" }],
	},
	{ pattern: 'plugin_ide', label: 'Plugin editor', group: 'Advanced' },
	{
		pattern: 'plugin_ide/{draftId}',
		label: 'One plugin project in the editor',
		group: 'Advanced',
		args: [{ name: 'draftId', hint: 'The id of one of your plugin projects' }],
	},
	{ pattern: 'datasaver', label: 'Data saver', group: 'Advanced' },
	{ pattern: 'servers', label: 'Servers (F-Droid edition only)', group: 'Advanced' },
	{ pattern: 'notifications', label: 'Notifications', group: 'Advanced' },
	{ pattern: 'selection_macros', label: 'Selection actions', group: 'Advanced' },
	{ pattern: 'selection_macros/actions', label: 'Selection actions: the list and its order', group: 'Advanced' },
	{ pattern: 'selection_macros/icons', label: 'Selection actions: the icon-only actions', group: 'Advanced' },
	{ pattern: 'selection_macros/ai', label: 'Selection actions: the AI buttons', group: 'Advanced' },
	{ pattern: 'selection_macros/zones', label: 'Selection actions: the time zones', group: 'Advanced' },

	{ pattern: 'privacy', label: 'Privacy', group: 'Privacy and backup' },
	{ pattern: 'permissions', label: 'Permissions', group: 'Privacy and backup' },
	{ pattern: 'network_activity', label: 'Network activity', group: 'Privacy and backup' },
	{ pattern: 'applock', label: 'App lock', group: 'Privacy and backup' },
	{ pattern: 'automation', label: 'Allowed actions (automation)', group: 'Privacy and backup' },
	{ pattern: 'accessibility', label: 'Accessibility', group: 'Privacy and backup' },
	{ pattern: 'backup', label: 'Backup and restore', group: 'Privacy and backup' },
	{ pattern: 'backup/auto', label: 'Automatic backup', group: 'Privacy and backup' },
	{ pattern: 'backup/sync', label: 'Sync devices', group: 'Privacy and backup' },
	{ pattern: 'backup/contents', label: 'What goes in an export', group: 'Privacy and backup' },

	{ pattern: 'about', label: 'About', group: 'About' },
	{ pattern: 'storage', label: 'Storage', group: 'About' },
	{
		pattern: 'storage/{category}',
		label: 'One storage category',
		group: 'About',
		args: [{ name: 'category', hint: 'A category id, themes or plugins', options: 'storage' }],
	},
	{ pattern: 'resources', label: 'Resources', group: 'About' },
	{ pattern: 'statistics', label: 'Statistics', group: 'About' },
	{ pattern: 'debug_log', label: 'Diagnostics', group: 'About' },
	{ pattern: 'licenses', label: 'Open-source licences', group: 'About' },
	{ pattern: 'supporters', label: 'Supporters', group: 'About' },
	{
		pattern: 'license_text/{asset}',
		label: "One licence's full text",
		group: 'About',
		args: [{ name: 'asset', hint: 'A licence file name, mit-wmkeyboard.txt', options: 'licenses' }],
	},
	{ pattern: 'egg_game', label: 'Keycap catcher', group: 'About' },
];

/** `ToolbarTool` entry names, the accepted values of `tool/{toolName}`. */
export const TOOL_NAMES = [
	'EMOJI', 'CLIPBOARD', 'SNIPPETS', 'TEXT_EDIT', 'TRACKPAD', 'ONE_HANDED', 'SPLIT', 'FLOATING', 'SETTINGS',
	'FLASHLIGHT', 'COMPASS', 'LEVEL', 'UNDO', 'REDO', 'MOON_PHASE', 'WEATHER', 'CALENDAR', 'INCOGNITO', 'THEMES',
	'AUTOCORRECT', 'SOUND_HAPTICS', 'NUMPAD', 'HANDWRITING', 'CAMERA', 'DICTIONARY', 'VOCABULARY', 'TRANSLATE',
	'GIF', 'STICKER', 'WEB_SEARCH', 'IMAGE_SEARCH', 'OCR', 'QR_SCAN', 'DOC_SCAN', 'VOICE', 'GRAMMAR', 'WIKIPEDIA',
	'SYMBOLS', 'CALCULATOR', 'UNIT_CONVERT', 'CURRENCY', 'QR_GEN', 'PASSWORD_GEN', 'AI', 'MODES', 'TYPING_TEST',
	'MEDIA_CONTROL', 'PLUGINS', 'POWER_SAVING', 'APP_LAUNCHER', 'FANCY', 'CUSTOM_LAYOUT', 'RESIZE', 'CURSOR_LEFT',
	'CURSOR_RIGHT', 'CURSOR_UP', 'CURSOR_DOWN', 'CURSOR_HOME', 'CURSOR_END', 'PAGE_UP', 'PAGE_DOWN',
	'CURSOR_WORD_LEFT', 'CURSOR_WORD_RIGHT', 'SELECT_WORD', 'SELECT_LINE', 'SELECT_ALL', 'SELECT_MODE', 'COPY',
	'CUT', 'PASTE', 'HIDE_KEYBOARD', 'PERSISTENT', 'SELECTION_ACTIONS', 'LEARN_FROM_TEXT',
	'PHONETIC_ENGLISH', 'KDE_CONNECT', 'STATISTICS', 'FIND_REPLACE', 'NOTES', 'AI_REPLY', 'LOCATION', 'CLOCK', 'HOME_ASSISTANT', 'GALLERY', 'LANGUAGE',
	'TEXT_REPEAT', 'SEND_FILES', 'TODO',
];

/**
 * The keyboard modes that ship with the app, `DefaultKeyboardModes` in
 * core/settings/.../KeyboardModes.kt, in its order. Their ids are the same on
 * every install, so `mode_edit/{modeId}` can name them in a link anyone can
 * use. A mode the user made has an id of its own (`mode_custom_…`) that only
 * their install knows. The names are the English ones a mode is stored with.
 */
export const MODE_IDS: { id: string; name: string }[] = [
	{ id: 'mode_password', name: 'Passwords' },
	{ id: 'mode_email', name: 'Email' },
	{ id: 'mode_browser', name: 'Browser' },
	{ id: 'mode_chat', name: 'Chat' },
	{ id: 'mode_writing', name: 'Writing' },
	{ id: 'mode_coding', name: 'Coding' },
];

/** `PanelKind` entry names. */
export const PANEL_NAMES = ['EMOJI', 'GIF', 'STICKER', 'CLIPBOARD', 'TEXT_EDIT', 'TRACKPAD', 'NUMPAD', 'HANDWRITING'];

/**
 * The scripts the font screen lists: Latin, then `KeyboardFonts.scriptFontChoices`
 * (feature/ime/.../KeyboardFonts.kt). `fonts/{script}` accepts any `ScriptId`
 * name, but only these have a page with faces on it.
 */
export const SCRIPT_NAMES = [
	'LATIN', 'BENGALI', 'ARABIC', 'HEBREW', 'ARMENIAN', 'GEORGIAN', 'DEVANAGARI', 'GURMUKHI', 'GUJARATI', 'ORIYA',
	'TAMIL', 'TELUGU', 'KANNADA', 'MALAYALAM', 'SINHALA', 'THAI', 'LAO', 'KHMER', 'MYANMAR', 'ETHIOPIC', 'HANGUL', 'JAPANESE', 'HAN',
	'PIQAD',
];

/** `StorageCategories` ids, the accepted values of `storage/{category}`. */
export const STORAGE_IDS = [
	'wordlists', 'bundled_dicts', 'emoji_dicts', 'vocab', 'voice_models', 'ai_models', 'ocr_packs', 'gif_packs', 'addon_index', 'themes',
	'stickers', 'icon_packs', 'fonts', 'key_sounds', 'plugins', 'learned', 'typing_stats', 'netlog', 'clipboard',
	'snippets', 'ai_history', 'custom_wordlists', 'captures', 'settings_data', 'cache_images', 'cache_media',
	'cache_addons', 'cache_temp', 'logs',
];

/** The files under app/src/main/assets/licenses/. */
export const LICENSE_ASSETS = [
	'apache-2.0.txt', 'asr4all-weights.txt', 'bsd-2-clause-leptonica.txt', 'bsd-2-clause-stroke.txt', 'bsd-3-clause.txt',
	'bsd-executorch.txt', 'bsd-jsch.txt', 'cc-by-4.0-lshk.txt', 'cc-by-4.0-noto-animated.txt', 'cc-by-4.0-parakeet.txt',
	'cc-by-sa-4.0.txt', 'harper-third-party.txt', 'jyutping-sources.txt', 'mit-bouncycastle.txt', 'mit-color-themes.txt',
	'mit-gemoji.txt', 'mit-keyman.txt', 'mit-khipro.txt', 'mit-luaj.txt', 'mit-moonshine.txt', 'mit-whisper-android.txt',
	'mit-whisper-cpp.txt', 'mit-whisper.txt', 'mit-wmkeyboard.txt', 'mpl-1.1.txt', 'ofl-1.1-fonts.txt', 'ofl-1.1.txt',
	'unicode-3.0.txt', 'wordlist-sources.txt', 'x11-keysymdef.txt',
];

/** `FLASHLIGHT` → `Flashlight`, `SOUND_HAPTICS` → `Sound haptics`. */
export function humanize(name: string): string {
	return name.charAt(0) + name.slice(1).toLowerCase().replace(/_/g, ' ');
}

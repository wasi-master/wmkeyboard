/**
 * Wire types for a WM Keyboard addon repository, mirroring `AddonRepo.kt`
 * in core/addons. Everything past `format`/`version`/`repo`/`addons` is
 * optional on the wire; the decoder fills in the same defaults the app does.
 */

export const REPO_FORMAT = 'wmkeyboard-repo';
export const MANIFEST_NAME = 'wmkeyboard-repo.json';

/** Wire names of every addon type the app knows, in the app's enum order. */
export const ADDON_TYPES = [
	'theme',
	'layout',
	'dictionary',
	'emoji_keywords',
	'snippets',
	'espanso',
	'stickers',
	'dynamic_stickers',
	'icon_pack',
	'font',
	'emoji_font',
	'sound',
	'sound_pack',
	'plugin',
	'vocabulary',
] as const;

export type AddonType = (typeof ADDON_TYPES)[number];

export interface AddonTypeInfo {
	/** Plural label, used for filter chips and section headings. */
	plural: string;
	/** Singular label, used on cards and detail pages. */
	singular: string;
	/** Tint the app gives the type (hex, no #). */
	hue: string;
	/** Install cap in bytes, enforced mid-download by the app. */
	maxBytes: number;
	/** Whether the app previews the payload before installing. */
	appPreviews: boolean;
	/** Type the app files it under for chips/filters (espanso → snippets). */
	category: AddonType;
	/** Where "Use" lands in the app, as a human path. */
	useLabel: string;
	/** Whether the app asks "switch to it?" after installing. */
	asksToApply: boolean;
	/** What the payload file looks like, for the creator + downloads. */
	payload: string;
}

const MB = 1024 * 1024;

export const TYPE_INFO: Record<AddonType, AddonTypeInfo> = {
	theme: { plural: 'Themes', singular: 'Theme', hue: '7E57C2', maxBytes: 16 * MB, appPreviews: false, category: 'theme', useLabel: 'Themes', asksToApply: true, payload: '.wmtheme.json' },
	layout: { plural: 'Layouts', singular: 'Layout', hue: '3B82F6', maxBytes: 4 * MB, appPreviews: false, category: 'layout', useLabel: 'Key layouts', asksToApply: true, payload: '.wmlayout.json' },
	dictionary: { plural: 'Dictionaries', singular: 'Dictionary', hue: '14B8A6', maxBytes: 32 * MB, appPreviews: true, category: 'dictionary', useLabel: 'Dictionaries', asksToApply: false, payload: 'word list (.txt, .txt.gz)' },
	emoji_keywords: { plural: 'Emoji keywords', singular: 'Emoji keyword pack', hue: '0EA5E9', maxBytes: 8 * MB, appPreviews: true, category: 'emoji_keywords', useLabel: 'Emoji', asksToApply: false, payload: 'keyword table (.tsv, .tsv.gz)' },
	snippets: { plural: 'Snippets', singular: 'Snippet pack', hue: 'F59E0B', maxBytes: 4 * MB, appPreviews: true, category: 'snippets', useLabel: 'Snippets', asksToApply: false, payload: '.wmsnippets.json' },
	espanso: { plural: 'Espanso packs', singular: 'Espanso pack', hue: 'F59E0B', maxBytes: 4 * MB, appPreviews: true, category: 'snippets', useLabel: 'Snippets', asksToApply: false, payload: 'Espanso package (.yml)' },
	stickers: { plural: 'Sticker packs', singular: 'Sticker pack', hue: 'EC4899', maxBytes: 64 * MB, appPreviews: true, category: 'stickers', useLabel: 'Stickers', asksToApply: false, payload: '.wmstickers' },
	dynamic_stickers: { plural: 'Dynamic stickers', singular: 'Dynamic sticker pack', hue: 'D946EF', maxBytes: 64 * MB, appPreviews: true, category: 'dynamic_stickers', useLabel: 'Dynamic stickers', asksToApply: false, payload: '.wmdynstickers' },
	icon_pack: { plural: 'Icon packs', singular: 'Icon pack', hue: '22A559', maxBytes: 8 * MB, appPreviews: false, category: 'icon_pack', useLabel: 'Icon packs', asksToApply: true, payload: '.wmicons' },
	font: { plural: 'Fonts', singular: 'Font', hue: '6366F1', maxBytes: 32 * MB, appPreviews: false, category: 'font', useLabel: 'Fonts', asksToApply: false, payload: '.ttf / .otf' },
	emoji_font: { plural: 'Emoji fonts', singular: 'Emoji font', hue: 'EAB308', maxBytes: 32 * MB, appPreviews: false, category: 'emoji_font', useLabel: 'Emoji', asksToApply: true, payload: '.ttf / .otf' },
	sound: { plural: 'Sounds', singular: 'Key sound', hue: 'EF4444', maxBytes: 4 * MB, appPreviews: true, category: 'sound', useLabel: 'Sound & vibration', asksToApply: true, payload: '.mp3 / .ogg / .wav' },
	sound_pack: { plural: 'Sound packs', singular: 'Sound pack', hue: 'F97316', maxBytes: 16 * MB, appPreviews: true, category: 'sound_pack', useLabel: 'Sound & vibration', asksToApply: true, payload: '.wmsoundpack' },
	plugin: { plural: 'Plugins', singular: 'Plugin', hue: '06B6D4', maxBytes: 1 * MB, appPreviews: true, category: 'plugin', useLabel: 'Plugins', asksToApply: true, payload: '.wmplugin' },
	vocabulary: { plural: 'Vocabulary', singular: 'Vocabulary pack', hue: '8E24AA', maxBytes: 8 * MB, appPreviews: true, category: 'vocabulary', useLabel: 'Vocabulary', asksToApply: false, payload: '.wmvocab.json' },
};

export const UNKNOWN_TYPE_INFO: AddonTypeInfo = { plural: 'Other', singular: 'Add-on', hue: '6B7280', maxBytes: 0, appPreviews: false, category: 'theme', useLabel: 'Addons', asksToApply: false, payload: 'file' };

export function typeInfo(type: string): AddonTypeInfo {
	return (TYPE_INFO as Record<string, AddonTypeInfo>)[type] ?? UNKNOWN_TYPE_INFO;
}

export function isAddonType(s: string): s is AddonType {
	return (ADDON_TYPES as readonly string[]).includes(s);
}

/** Types that require `langId` on the wire (schema `allOf`). */
export const LANG_REQUIRED: ReadonlySet<string> = new Set(['dictionary', 'emoji_keywords', 'vocabulary']);

export interface RepoInfo {
	id: string;
	name: string;
	description?: string;
	author?: string;
	homepage?: string;
	icon?: string;
	updatedAt?: string;
}

export interface AddonEntry {
	id: string;
	type: AddonType | string;
	name: string;
	version: string;
	author?: string;
	description?: string;
	tags: string[];
	path: string;
	sha256?: string;
	sizeBytes?: number;
	previews: string[];
	minAppVersion?: number;
	langId?: string;
	langIds: string[];
	license?: string;
	licenseText?: string;
	licenseFile?: string;
	/** Addon ids in the same repository the app offers to download alongside. */
	requires: string[];
	/** `"<repoId>/<addonId>"` of the addon this one remixes; AddonEntry.basedOn in the app. */
	basedOn?: string;
}

export interface Manifest {
	format: string;
	version: number;
	repo: RepoInfo;
	addons: AddonEntry[];
	/** Anything the publisher put at the top level that we don't model. */
	extra?: Record<string, unknown>;
}

/** A repository as the store remembers it. */
export interface RepoRef {
	/** Resolved manifest URL (https://…/wmkeyboard-repo.json). The key. */
	url: string;
	/** What the user typed or the seed: the "page" URL, kept for display. */
	input: string;
	/** Seeded by the store rather than added by the user. */
	seeded?: boolean;
	/** Slug for prerendered pages (only seeded repos have one). */
	slug?: string;
	addedAt: number;
}

export interface LoadedRepo {
	ref: RepoRef;
	manifest: Manifest | null;
	error: string | null;
	fetchedAt: number | null;
	/** True while a (re)fetch is in flight. */
	loading: boolean;
	/** Entries the decoder dropped, with reasons, for the health check. */
	dropped: { index: number; reason: string }[];
}

/** One addon pinned to its repository, the unit the collection stores. */
export interface AddonKey {
	repo: string;
	id: string;
}

export function addonKey(k: AddonKey): string {
	return `${k.repo}#${k.id}`;
}

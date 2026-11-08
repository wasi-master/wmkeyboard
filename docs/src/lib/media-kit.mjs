/**
 * The press kit at /media-kit/: what it offers, and the one zip of all of it.
 *
 * The page (src/content/docs/media-kit.mdx and its components) and the zip
 * endpoint (src/pages/media-kit/wm-keyboard-media-kit.zip.ts) both read this
 * file, so the archive can't list a file the page doesn't, or the other way
 * round.
 *
 * Screenshots and banners are not copied into the docs. They are read from
 * where the Play listing pipeline already keeps them (play/ at the repo root),
 * so a re-shot store listing reaches the press kit on the next build. Only the
 * logo files are the docs' own: tools/icon/gen_media_kit.py renders them into
 * public/media-kit/logo/ from the launcher icon's geometry.
 */

import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { strToU8, zipSync } from 'fflate';
import sharp from 'sharp';

import { REPO_URL, SITE_URL, SOCIALS } from '../site.mjs';
import { INSTALL_SOURCES } from './install-links.mjs';

// The build runs from docs/. Not `import.meta.url`: this module is bundled
// into dist/ before it runs, so a path relative to it points nowhere.
const REPO = resolve(process.cwd(), '..');
const fromRepo = (path) => resolve(REPO, path);

export const ZIP_NAME = 'wm-keyboard-media-kit.zip';
export const ZIP_PATH = `/media-kit/${ZIP_NAME}`;

/** Brand colours: the launcher icon's (tools/icon/gen_icon.py) and the docs' accent. */
export const COLOURS = [
	{ name: 'Brand blue', hex: '#4C8DF6', use: 'Gradient start, links, accents' },
	{ name: 'Brand violet', hex: '#8B5CF6', use: 'Gradient end' },
	{ name: 'Key blue', hex: '#5D9BFF', use: 'Keyboard body in the mark, top' },
	{ name: 'Key lilac', hex: '#A78BFA', use: 'Keyboard body in the mark, bottom' },
	{ name: 'Indigo', hex: '#2F3474', use: 'Icon background, top' },
	{ name: 'Navy', hex: '#191C42', use: 'Icon background, bottom; text on light' },
	{ name: 'White', hex: '#FFFFFF', use: 'The pin; text on dark' },
];

/**
 * Logo cards. `files` are names inside public/media-kit/logo/; `on` is the
 * backdrop the preview sits on, which is also the backdrop the file is for.
 */
export const LOGOS = [
	{
		title: 'App icon',
		note: 'The launcher icon. Use it wherever an app icon belongs.',
		preview: 'icon-rounded.svg',
		on: 'checker',
		files: ['icon-rounded.svg', 'icon-rounded-512.png', 'icon-rounded-1024.png', 'icon.svg', 'icon-1024.png'],
	},
	{
		title: 'Horizontal logo, dark',
		note: 'Icon and name, for dark backgrounds.',
		preview: 'lockup-dark.svg',
		on: 'dark',
		files: ['lockup-dark.svg', 'lockup-dark.png'],
	},
	{
		title: 'Horizontal logo, light',
		note: 'Icon and name, for light backgrounds.',
		preview: 'lockup-light.svg',
		on: 'light',
		files: ['lockup-light.svg', 'lockup-light.png'],
	},
	{
		title: 'Stacked logo, dark',
		note: 'For square or tall spaces on dark backgrounds.',
		preview: 'lockup-stacked-dark.svg',
		on: 'dark',
		files: ['lockup-stacked-dark.svg', 'lockup-stacked-dark.png'],
	},
	{
		title: 'Stacked logo, light',
		note: 'For square or tall spaces on light backgrounds.',
		preview: 'lockup-stacked-light.svg',
		on: 'light',
		files: ['lockup-stacked-light.svg', 'lockup-stacked-light.png'],
	},
	{
		title: 'Mark, for dark backgrounds',
		note: 'The keyboard and pin without the icon tile. White pin.',
		preview: 'mark-on-dark.svg',
		on: 'dark',
		files: ['mark-on-dark.svg', 'mark-on-dark.png'],
	},
	{
		title: 'Mark, for light backgrounds',
		note: 'The same mark with a navy pin, so it holds up on white.',
		preview: 'mark-on-light.svg',
		on: 'light',
		files: ['mark-on-light.svg', 'mark-on-light.png'],
	},
	{
		title: 'One-colour mark',
		note: 'For print, embossing, stickers and anywhere a gradient will not reproduce.',
		preview: 'mark-mono-white.svg',
		on: 'dark',
		files: ['mark-mono-white.svg', 'mark-mono-white.png', 'mark-mono-black.svg', 'mark-mono-black.png'],
	},
	{
		title: 'Wordmark',
		note: 'The name alone, set in Inter and converted to outlines.',
		preview: 'wordmark-navy.svg',
		on: 'light',
		files: ['wordmark-navy.svg', 'wordmark-navy.png', 'wordmark-white.svg', 'wordmark-white.png'],
	},
];

/** Captions for the screenshots, by file stem. Anything missing falls back to its stem. */
export const CAPTIONS = {
	'ai-panel': 'AI writing tools',
	'clipboard-panel': 'Clipboard manager',
	'emoji-search': 'Emoji search',
	'kb-airplane': 'Airplane mode',
	'kb-bengali-typing': 'Bangla phonetic typing',
	'kb-english-dark': 'English, dark theme',
	'kb-floating': 'Floating keyboard',
	'kb-glide': 'Glide typing',
	'kb-split': 'Split keyboard',
	'otp-chip': 'One-time code chip',
	'theme-light': 'Light theme',
	'theme-light-altbg': 'Light theme, tinted background',
	'theme-photo': 'Photo background',
	'theme-sakura': 'Sakura theme',
	'toolbox-grid': 'The tools panel',
	'tools-calculator': 'Calculator',
	'tools-dictionary': 'Dictionary look-up',
	'voice-panel': 'Voice typing',
	'01-hero': 'Store listing: overview',
	'02-bengali': 'Store listing: Bangla',
	'03-offline': 'Store listing: offline',
	'04-ai': 'Store listing: AI',
	'05-clipboard': 'Store listing: clipboard',
	'06-tools': 'Store listing: tools',
	'07-themes': 'Store listing: themes',
	'08-chip-wall': 'Store listing: everything else',
	'feature-graphic': 'Play feature graphic',
	'og-card': 'Link preview card',
	social_preview: 'GitHub social preview',
	'discord-960x540': 'Discord server banner',
	'facebook-1640x624': 'Facebook cover',
	'header-1500x500': 'X, Bluesky, Mastodon header',
	'linkedin-1128x191': 'LinkedIn banner',
	'reddit-1920x384': 'Reddit banner',
	'reddit-wide-1920x232': 'Reddit banner, wide',
	'youtube-2560x1440': 'YouTube channel art',
};

export const caption = (stem) => CAPTIONS[stem] ?? stem.replace(/[-_]+/g, ' ');

const play = getInstall('play');
const fdroid = getInstall('fdroid');
function getInstall(id) {
	return INSTALL_SOURCES.find((s) => s.id === id)?.url;
}

/** Copy for writers, in three lengths. Plain text: it is pasted into other people's pages. */
export const BOILERPLATE = {
	tagline: 'Type anything. Share nothing.',
	short: 'WM Keyboard is a free, open-source Android keyboard that keeps prediction, autocorrect and voice typing on the phone.',
	long: [
		'WM Keyboard is a free and open-source keyboard for Android. Prediction, autocorrect, glide typing, transliteration and offline voice typing all run on the device, and the app carries no ads and no analytics.',
		'It types in more than 800 languages, with phonetic input for Bangla, Hindi and Urdu, CJK input methods and downloadable dictionaries. A built-in tool belt adds a clipboard manager, translation, OCR and QR scanning, handwriting, GIFs and stickers, a calculator and an on-device AI model. Themes, addon repositories and sandboxed Python plugins let people make it their own.',
		`WM Keyboard is MIT licensed and available on Google Play, F-Droid and GitHub. Documentation lives at ${SITE_URL}.`,
	].join('\n\n'),
};

/** Where press questions go. Shown on the page and in the zip's README. */
export const PRESS_EMAIL = 'arianmollik323@gmail.com';

export const FACTS = [
	['Name', 'WM Keyboard (two words; “WM” in capitals)'],
	['Made by', 'Arian Mollik Wasi, an independent developer based in Bangladesh'],
	['Platform', 'Android 7.0 and later, phones, tablets and Android TV'],
	['Price', 'Free. No ads, no in-app purchases, no analytics'],
	['Licence', 'MIT, source on GitHub'],
	['Package', 'com.wasimaster.wmkeyboard'],
];

export const LINKS = [
	['Website', SITE_URL],
	['Source code', REPO_URL],
	['Google Play', play],
	['F-Droid', fdroid],
	['Releases', `${REPO_URL}/releases`],
	...SOCIALS.map((s) => [s.label, s.href]),
	['Press contact', `mailto:${PRESS_EMAIL}`],
];

// ---------------------------------------------------------------- the zip

/** Every file the archive carries, as [path inside the zip, repo-relative source]. */
function sources() {
	const list = (dir, re) =>
		readdirSync(fromRepo(dir))
			.filter((f) => re.test(f))
			.sort()
			.map((f) => `${dir}${f}`);
	const into = (folder) => (src) => [`${folder}/${src.split('/').pop()}`, src];

	return [
		...list('docs/public/media-kit/logo/', /\.(svg|png)$/).map(into('logo')),
		...list('play/screencaps/', /\.(png|jpe?g)$/).map(into('screenshots/phone')),
		...list('play/screencaps-tablet/', /\.(png|jpe?g)$/).map(into('screenshots/tablet')),
		...list('play/store-listing/', /^(0\d-.*|feature-graphic)\.png$/).map(into('store-graphics')),
		...list('play/social/', /\.png$/).map(into('banners')),
		['banners/og-card.png', 'docs/public/og-card.png'],
		['banners/github-social-preview.png', '.github/social_preview.png'],
	];
}

function readme() {
	return [
		'WM Keyboard media kit',
		'=====================',
		'',
		BOILERPLATE.tagline,
		'',
		BOILERPLATE.long,
		'',
		...FACTS.map(([k, v]) => `${k}: ${v}`),
		'',
		...LINKS.map(([k, v]) => `${k}: ${v.replace(/^mailto:/, '')}`),
		'',
		'Colours',
		...COLOURS.map((c) => `  ${c.hex}  ${c.name} (${c.use})`),
		'',
		'Use the logos as they are: no recolouring, stretching, outlines or effects,',
		'and leave clear space around them about the height of the "W".',
		'The store graphics are JPEG in this archive; full PNGs are on the web page.',
		'',
		`Latest version: ${SITE_URL}/media-kit/`,
		'',
	].join('\n');
}

let cached;

/**
 * The archive, built once per build. The store-listing composites are 2 MB
 * PNGs each, which would push the zip near Cloudflare Pages' 25 MiB file cap,
 * so they go in as high-quality JPEG. Everything else is copied untouched, and
 * stored rather than deflated: PNG, JPEG and the like are already compressed.
 */
export function mediaKitZip() {
	cached ??= (async () => {
		const files = { 'README.txt': [strToU8(readme()), { level: 6 }] };
		for (const [name, src] of sources()) {
			const path = fromRepo(src);
			if (name.startsWith('store-graphics/')) {
				const jpg = await sharp(path).jpeg({ quality: 90, mozjpeg: true }).toBuffer();
				files[name.replace(/\.png$/, '.jpg')] = [new Uint8Array(jpg), { level: 0 }];
			} else {
				files[name] = [new Uint8Array(readFileSync(path)), { level: name.endsWith('.svg') ? 6 : 0 }];
			}
		}
		return zipSync(files, { mtime: new Date('2026-01-01T00:00:00Z') });
	})();
	return cached;
}

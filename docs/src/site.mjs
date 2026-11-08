/**
 * Site-wide constants shared by `astro.config.mjs` and the components that need
 * them (`Head.astro` in particular). Keeping them here means the canonical URL,
 * title and description can't drift apart between the Starlight config and the
 * SEO tags built from it.
 */

/**
 * Deployed origin. Also what the sitemap and every absolute og: URL is built
 * from, and what the app's About screen opens (`DOCS_URL` in AboutScreens.kt).
 * Change it in both places or the in-app links point at the old host.
 */
export const SITE_URL = 'https://wmkeyboard.pages.dev';

export const SITE_TITLE = 'WM Keyboard';

/**
 * The landing page's `<title>`. Starlight titles a page `<name> | <site>`,
 * which on the landing page — whose name *is* the site — came out as
 * "WM Keyboard | WM Keyboard": two of the ~60 characters Google shows spent
 * saying the brand twice, and nothing said about what the app is. This is the
 * one title worth writing by hand.
 */
export const SITE_TAGLINE_TITLE = 'WM Keyboard — privacy-first Android keyboard, 843 languages';

export const SITE_DESCRIPTION =
	'A modern, privacy-first Android keyboard — offline intelligence, 843 languages, themes, tools and an addon ecosystem.';

/**
 * IndexNow key (https://www.indexnow.org). Not a secret: the protocol proves
 * ownership by serving it back from `public/<key>.txt`, so the two must match.
 * `scripts/indexnow.mjs` submits changed URLs with it after each deploy, and
 * Bing, Yandex, Seznam and Naver all read the same submission.
 */
export const INDEXNOW_KEY = '2aede9eeff19d0a2c253a2ca1134c854';

/** Social card. 1200×630, regenerate with `npm run og`. */
export const OG_IMAGE = '/og-card.png';
export const OG_IMAGE_ALT = `${SITE_TITLE}: a privacy-first Android keyboard with 843 languages, offline intelligence, tools and themes.`;

/**
 * Who the structured data credits. `SITE_AUTHOR` is the copyright holder in
 * LICENSE; `REPO_URL` is what `sameAs` points at, so a search engine can tie
 * this site, the repository and the Play listing together as one entity.
 */
export const SITE_AUTHOR = 'Wasi Master';
export const REPO_URL = 'https://github.com/wasi-master/wmkeyboard';
export const PLAY_URL =
	'https://play.google.com/store/apps/details?id=com.wasimaster.wmkeyboard';
export const APP_ID = 'com.wasimaster.wmkeyboard';

/**
 * The project's own accounts, in the order the header shows them. `icon` is a
 * Starlight built-in icon name. Every handle is `wmkeyboard` except Instagram,
 * where that name was taken: keep `wmkeyboardapp` there.
 */
export const SOCIALS = [
	{ icon: 'telegram', label: 'Telegram', handle: 't.me/wmkeyboard', href: 'https://t.me/wmkeyboard' },
	{ icon: 'x.com', label: 'X', handle: '@wmkeyboard', href: 'https://x.com/wmkeyboard' },
	{ icon: 'reddit', label: 'Reddit', handle: 'r/wmkeyboard', href: 'https://www.reddit.com/r/wmkeyboard' },
	{ icon: 'instagram', label: 'Instagram', handle: '@wmkeyboardapp', href: 'https://www.instagram.com/wmkeyboardapp' },
	{ icon: 'tiktok', label: 'TikTok', handle: '@wmkeyboard', href: 'https://www.tiktok.com/@wmkeyboard' },
];

/**
 * The sidebar's shape, and the only place a section's name lives.
 *
 * `astro.config.mjs` builds the sidebar from this, and the SEO layer reads the
 * same list to name a page's section in its `<title>`, its BreadcrumbList and
 * its llms.txt group. Renaming a group used to mean editing four places and
 * finding out later that one of them still said the old name.
 */
export const SECTIONS = [
	{ label: 'Getting started', directory: 'start' },
	{ label: 'Typing', directory: 'typing' },
	{ label: 'Languages', directory: 'languages' },
	{ label: 'Suggestions & correction', directory: 'smart' },
	{ label: 'Emoji & expression', directory: 'emoji' },
	{ label: 'Tools', directory: 'tools' },
	{ label: 'Themes & appearance', directory: 'themes' },
	{ label: 'Addons', directory: 'addons' },
	{ label: 'Plugins', directory: 'plugins', badge: { text: 'Python', variant: 'tip' } },
	{ label: 'Privacy & security', directory: 'privacy' },
	{ label: 'Accessibility', directory: 'accessibility' },
	{ label: 'Reference', directory: 'reference', collapsed: true },
	{ label: 'Development', directory: 'development', collapsed: true },
];

/** The section label for a doc id ('typing/glide-typing'), or undefined for the landing page. */
export const sectionFor = (id) =>
	SECTIONS.find((s) => id === s.directory || id.startsWith(`${s.directory}/`))?.label;

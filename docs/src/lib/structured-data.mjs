/**
 * The JSON-LD the site publishes, built as one `@graph` per page.
 *
 * Every page carries the same three nodes — the WebSite, the app it documents
 * and the person who wrote both — under stable `@id`s, so a crawler reading two
 * pages recognises one entity rather than two copies of it. The page's own node
 * (`TechArticle`, or `SoftwareApplication` on an addon page) then points back at
 * those `@id`s instead of repeating them, which is what lets Google build the
 * knowledge-panel links between the site, the repository and the Play listing.
 *
 * Nothing here invents a fact. There are no `aggregateRating` or `review` nodes,
 * because the site has no ratings to report and Google treats a fabricated one
 * as a structured-data violation, not a ranking boost.
 */

import {
	APP_ID,
	OG_IMAGE,
	PLAY_URL,
	REPO_URL,
	SOCIALS,
	SITE_AUTHOR,
	SITE_DESCRIPTION,
	SITE_TITLE,
	SITE_URL,
} from '../site.mjs';

/** Absolute URL for a site-relative path, always with the trailing slash the site serves. */
const abs = (path, site = SITE_URL) => new URL(path, site).href;

// Stable node identities. A fragment `@id` is the convention for "this thing,
// described on this site" — the URL resolves, but the fragment says the node is
// the entity rather than the page.
export const ID_SITE = `${SITE_URL}/#website`;
export const ID_APP = `${SITE_URL}/#app`;
export const ID_AUTHOR = `${SITE_URL}/#author`;

const authorNode = {
	'@type': 'Person',
	'@id': ID_AUTHOR,
	name: SITE_AUTHOR,
	url: `${REPO_URL.replace(/\/[^/]+$/, '')}`,
	sameAs: [REPO_URL.replace(/\/[^/]+$/, '')],
};

const siteNode = {
	'@type': 'WebSite',
	'@id': ID_SITE,
	url: `${SITE_URL}/`,
	name: `${SITE_TITLE} documentation`,
	alternateName: SITE_TITLE,
	description: SITE_DESCRIPTION,
	inLanguage: 'en',
	publisher: { '@id': ID_AUTHOR },
	about: { '@id': ID_APP },
	license: 'https://opensource.org/licenses/MIT',
};

/**
 * The keyboard itself. `MobileApplication` is the narrower type Google
 * documents for app results, and the one an Android install belongs under.
 *
 * `offers` at price 0 is what marks the app free; omitting it leaves the app
 * result with no price at all, which reads as unknown rather than as free.
 */
const appNode = (site) => ({
	'@type': ['MobileApplication', 'SoftwareApplication'],
	'@id': ID_APP,
	name: SITE_TITLE,
	alternateName: 'WMKeyboard',
	description: SITE_DESCRIPTION,
	applicationCategory: 'UtilitiesApplication',
	applicationSubCategory: 'Keyboard',
	operatingSystem: 'Android 8.0+',
	url: `${SITE_URL}/`,
	downloadUrl: PLAY_URL,
	installUrl: PLAY_URL,
	softwareHelp: { '@id': ID_SITE },
	image: abs(OG_IMAGE, site),
	screenshot: abs(OG_IMAGE, site),
	author: { '@id': ID_AUTHOR },
	publisher: { '@id': ID_AUTHOR },
	license: 'https://opensource.org/licenses/MIT',
	isAccessibleForFree: true,
	offers: { '@type': 'Offer', price: '0', priceCurrency: 'USD', availability: 'https://schema.org/InStock' },
	identifier: APP_ID,
	sameAs: [REPO_URL, PLAY_URL, ...SOCIALS.map((s) => s.href)],
	featureList: [
		'Offline word prediction and autocorrect',
		'Glide (swipe) typing',
		'843 language layouts',
		'Clipboard history and text tools',
		'Themes, fonts and icon packs',
		'Sandboxed Python plugins',
		'No analytics and no network access for typing',
	],
	keywords:
		'android keyboard, privacy keyboard, offline keyboard, glide typing, swipe typing, custom layouts, open source keyboard',
});

/** The nodes every page shares. */
const baseNodes = (site) => [siteNode, appNode(site), authorNode];

/**
 * The trail above the page, as the crumbs a search result shows in place of
 * the raw URL. `crumbs` is the list PageTitle.astro renders (src/lib/crumbs.ts),
 * so the markup and what the reader sees cannot disagree — which Google asks
 * for, and which two separately-built trails were not doing.
 */
export const breadcrumbNode = ({ canonical, crumbs }) => ({
	'@type': 'BreadcrumbList',
	'@id': `${canonical}#breadcrumb`,
	itemListElement: crumbs.map((crumb, index) => ({
		'@type': 'ListItem',
		position: index + 1,
		name: crumb.text,
		item: new URL(crumb.href, canonical).href,
	})),
});

/**
 * A documentation page. `TechArticle` is the right shape for a how-to-use page:
 * it carries `dateModified` (which is what surfaces as a freshness signal) and
 * `proficiencyLevel`, and unlike plain `Article` it doesn't imply a news item.
 */
export const docPageGraph = ({ canonical, title, description, section, crumbs, image, modified, headings }) => {
	const site = new URL(canonical).origin;
	const article = {
		'@type': 'TechArticle',
		'@id': `${canonical}#article`,
		headline: title,
		name: title,
		description,
		url: canonical,
		inLanguage: 'en',
		isPartOf: { '@id': ID_SITE },
		about: { '@id': ID_APP },
		author: { '@id': ID_AUTHOR },
		publisher: { '@id': ID_AUTHOR },
		image,
		breadcrumb: { '@id': `${canonical}#breadcrumb` },
		mainEntityOfPage: canonical,
		license: 'https://opensource.org/licenses/MIT',
		isAccessibleForFree: true,
		...(section ? { articleSection: section } : {}),
		...(modified ? { dateModified: modified } : {}),
	};
	// `hasPart` carries the on-page headings with their anchors, so an answer
	// engine can cite the section that answers a question, not the whole page.
	if (headings?.length) {
		article.hasPart = headings.map((h) => ({
			'@type': 'WebPageElement',
			name: h.text,
			url: `${canonical}#${h.slug}`,
		}));
	}

	return [...baseNodes(site), article, breadcrumbNode({ canonical, crumbs })];
};

/**
 * The landing page. It describes the app rather than an article, so the page
 * node is a `WebPage` whose `mainEntity` is the app — the arrangement Google
 * reads as "this is the app's home", not "this is a blog post about an app".
 */
export const homeGraph = ({ canonical, title, description, image }) => {
	const site = new URL(canonical).origin;
	return [
		...baseNodes(site),
		{
			'@type': ['WebPage', 'CollectionPage'],
			'@id': `${canonical}#webpage`,
			url: canonical,
			name: title,
			description,
			isPartOf: { '@id': ID_SITE },
			about: { '@id': ID_APP },
			mainEntity: { '@id': ID_APP },
			primaryImageOfPage: image,
			inLanguage: 'en',
			publisher: { '@id': ID_AUTHOR },
		},
	];
};

/**
 * A page whose H2s are questions. Google's FAQ rich result is now limited to a
 * handful of sites, but the markup still feeds AI Overviews and the "people
 * also ask" extraction, and it costs one node.
 */
export const faqNode = ({ canonical, questions }) => ({
	'@type': 'FAQPage',
	'@id': `${canonical}#faq`,
	mainEntity: questions.map((q) => ({
		'@type': 'Question',
		name: q.question,
		acceptedAnswer: { '@type': 'Answer', text: q.answer },
	})),
});

/**
 * One addon in the store. A theme or a font is a `CreativeWork`; a plugin is a
 * program, so it gets `SoftwareApplication` with the keyboard as its
 * `requiresSubscription`-free prerequisite via `isPartOf`.
 */
export const addonGraph = ({ canonical, name, description, kind, type, image, repoName, repoUrl, author, version, license }) => {
	const site = new URL(canonical).origin;
	const isProgram = type === 'plugin';
	const node = {
		'@type': isProgram ? 'SoftwareApplication' : 'CreativeWork',
		'@id': `${canonical}#addon`,
		name,
		description,
		url: canonical,
		inLanguage: 'en',
		isPartOf: { '@id': ID_SITE },
		genre: kind,
		image,
		isAccessibleForFree: true,
		offers: { '@type': 'Offer', price: '0', priceCurrency: 'USD' },
		...(version ? { version } : {}),
		...(license ? { license } : {}),
		...(author ? { author: { '@type': 'Person', name: author } } : {}),
		...(repoName ? { provider: { '@type': 'Organization', name: repoName, ...(repoUrl ? { url: repoUrl } : {}) } } : {}),
	};
	if (isProgram) {
		node.applicationCategory = 'UtilitiesApplication';
		node.operatingSystem = 'Android 8.0+';
		node.softwareRequirements = SITE_TITLE;
	} else {
		node.encodingFormat = 'application/json';
	}
	return [...baseNodes(site), node];
};

/**
 * A repository's page, which is a list of addons. `ItemList` with `url`s is
 * what lets a search result for "WM Keyboard themes" show the entries rather
 * than only the page — and it is the one place on this site where a list of
 * links is the content, so it is the one place the markup earns its bytes.
 */
export const collectionGraph = ({ canonical, title, description, image, items }) => {
	const site = new URL(canonical).origin;
	return [
		...baseNodes(site),
		{
			'@type': 'CollectionPage',
			'@id': `${canonical}#webpage`,
			url: canonical,
			name: title,
			description,
			isPartOf: { '@id': ID_SITE },
			about: { '@id': ID_APP },
			inLanguage: 'en',
			publisher: { '@id': ID_AUTHOR },
			...(image ? { primaryImageOfPage: image } : {}),
			mainEntity: {
				'@type': 'ItemList',
				'@id': `${canonical}#list`,
				numberOfItems: items.length,
				itemListOrder: 'https://schema.org/ItemListUnordered',
				itemListElement: items.map((item, index) => ({
					'@type': 'ListItem',
					position: index + 1,
					name: item.name,
					url: item.url,
					...(item.description ? { description: item.description } : {}),
				})),
			},
		},
	];
};

/** `@graph` wrapper. One script tag per page, one context, many nodes. */
export const graph = (nodes) => ({ '@context': 'https://schema.org', '@graph': nodes });

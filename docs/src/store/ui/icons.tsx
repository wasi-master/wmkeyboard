/** Inline stroke icons (Lucide-style, 24-unit box) so the store ships no icon font. */
import type { JSX } from 'preact';

type P = JSX.SVGAttributes<SVGSVGElement>;

const base = (d: string | string[], extra?: P) => (props: P) => (
	<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" {...extra} {...props}>
		{(Array.isArray(d) ? d : [d]).map((p) => (
			<path d={p} />
		))}
	</svg>
);

export const IconSearch = base(['M21 21l-4.35-4.35', 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z']);
export const IconChevron = base('m6 9 6 6 6-6');
export const IconChevronRight = base('m9 6 6 6-6 6');
export const IconBack = base(['M19 12H5', 'm12 19-7-7 7-7']);
export const IconStar = (props: P & { filled?: boolean }) => (
	<svg viewBox="0 0 24 24" fill={props.filled ? 'currentColor' : 'none'} stroke="currentColor" stroke-width="2" stroke-linejoin="round" aria-hidden="true" {...props}>
		<path d="m12 2.5 2.9 6.2 6.8.8-5 4.7 1.3 6.8L12 17.7 5.9 21l1.3-6.8-5-4.7 6.8-.8z" />
	</svg>
);
export const IconDownload = base(['M12 3v12', 'm7 10 5 5 5-5', 'M4 19h16']);
export const IconQr = base(['M4 4h6v6H4z', 'M14 4h6v6h-6z', 'M4 14h6v6H4z', 'M14 14h3v3h-3z', 'M20 14v3', 'M17 20h3']);
export const IconShare = base(['M4 12v7a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-7', 'M12 3v12', 'm7 8 5-5 5 5']);
export const IconCopy = base(['M9 9h10v10H9z', 'M5 15V5h10']);
export const IconExternal = base(['M14 4h6v6', 'M20 4 10 14', 'M18 13v6H5V6h6']);
export const IconCheck = base('m5 12 5 5 9-10');
export const IconRefresh = base(['M3 12a9 9 0 0 1 15.5-6.3L21 8', 'M21 3v5h-5', 'M21 12a9 9 0 0 1-15.5 6.3L3 16', 'M3 21v-5h5']);
export const IconTrash = base(['M4 7h16', 'M9 7V4h6v3', 'M6 7l1 13h10l1-13', 'M10 11v6', 'M14 11v6']);
export const IconPlus = base(['M12 5v14', 'M5 12h14']);
export const IconX = base(['M6 6l12 12', 'M18 6 6 18']);
export const IconGrid = base(['M4 4h7v7H4z', 'M13 4h7v7h-7z', 'M4 13h7v7H4z', 'M13 13h7v7h-7z']);
export const IconList = base(['M8 6h13', 'M8 12h13', 'M8 18h13', 'M3 6h.01', 'M3 12h.01', 'M3 18h.01']);
export const IconWarn = base(['M12 3 2 20h20z', 'M12 9v5', 'M12 17h.01']);
export const IconInfo = base(['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M12 11v5', 'M12 8h.01']);
export const IconPlay = (props: P) => (
	<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" {...props}>
		<path d="M7 4.5v15l12-7.5z" />
	</svg>
);
export const IconPause = (props: P) => (
	<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" {...props}>
		<path d="M6 4h4v16H6zM14 4h4v16h-4z" />
	</svg>
);
export const IconSparkle = base(['M12 3v4', 'M12 17v4', 'M3 12h4', 'M17 12h4', 'm12 7 1.8 3.2L17 12l-3.2 1.8L12 17l-1.8-3.2L7 12l3.2-1.8z']);
export const IconHeart = base('M12 20.5s-7.5-4.6-7.5-10A4 4 0 0 1 12 8a4 4 0 0 1 7.5 2.5c0 5.4-7.5 10-7.5 10z');
export const IconPulse = base(['M3 12h4l3-7 4 14 3-7h4']);
export const IconCompare = base(['M4 6h7', 'M4 12h7', 'M4 18h7', 'M13 6h7', 'M13 12h7', 'M13 18h7']);
export const IconWand = base(['m15 4 5 5', 'M5 19 14 10', 'M18 2v3', 'M21 5h-3', 'M4 9l1.5 1.5', 'M9 4 7.5 5.5']);
export const IconBook = base(['M4 4h12a3 3 0 0 1 3 3v13H7a3 3 0 0 0-3 3z', 'M4 17a3 3 0 0 1 3-3h12']);
export const IconPhone = base(['M7 3h10a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z', 'M11 18h2']);
export const IconLink = base(['M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1', 'M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1']);
export const IconFile = base(['M6 3h8l5 5v13H6z', 'M14 3v5h5']);
export const IconUpload = base(['M12 17V5', 'm7 10 5-5 5 5', 'M4 19h16']);
export const IconShield = base(['M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6z', 'm9 12 2 2 4-4']);
export const IconGlobe = base(['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M3 12h18', 'M12 3a14 14 0 0 1 0 18', 'M12 3a14 14 0 0 0 0 18']);
export const IconEdit = base(['M4 20h4l10-10-4-4L4 16z', 'm13 7 4 4']);
export const IconMore = base(['M12 6h.01', 'M12 12h.01', 'M12 18h.01']);
export const IconHome = base(['m3 11 9-8 9 8', 'M5 10v10h14V10']);
export const IconClock = base(['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M12 7v5l3 2']);
export const IconLayers = base(['m12 3 9 5-9 5-9-5z', 'm3 13 9 5 9-5', 'm3 17 9 5 9-5']);

/* Type glyphs, mirroring the Material icons the app uses per type. */
const TYPE_PATHS: Record<string, string[]> = {
	theme: ['M12 3a9 9 0 0 0 0 18c1.2 0 2-.8 2-1.8 0-.5-.2-.9-.5-1.2-.3-.3-.5-.7-.5-1.2 0-1 .8-1.8 1.8-1.8H17a4 4 0 0 0 4-4c0-4.4-4-8-9-8z', 'M7.5 11.5h.01', 'M10 7.5h.01', 'M14.5 7.5h.01', 'M17 11h.01'],
	layout: ['M3 6h18v12H3z', 'M6 9h2', 'M11 9h2', 'M16 9h2', 'M6 13h2', 'M11 13h2', 'M16 13h2', 'M8 16h8'],
	dictionary: ['M4 4h12a3 3 0 0 1 3 3v13H7a3 3 0 0 0-3 3z', 'M4 17a3 3 0 0 1 3-3h12', 'M8 8h6'],
	emoji_keywords: ['M4 5h9', 'M8.5 5v2', 'M5 9c1.5 3 4 5.5 7 7', 'M11 9c-1.5 3-4 5.5-7 7', 'm13 20 3.5-8 3.5 8', 'M14.2 17.5h4.6'],
	snippets: ['M6 3h8l5 5v13H6z', 'M14 3v5h5', 'M9 12h6', 'M9 16h6'],
	espanso: ['m13 2-9 12h7l-1 8 9-12h-7z'],
	stickers: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M9 10h.01', 'M15 10h.01', 'M8.5 14.5c1 1.2 2.2 1.8 3.5 1.8s2.5-.6 3.5-1.8'],
	// A speech bubble with lines of text: a sticker drawn from what you type.
	dynamic_stickers: ['M4 5h16v11h-9l-5 4v-4H4z', 'M8 9h8', 'M8 12h5', 'M19 2.5v2', 'M18 3.5h2'],
	icon_pack: ['M4 4h7v7H4z', 'M13 4h7v7h-7z', 'M4 13h7v7H4z', 'M16.5 13.5v7', 'M13 17h7'],
	font: ['M5 20 12 4l7 16', 'M8 14h8'],
	emoji_font: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M8.5 14.5c1 1.2 2.2 1.8 3.5 1.8s2.5-.6 3.5-1.8', 'M9 10h.01', 'M15 10h.01'],
	sound: ['M4 12h2l2-6 3 12 3-9 2 4h4'],
	sound_pack: ['M4 18V6', 'M8 18V9', 'M12 18V4', 'M16 18v-7', 'M20 18v-4'],
	plugin: ['m8 6-5 6 5 6', 'm16 6 5 6-5 6', 'm14 4-4 16'],
	vocabulary: ['M4 4h12a3 3 0 0 1 3 3v13H7a3 3 0 0 0-3 3z', 'M4 17a3 3 0 0 1 3-3h12', 'M8 8h6', 'M8 11h4'],
};

export function TypeIcon({ type, ...props }: P & { type: string }) {
	const paths = TYPE_PATHS[type] ?? ['M4 4h16v16H4z', 'M9 9h6v6H9z'];
	return (
		<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" {...props}>
			{paths.map((d) => (
				<path d={d} />
			))}
		</svg>
	);
}

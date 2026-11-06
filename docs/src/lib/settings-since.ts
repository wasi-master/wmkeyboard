/**
 * Which version of the app first opens a settings link.
 *
 * The data is `src/data/settings-since.json`, written from git tags by
 * `scripts/extract-settings-since.mjs`: the first release whose route
 * allowlist has a screen, and whose string resources have a row's name. Every
 * screen and row the app has is listed; nothing is dated before `floor`, the
 * first release that answers `wmkeyboard://` links at all. A key missing from
 * the data is newer than the data, and is `floor` unless a link says more.
 *
 * A version newer than `latest` is not in a release yet. It is `next` in the
 * data, the version main is heading for, which is also the earliest the next
 * tag can be.
 */
import data from '../data/settings-since.json';
import { settingLink, settingsLink, type Explanation } from './deep-links';

export const SINCE_FLOOR: string = data.floor;
export const LATEST_RELEASE: string = data.latest;
export const NEXT_RELEASE: string = data.next;
/**
 * The first release that reads `since=`. An older copy opens nothing for a
 * link to a screen it lacks; this one and later say which version to get.
 */
export const SINCE_AWARE: string = data.sinceAware;

const routes = data.routes as Record<string, string>;
const settings = data.settings as Record<string, string>;
const modes = (data.modes ?? {}) as Record<string, string>;
/**
 * The first release that scrolls to a row on one keyboard mode's editor
 * (`mode_edit/mode_browser?setting=…`). An older copy opens the mode and
 * pulses nothing, so the row is not there for it yet (#323).
 */
export const MODE_ROWS_SINCE: string = data.modeRows ?? SINCE_FLOOR;

const MODE_EDIT = 'mode_edit/{modeId}';

/** Numeric, part by part, the way `AppVersion.compare` in the app does it. */
export function compareVersions(a: string, b: string): number {
	const pa = a.split('.').map((n) => parseInt(n, 10) || 0);
	const pb = b.split('.').map((n) => parseInt(n, 10) || 0);
	for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
		const d = (pa[i] ?? 0) - (pb[i] ?? 0);
		if (d !== 0) return Math.sign(d);
	}
	return 0;
}

function newest(...versions: (string | undefined)[]): string {
	return versions.filter((v): v is string => !!v).reduce((a, b) => (compareVersions(a, b) >= 0 ? a : b), SINCE_FLOOR);
}

export interface Since {
	/** The first version that opens the link. */
	version: string;
	/** False while no release has it: the link waits for the next one. */
	released: boolean;
}

const knows = (map: Record<string, string>, key: string | undefined) => !key || Object.prototype.hasOwnProperty.call(map, key);

/**
 * The version a settings link needs, or null for a link that is not a
 * settings link.
 *
 * The link's own `since=` counts only for a screen, row or mode the data does
 * not list yet. A link copied in the app carries the sender's version there,
 * not the version the row arrived in (the app keeps no table of that), so for
 * a row listed here it would date a 0.5 row to whatever the sender runs.
 */
export function sinceOf(x: Explanation): Since | null {
	let version: string;
	if (x.kind === 'settings') {
		// One mode's editor: that mode has to ship with the app, and a row on it
		// needs the release that finds rows through the mode.
		const mode = x.pattern === MODE_EDIT ? x.route.split('/')[1] : undefined;
		const known = knows(routes, x.pattern) && knows(settings, x.setting || undefined) && knows(modes, mode);
		version = newest(
			routes[x.pattern],
			x.setting ? settings[x.setting] : undefined,
			mode ? modes[mode] : undefined,
			mode && x.setting ? MODE_ROWS_SINCE : undefined,
			known ? undefined : x.since,
		);
	} else if (x.kind === 'setting') {
		version = newest(settings[x.setting], knows(settings, x.setting) ? undefined : x.since);
	} else {
		return null;
	}
	return { version, released: compareVersions(version, LATEST_RELEASE) <= 0 };
}

/**
 * The `since=` worth putting in a link, or undefined when it would add
 * nothing. Every copy of the app that opens links at all is at least
 * `floor`, so a link that `floor` already opens has no copy to warn.
 */
export function sinceParamFor(version: string): string | undefined {
	return compareVersions(version, SINCE_FLOOR) > 0 ? version : undefined;
}

/** [link] rebuilt with its `since=` filled in, when that says anything. */
export function withSince(x: Explanation): string | null {
	const since = sinceOf(x);
	if (!since) return null;
	const param = sinceParamFor(since.version);
	if (x.kind === 'setting') return settingLink(x.setting, param);
	if (x.kind !== 'settings') return null;
	return settingsLink({ route: x.route === 'home' ? '' : x.route, setting: x.setting || undefined, since: param });
}

/** "Added in 0.5.9", or the line for a version that is not out yet. */
export function sinceLabel(since: Since): string {
	return since.released ? `Added in version ${since.version}` : 'Not released yet';
}

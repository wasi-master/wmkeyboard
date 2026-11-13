#!/usr/bin/env node
/**
 * Writes src/data/settings-since.json: the first release of the app that can
 * open each settings screen and each settings row a link can name.
 *
 * A link to a screen or a row only works on a copy of the app that has that
 * screen or row. The /open/ page, the link builder and the <SettingsPath>
 * chips read this file to say "Added in 0.5.11", and to put `since=0.5.11` in
 * the links they make, so a copy of the app older than that can say it needs an
 * update instead of doing nothing.
 *
 * The answer comes from git, not from a list kept by hand. For every release
 * tag it reads two things at that tag:
 *
 *   - the route patterns in SettingsRoutes.all, the allowlist a link's screen
 *     is checked against;
 *   - every <string name="…"> in a res/values/ file, because a row is named by
 *     the resource name of its title (see SettingsDeepLink).
 *
 * The first tag that has a route or a name is its version. Nothing is older
 * than `floor`, the first tag with SettingsDeepLink.kt: before it the app did
 * not answer wmkeyboard:// links at all. A route or row that no tag has yet is
 * `next`, the version after `latest`, so a link made from main today still
 * says which release it waits for.
 *
 * Every route and row the app has today is written, `floor` ones included,
 * so a reader can tell a key it knows from one it does not. The app bundles
 * this file as an asset (bundleSettingsSince in app/build.gradle.kts) and
 * dates the links it copies from it; an older copy, or a row newer than its
 * bundled table, carries the sender's own version as `since=`, which for a
 * key listed here is only an upper bound, so the date here wins. A key missing here is newer than this file,
 * and only then does a link's `since=` count. `sinceAware` is the first
 * release that reads `since=` itself.
 *
 * Keyboard modes get two more answers (#323). `modes` dates each built-in
 * mode's id, the first tag whose KeyboardModes.kt ships it, since
 * `mode_edit/mode_chat` opens nothing useful on a copy without a Chat mode.
 * `modeRows` is the first release that scrolls to a row on one mode's editor:
 * the first tag whose SettingsDeepLink.kt matches a row through its
 * `screenPattern`. An older copy still opens the mode, but pulses nothing.
 *
 * Run from docs/:  node scripts/extract-settings-since.mjs
 * Rerun after every release tag, and after scripts/extract_settings_links.sh.
 * Needs the tags: `git fetch --tags` first on a fresh clone.
 */
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const DOCS = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const REPO = resolve(DOCS, '..');
const OUT = resolve(DOCS, 'src/data/settings-since.json');
const LINKS = resolve(DOCS, 'src/data/settings-links.json');
const ROUTES_KT = 'app/src/main/java/com/wasimaster/wmkeyboard/app/SettingsRoutes.kt';
const DEEP_LINK_KT = 'app/src/main/java/com/wasimaster/wmkeyboard/app/SettingsDeepLink.kt';

function git(...args) {
	return execFileSync('git', args, {
		cwd: REPO,
		encoding: 'utf8',
		maxBuffer: 256 * 1024 * 1024,
		stdio: ['ignore', 'pipe', 'pipe'],
	});
}

function gitOrNull(...args) {
	try {
		return git(...args);
	} catch {
		return null;
	}
}

/** Numeric, part by part. "0.5.10" is newer than "0.5.9". */
function compare(a, b) {
	const pa = a.split('.').map((n) => parseInt(n, 10) || 0);
	const pb = b.split('.').map((n) => parseInt(n, 10) || 0);
	for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
		const d = (pa[i] ?? 0) - (pb[i] ?? 0);
		if (d !== 0) return Math.sign(d);
	}
	return 0;
}

/** The quoted entries of `val all = listOf(…)`, one per line in the Kotlin. */
function routesIn(source) {
	const start = source.indexOf('val all: List<String> = listOf(');
	if (start < 0) return new Set();
	const body = source.slice(start, source.indexOf('\n    )', start));
	return new Set([...body.matchAll(/^\s+"([^"]+)",\s*$/gm)].map((m) => m[1]));
}

function namesAt(ref) {
	// Exit status 1 is "no match", which a tag with no strings at all would be.
	const out = gitOrNull('grep', '-h', '-o', '-E', '<string name="[a-z0-9_]+"', ref, '--', '*/res/values/*.xml') ?? '';
	return new Set([...out.matchAll(/name="([a-z0-9_]+)"/g)].map((m) => m[1]));
}

const tags = git('tag', '--list', 'v*', '--sort=version:refname')
	.split('\n')
	.map((t) => t.trim())
	.filter((t) => /^v\d+(\.\d+)*$/.test(t));
if (!tags.length) {
	console.error('No v* tags. Run `git fetch --tags` first.');
	process.exit(1);
}

const linkTags = tags.filter((t) => gitOrNull('cat-file', '-e', `${t}:${DEEP_LINK_KT}`) !== null);
if (!linkTags.length) {
	console.error(`No tag has ${DEEP_LINK_KT} yet, so no release opens a link.`);
	process.exit(1);
}
const version = (tag) => tag.slice(1);
const floor = version(linkTags[0]);
const latest = version(tags[tags.length - 1]);

// The version main is heading for: gradle.properties once it has been bumped
// past the last tag, else the next patch release, which is the earliest the
// next tag can be.
const planned = /^wmkb\.versionName=(.+)$/m.exec(readFileSync(resolve(REPO, 'gradle.properties'), 'utf8'))?.[1]?.trim();
const bumped = latest.split('.').map((n) => parseInt(n, 10) || 0);
bumped[bumped.length - 1] += 1;
const next = planned && compare(planned, latest) > 0 ? planned : bumped.join('.');

// The first release that reads since= itself, and so can tell the user which
// version a link needs rather than opening nothing.
const awareTag = linkTags.find((t) => (gitOrNull('show', `${t}:${DEEP_LINK_KT}`) ?? '').includes('SINCE_PARAM'));
const sinceAware = awareTag ? version(awareTag) : next;

const wantedRoutes = routesIn(readFileSync(resolve(REPO, ROUTES_KT), 'utf8'));
// A shipped mode's editor is a screen named by the mode's id, not by a string
// resource, so it has no name to date; its route pattern dates it instead.
const wantedNames = new Set(
	JSON.parse(readFileSync(LINKS, 'utf8'))
		.filter((e) => !(e.screen && e.pattern))
		.map((e) => e.name),
);

const routes = {};
const settings = {};
const routesLeft = new Set(wantedRoutes);
const namesLeft = new Set(wantedNames);
for (const tag of linkTags) {
	const v = version(tag);
	const tagRoutes = routesIn(git('show', `${tag}:${ROUTES_KT}`));
	const tagNames = namesAt(tag);
	for (const r of [...routesLeft]) {
		if (!tagRoutes.has(r)) continue;
		routesLeft.delete(r);
		routes[r] = v;
	}
	for (const n of [...namesLeft]) {
		if (!tagNames.has(n)) continue;
		namesLeft.delete(n);
		settings[n] = v;
	}
}
for (const r of routesLeft) routes[r] = next;
for (const n of namesLeft) settings[n] = next;

// Built-in mode ids, from any KeyboardModes.kt at the tag: the file has moved
// between modules, and a path-free grep follows it.
function modeIdsAt(ref) {
	const out = gitOrNull('grep', '-h', '-o', '-E', 'id = "mode_[a-z_]+"', ref, '--', '*KeyboardModes.kt') ?? '';
	return new Set([...out.matchAll(/"(mode_[a-z_]+)"/g)].map((m) => m[1]));
}
const wantedModes = modeIdsAt('HEAD');
const modes = {};
const modesLeft = new Set(wantedModes);
for (const tag of linkTags) {
	const v = version(tag);
	const tagModes = modeIdsAt(tag);
	for (const m of [...modesLeft]) {
		if (!tagModes.has(m)) continue;
		modesLeft.delete(m);
		modes[m] = v;
	}
}
for (const m of modesLeft) modes[m] = next;
const rowsTag = linkTags.find((t) => (gitOrNull('show', `${t}:${DEEP_LINK_KT}`) ?? '').includes('screenPattern'));
const modeRows = rowsTag ? version(rowsTag) : next;

const sorted = (o) => Object.fromEntries(Object.entries(o).sort(([a], [b]) => a.localeCompare(b)));
writeFileSync(OUT, JSON.stringify({ floor, latest, next, sinceAware, modeRows, routes: sorted(routes), settings: sorted(settings), modes: sorted(modes) }, null, '\t') + '\n');

/** Entries dated [v], or, with [newer] set, dated after it. */
const count = (o, v, newer) => Object.values(o).filter((x) => (newer ? compare(x, v) > 0 : x === v)).length;
console.log(
	`wrote ${OUT}\n` +
		`  floor ${floor}, latest ${latest}, next ${next}, since= read from ${sinceAware}\n` +
		`  routes: ${wantedRoutes.size} (${count(routes, floor, true)} newer than ${floor}, ${count(routes, next)} unreleased)\n` +
		`  rows:   ${wantedNames.size} (${count(settings, floor, true)} newer than ${floor}, ${count(settings, next)} unreleased)\n` +
		`  modes:  ${wantedModes.size} (${count(modes, floor, true)} newer than ${floor}), a row on one mode from ${modeRows}`
);

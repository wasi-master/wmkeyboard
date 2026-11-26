/**
 * Sending a plugin to the community repository: lib/community.ts's issue
 * links and `wmkb:1:` payloads, for the form the repository keeps for
 * plugins, `submit-plugin.yml`. A .wmplugin is a zip of its own, so it goes
 * the way icon and sticker packs do: in the link, pasted, or as the .zip.
 * Plugins take code licences, not the Creative Commons ones the other forms
 * offer (the bot's CODE_LICENSES, in the same order).
 */
import { COMMUNITY_URL, encodePayload, LINK_LIMIT, PASTE_LIMIT, type Fields, type Plan } from '../../../lib/community';

export const PLUGIN_TEMPLATE = 'submit-plugin.yml';
export const PLUGIN_TITLE = '[Plugin]';
/** The most the repository takes for a plugin: the app downloads 1 MB at most. */
export const PLUGIN_MAX_BYTES = 1024 * 1024;
export const PLUGIN_LICENSES = ['MIT', 'Apache-2.0', 'BSD-2-Clause', 'BSD-3-Clause', 'ISC', 'MPL-2.0', 'LGPL-3.0-or-later', 'GPL-3.0-or-later', 'AGPL-3.0-or-later', 'Unlicense', 'CC0-1.0'] as const;

/** The link that opens the plugin form filled in, with the same fields and encoding as lib/community.ts's issueUrl. */
export function pluginIssueUrl(f: Fields, payload: string | null): string {
	const params: [string, string][] = [
		['template', PLUGIN_TEMPLATE],
		['title', `${PLUGIN_TITLE} ${f.name.trim()}`],
		['name', f.name.trim()],
	];
	if (f.description?.trim()) params.push(['description', f.description.trim()]);
	const tags = [...new Set((f.tags ?? []).map((t) => t.trim()).filter(Boolean))];
	if (tags.length) params.push(['tags', tags.join(', ')]);
	params.push(['license', f.license ?? PLUGIN_LICENSES[0]]);
	if (f.updates?.trim()) params.push(['updates', f.updates.trim()]);
	if (f.basedOn?.trim()) params.push(['based_on', f.basedOn.trim()]);
	if (payload != null) params.push(['payload', payload]);
	return `${COMMUNITY_URL}/issues/new?` + params.map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');
}

/** The smallest way the .wmplugin fits: in the link, pasted, or attached. */
export function pluginPlan(f: Fields, file: Uint8Array): Plan {
	const encoded = encodePayload(file);
	const withPayload = pluginIssueUrl(f, encoded);
	if (withPayload.length <= LINK_LIMIT) return { tier: 'link', url: withPayload, encoded };
	return { tier: encoded.length <= PASTE_LIMIT ? 'paste' : 'attach', url: pluginIssueUrl(f, null), encoded };
}

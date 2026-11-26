/**
 * Submit to community, for a plugin: the same form CommunitySubmit.tsx shows
 * under the other builders, opening the community repository's
 * submit-plugin.yml issue with the .wmplugin in the link, on the clipboard,
 * or saved as a .zip to drag in. Nothing is sent from here.
 */
import { useState } from 'preact/hooks';
import { COMMUNITY_URL, type Plan } from '../../../lib/community';
import { slugify } from '../../../lib/util';
import { Notice } from '../../common';
import { IconExternal, IconGlobe } from '../../icons';
import { saveBytes, Select, Tags, Text, Toggle } from '../shared';
import type { ArchiveResult } from './draft-files';
import { sanitise, type PluginManifest } from './ide-store';
import { PLUGIN_LICENSES, PLUGIN_MAX_BYTES, pluginPlan } from './plugin-submit';
import { S } from './strings';

export function PluginSubmit({ manifest, build, problems }: { manifest: PluginManifest; build: () => ArchiveResult; problems: string[] }) {
	const name = sanitise(manifest.name, 'NAME');
	const [description, setDescription] = useState(() => sanitise(manifest.description, 'DESCRIPTION'));
	const [tags, setTags] = useState<string[]>([]);
	const [license, setLicense] = useState<string>(PLUGIN_LICENSES[0]);
	const [updates, setUpdates] = useState('');
	const [typeOnGitHub, setTypeOnGitHub] = useState(false);
	const [made, setMade] = useState<Plan | null>(null);
	const [error, setError] = useState<string | null>(null);

	const submit = async () => {
		setError(null);
		setMade(null);
		const archive = build();
		const problem = problems[0] ?? archive.problems[0];
		if (problem) return setError(problem);
		const file = archive.bytes;
		if (file.byteLength > PLUGIN_MAX_BYTES) return setError(S.submitTooBig);
		const p = pluginPlan({ name, description: typeOnGitHub ? '' : description, tags: typeOnGitHub ? [] : tags, license, updates }, file);
		setMade(p);
		if (p.tier === 'link') window.open(p.url, '_blank', 'noopener');
		else if (p.tier === 'paste') await navigator.clipboard?.writeText(p.encoded).catch(() => undefined);
		// The .wmplugin is a zip already: the bot reads it by its plugin.json.
		else saveBytes(`${slugify(name) || 'plugin'}.zip`, file);
	};

	return (
		<div style="display:flex;flex-direction:column;gap:0.6rem">
			<p class="st-small st-muted" style="margin:0">
				{S.submitIntro.split(S.submitRepository)[0]}<a href={COMMUNITY_URL} target="_blank" rel="noopener">{S.submitRepository}</a>{S.submitIntro.split(S.submitRepository)[1]}
			</p>
			{!typeOnGitHub && <Text label={S.submitDescription} value={description} onInput={setDescription} />}
			{!typeOnGitHub && <Tags label={S.submitTags} value={tags} onInput={setTags} placeholder={S.submitTagsPlaceholder} />}
			<Toggle label={S.submitTypeOnGitHub} value={typeOnGitHub} onInput={setTypeOnGitHub} help={S.submitTypeOnGitHubHelp} />
			<Select label={S.submitLicense} value={license} onInput={setLicense} options={PLUGIN_LICENSES} help={S.submitLicenseHelp} />
			<Text label={S.submitUpdates} mono value={updates} onInput={setUpdates} help={S.submitUpdatesHelp} />
			<button type="button" class="st-btn st-btn-primary" disabled={!name.trim()} onClick={() => void submit()}><IconGlobe /> {S.submitOpen}</button>
			{error && <div class="wm-error-text">{error}</div>}
			{made?.tier === 'paste' && <Notice kind="info">{S.submitPasted} <a href={made.url} target="_blank" rel="noopener">{S.submitOpenIssue} <IconExternal /></a></Notice>}
			{made?.tier === 'attach' && <Notice kind="info">{S.submitAttached} <a href={made.url} target="_blank" rel="noopener">{S.submitOpenIssue} <IconExternal /></a></Notice>}
		</div>
	);
}

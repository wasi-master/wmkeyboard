/** Sound pack builder: samples in, a .wmsoundpack zip (pack.json + sounds/) out. */
import { useMemo, useState } from 'preact/hooks';
import { fmtBytes } from '../../lib/net';
import { SOUND_PACK_MAX_KEYS, SOUND_PACK_MAX_SAMPLES, SOUND_PACK_MAX_VARIANTS, SOUND_ROLES } from '../../lib/payloads';
import { slugify, uid } from '../../lib/util';
import { blobUrl, mimeFor, writeZip } from '../../lib/zip';
import { Notice } from '../common';
import { IconDownload, IconPlay, IconTrash, IconWarn } from '../icons';
import { CommunitySubmit } from './CommunitySubmit';
import { Area, DropZone, ExportPanel, idError, Range, saveBytes, Section, Select, Text, useDraft, type PickedFile } from './shared';

type Phase = 'press' | 'release';
type Role = (typeof SOUND_ROLES)[number];

interface SampleDraft {
	key: string;
	fileName: string;
	role: Role;
	phase: Phase;
	/** The text of the one key this sample belongs to; empty means "use the role". */
	keyChar: string;
}

interface Draft {
	id: string;
	name: string;
	author: string;
	description: string;
	packVersion: string;
	gain: number;
	roleGain: Partial<Record<Role, number | null>>;
	samples: SampleDraft[];
}

type SampleSet = { press: string[]; release: string[] };

const ROLE_WORDS = /^(space|enter|return|del|delete|back|backspace|shift|mod|modifier)$/;

/**
 * The key a file name says it belongs to, or empty for a board-wide sample.
 *
 * Deliberately narrow: a bare single character (`a.wav`, `a-2.wav`, the way a
 * voice pack ships) or an explicit `key_` prefix (`key_th.wav`). Anything
 * longer without the prefix stays a board-wide sample, because a pack whose
 * takes are named `ah.wav` and `oh.wav` means those as variants, not as keys.
 */
function keyFromName(name: string): string {
	let n = name.replace(/\.[^.]+$/, '').toLowerCase().trim();
	const explicit = /^key[_\-\s]+/.test(n);
	n = n.replace(/^key[_\-\s]+/, '');
	n = n.replace(/[_\-\s]*(press|release|up|down)$/, '');
	n = n.replace(/[_\-\s]*\d+$/, '');
	if (!n || ROLE_WORDS.test(n)) return '';
	if (explicit) return n;
	return [...n].length === 1 ? n : '';
}

export function SoundPackBuilder() {
	const [d, setD, reset] = useDraft<Draft>('sound_pack', () => ({ id: '', name: '', author: '', description: '', packVersion: '1.0.0', gain: 1, roleGain: {}, samples: [] }));
	const [files] = useState(() => new Map<string, PickedFile & { url: string }>());
	const [, bump] = useState(0);
	const patch = (p: Partial<Draft>) => setD({ ...d, ...p });
	const editSample = (key: string, p: Partial<SampleDraft>) =>
		setD({ ...d, samples: d.samples.map((x) => (x.key === key ? { ...x, ...p } : x)) });

	const addFiles = (picked: PickedFile[]) => {
		const next: SampleDraft[] = [];
		for (const f of picked) {
			if (!/\.(mp3|ogg|wav|m4a)$/i.test(f.name) && !/^audio\//.test(f.type)) continue;
			const key = uid();
			files.set(key, { ...f, url: blobUrl(f.bytes, f.type || mimeFor(f.name)) });
			const n = f.name.toLowerCase();
			const keyChar = keyFromName(f.name);
			// A keyed sample has no use for a role — keys beat roles — so it
			// lands on the default one rather than guessing from the name.
			const role: Role = keyChar ? 'default' : /space/.test(n) ? 'space' : /enter|return/.test(n) ? 'enter' : /del|back/.test(n) ? 'delete' : /shift|mod/.test(n) ? 'modifier' : 'default';
			next.push({ key, fileName: f.name, role, phase: /release|up/.test(n) ? 'release' : 'press', keyChar });
		}
		setD({ ...d, samples: [...d.samples, ...next] });
	};

	const path = (s: SampleDraft) => `sounds/${s.fileName}`;

	/** The per-key sets, keyed by the text a key types, lowercased as the importer does. */
	const keyed = useMemo(() => {
		const out: Record<string, SampleSet> = {};
		for (const s of d.samples) {
			const k = s.keyChar.trim().toLowerCase();
			if (!k) continue;
			const slot = (out[k] ??= { press: [], release: [] });
			slot[s.phase].push(path(s));
		}
		return out;
	}, [d]);

	const manifest = useMemo(() => {
		const board = (role: Role, phase: Phase) =>
			d.samples.filter((s) => !s.keyChar.trim() && s.role === role && s.phase === phase).map(path);
		const roles: Record<string, SampleSet & { gain?: number }> = {};
		for (const r of SOUND_ROLES.filter((r) => r !== 'default')) {
			const p = board(r, 'press');
			const rl = board(r, 'release');
			const g = d.roleGain[r];
			if (p.length || rl.length) roles[r] = { press: p, release: rl, ...(g != null ? { gain: g } : {}) };
		}
		return {
			format: 'wmkeyboard-sound-pack',
			version: 1,
			id: d.id,
			name: d.name,
			author: d.author,
			packVersion: d.packVersion,
			description: d.description,
			gain: d.gain,
			press: board('default', 'press'),
			release: board('default', 'release'),
			roles,
			// Left out entirely when no key is named, so a pack of switch
			// recordings exports the same bytes it always did.
			...(Object.keys(keyed).length ? { keys: keyed } : {}),
		};
	}, [d, keyed]);

	const missing = d.samples.filter((s) => !files.has(s.key));
	const total = [...files.values()].reduce((n, f) => n + f.bytes.byteLength, 0);
	const keyCount = Object.keys(keyed).length;
	const problems: string[] = [];
	const keyedPress = Object.values(keyed).some((k) => k.press.length);
	if (!manifest.press.length && !keyedPress) {
		problems.push('Nothing plays on key-down; the app refuses a pack that makes no sound.');
	}
	if (d.samples.length > SOUND_PACK_MAX_SAMPLES) problems.push(`Over ${SOUND_PACK_MAX_SAMPLES} samples.`);
	if (keyCount > SOUND_PACK_MAX_KEYS) problems.push(`Over ${SOUND_PACK_MAX_KEYS} named keys; the app drops the rest.`);
	const longest = Math.max(
		manifest.press.length,
		manifest.release.length,
		...Object.values(manifest.roles).flatMap((r) => [r.press.length, r.release.length]),
		...Object.values(keyed).flatMap((k) => [k.press.length, k.release.length]),
		0,
	);
	if (longest > SOUND_PACK_MAX_VARIANTS) problems.push(`One list holds ${longest} takes; the app keeps the first ${SOUND_PACK_MAX_VARIANTS}.`);
	if (total > 16 * 1024 * 1024) problems.push('Over the 16 MB cap.');
	if (!/^\d+\.\d+\.\d+/.test(d.packVersion)) problems.push('packVersion should be semver.');

	const notes: string[] = [];
	if (keyCount && !manifest.press.length) {
		notes.push('No board-wide press sample: keys you have not named will play the system click rather than this pack. One generic recording covers them.');
	}

	const pack = () => {
		const entries: { name: string; data: Uint8Array | string }[] = [{ name: 'pack.json', data: JSON.stringify(manifest, null, 2) + '\n' }];
		for (const s of d.samples) {
			const f = files.get(s.key);
			if (f) entries.push({ name: `sounds/${s.fileName}`, data: f.bytes });
		}
		return writeZip(entries);
	};
	const exportZip = () => saveBytes(`${slugify(d.id || 'sounds')}.wmsoundpack`, pack());

	return (
		<div class="st-creator-layout">
			<div style="display:flex;flex-direction:column;gap:0.8rem">
				<div>
					<span class="st-kicker">Sound pack</span>
					<h1 style="font-size:1.5rem;font-weight:800">Press and release samples</h1>
					<p class="st-muted st-small" style="max-width:62ch;margin-top:0.3rem">
						Several press samples make the keyboard pick one at random per keystroke, the way monkeytype does. Roles (space, enter, delete, modifier) get their own lists; anything without one falls back to the default list. Release lists are optional and mean silence when empty, not a fallback.
					</p>
					<p class="st-muted st-small" style="max-width:62ch;margin-top:0.3rem">
						Give a sample a <strong>key</strong> and it plays for that key alone — a syllable per letter, the way a voice pack works. The key is the text the key types, so <code>a</code> covers it on every layout and on shift. A key you name beats its role; a key you don't name is untouched.
					</p>
				</div>
				<Section title="Pack">
					<div class="st-grid2">
						<Text label="Id" required mono value={d.id} onInput={(v) => patch({ id: v })} error={idError(d.id)} />
						<Text label="Name" required value={d.name} onInput={(v) => patch({ name: v })} />
						<Text label="Author" value={d.author} onInput={(v) => patch({ author: v })} />
						<Text label="Pack version" mono value={d.packVersion} onInput={(v) => patch({ packVersion: v })} />
					</div>
					<Area label="Description" value={d.description} onInput={(v) => patch({ description: v })} rows={2} />
					<Range label="Gain" value={d.gain} min={0} max={1} step={0.05} onInput={(v) => patch({ gain: v })} />
				</Section>
				<Section title={`Samples (${d.samples.length}${keyCount ? `, ${keyCount} keyed` : ''}) · ${fmtBytes(total)}`}>
					<DropZone accept="audio/*,.mp3,.ogg,.wav" onFiles={addFiles}>Drop mp3 / ogg / wav files. Names like space_press.wav or enter-release.mp3 are sorted by role; a single-character name like a.wav or a-2.wav, or a key_th.wav, becomes that key's own sound.</DropZone>
					{missing.length > 0 && <Notice kind="warn" icon={<IconWarn />}>{missing.length} sample{missing.length === 1 ? '' : 's'} from a previous session need the file dropped again.</Notice>}
					<div class="st-entry-list">
						{d.samples.map((s) => {
							const f = files.get(s.key);
							const keyedRow = s.keyChar.trim().length > 0;
							return (
								<div class="st-entry" key={s.key} style="grid-template-columns:auto minmax(0,1fr) auto auto auto auto">
									<button class="st-btn st-btn-icon st-btn-sm" disabled={!f} onClick={() => f && new Audio(f.url).play()} aria-label="Play"><IconPlay /></button>
									<span class="st-entry-text"><span class="st-entry-name">{s.fileName}</span><span class="st-entry-sub">{f ? fmtBytes(f.bytes.byteLength) : 'file missing'}</span></span>
									<input
										class="st-input"
										style="width:5.5rem;padding:0.3rem 0.5rem;font-size:0.8rem;font-family:var(--sl-font-mono)"
										value={s.keyChar}
										placeholder="key"
										aria-label={`Key for ${s.fileName}`}
										onInput={(e) => editSample(s.key, { keyChar: (e.target as HTMLInputElement).value })}
									/>
									<select
										class="st-select"
										style="width:auto;padding:0.3rem 1.8rem 0.3rem 0.5rem;font-size:0.8rem"
										value={s.role}
										disabled={keyedRow}
										title={keyedRow ? 'A keyed sample ignores the role' : undefined}
										onChange={(e) => editSample(s.key, { role: (e.target as HTMLSelectElement).value as Role })}
									>
										{SOUND_ROLES.map((r) => <option value={r} key={r}>{r}</option>)}
									</select>
									<select class="st-select" style="width:auto;padding:0.3rem 1.8rem 0.3rem 0.5rem;font-size:0.8rem" value={s.phase} onChange={(e) => editSample(s.key, { phase: (e.target as HTMLSelectElement).value as Phase })}>
										<option value="press">press</option><option value="release">release</option>
									</select>
									<button class="st-btn st-btn-ghost st-btn-icon st-btn-sm" aria-label="Remove" onClick={() => { files.delete(s.key); setD({ ...d, samples: d.samples.filter((x) => x.key !== s.key) }); bump((n) => n + 1); }}><IconTrash /></button>
								</div>
							);
						})}
					</div>
				</Section>
				<Section title="Per-role gain" open={false}>
					<div class="st-grid2">
						{SOUND_ROLES.filter((r) => r !== 'default').map((r) => (
							<Select key={r} label={r} value={d.roleGain[r] == null ? 'inherit' : String(d.roleGain[r])} onInput={(v) => patch({ roleGain: { ...d.roleGain, [r]: v === 'inherit' ? null : Number(v) } })} options={[['inherit', 'inherit pack gain'], ...['0.25', '0.5', '0.75', '1'].map((g) => [g, g] as [string, string])]} />
						))}
					</div>
				</Section>
			</div>
			<aside class="st-creator-side">
				<ExportPanel title="Export" json={manifest}>
					<button class="st-btn st-btn-primary" disabled={!d.id || problems.length > 0 || missing.length > 0} onClick={exportZip}><IconDownload /> .wmsoundpack</button>
					<button class="st-btn st-btn-ghost st-btn-sm" onClick={() => { if (confirm('Discard this draft?')) { reset(); files.clear(); } }}>Start over</button>
				</ExportPanel>
				<CommunitySubmit kind="sound_pack" name={d.name} file={pack} disabled={!d.id || !d.name || problems.length > 0 || missing.length > 0} />
				{problems.length > 0 && <Notice kind="warn" icon={<IconWarn />}><ul style="padding-left:1rem">{problems.map((p, i) => <li key={i}>{p}</li>)}</ul></Notice>}
				{notes.length > 0 && <Notice kind="info"><ul style="padding-left:1rem">{notes.map((p, i) => <li key={i}>{p}</li>)}</ul></Notice>}
			</aside>
		</div>
	);
}

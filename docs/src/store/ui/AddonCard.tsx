/** One addon in a catalogue grid or list. */
import { useState } from 'preact/hooks';
import { inCollection, noveltyOf, toggleCollection } from '../state';
import { fmtBytes } from '../lib/net';
import { typeInfo, type AddonEntry, type LoadedRepo } from '../lib/types';
import { previewUrls } from '../lib/util';
import { Link } from './common';
import { IconStar, TypeIcon } from './icons';

export function AddonCard({ repo, entry, showRepo }: { repo: LoadedRepo; entry: AddonEntry; showRepo?: boolean }) {
	const info = typeInfo(entry.type);
	const shots = previewUrls(repo.ref.url, entry);
	const [broken, setBroken] = useState(false);
	const key = { repo: repo.ref.url, id: entry.id };
	const starred = inCollection(key);
	const novelty = noveltyOf(repo.ref.url, entry);
	const hue = `#${info.hue}`;
	return (
		<article class="st-card" style={{ '--type-hue': hue }}>
			<Link to={{ view: 'addon', repo: repo.ref.url, id: entry.id }} class="st-card-media" aria-label={entry.name}>
				{shots[0] && !broken ? <img src={shots[0]} alt="" loading="lazy" decoding="async" onError={() => setBroken(true)} /> : (
					<span class="st-glyph">
						<TypeIcon type={entry.type} />
					</span>
				)}
				<span class="st-card-badges">
					<span>{novelty && <span class={`st-pill st-pill-${novelty}`}>{novelty}</span>}</span>
					<span>{entry.type === 'plugin' && <span class="st-pill st-pill-muted">Python</span>}</span>
				</span>
			</Link>
			<div class="st-card-body">
				<Link to={{ view: 'addon', repo: repo.ref.url, id: entry.id }} class="st-card-title" style="text-decoration:none">
					{entry.name}
				</Link>
				<div class="st-card-meta">
					<span class="st-tag st-tag-type">{info.singular}</span>
					<span>v{entry.version}</span>
					{entry.sizeBytes != null && <span>{fmtBytes(entry.sizeBytes)}</span>}
					{showRepo && repo.manifest && <span>· {repo.manifest.repo.name}</span>}
				</div>
				{entry.description && <p class="st-card-desc">{entry.description}</p>}
				{entry.author && <div class="st-card-meta"><span>by {entry.author}</span></div>}
			</div>
			<button
				class="st-card-star"
				aria-pressed={starred}
				aria-label={starred ? 'Remove from collection' : 'Add to collection'}
				title={starred ? 'In your collection' : 'Add to collection'}
				onClick={(e) => { e.preventDefault(); toggleCollection(key); }}
			>
				<IconStar filled={starred} />
			</button>
		</article>
	);
}

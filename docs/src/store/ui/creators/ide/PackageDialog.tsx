/**
 * Add a Python package: the browser's `wmpy pip install`. Finds the package
 * on PyPI, resolves what it needs, downloads each pure wheel and checks its
 * sha256, runs the compatibility check on it, and on Add writes its modules
 * and licences into the draft with wmpy.lock beside them. The packages
 * already in the plugin are listed under it, each with Remove.
 */
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { fmtBytes, NetError } from '../../../lib/net';
import * as Py from '../../../lib/python';
import { downloadWheel, fetchProject, fetchRelease, lockRoots, normalizeName, parseRequirement, PypiError, readWheel, resolvePlan, satisfies, STAND_IN, type Lock, type Plan, type Project, type Requirement } from '../../../lib/pypi';
import { moduleNames, type DraftFiles } from './draft-files';
import { addPackages, neededBy, packageSources, patchPackage, removePackage, standInContents, withDataModules, type Changes, type PreparedPackage } from './packages';
import { Dialog, TextButton } from './parts';
import { S } from './strings';

type Stage =
	| { kind: 'idle' }
	| { kind: 'finding' }
	| { kind: 'found'; project: Project; request: Requirement }
	| { kind: 'checking'; project: Project; request: Requirement }
	| { kind: 'checked'; project: Project; request: Requirement; plan: Plan; prepared: PreparedPackage[] }
	| { kind: 'failed'; message: string; project?: Project; request?: Requirement };

function messageOf(e: unknown, name: string): string {
	if (e instanceof PypiError) {
		const [a, b, c] = e.message.split('|');
		switch (e.kind) {
			case 'not-found': return S.packageNotFound(name);
			case 'network': return S.packageNetwork(e.message);
			case 'sdist-only': return S.packageSdistOnly(a ?? name);
			case 'compiled': return S.packageCompiled(a ?? name);
			case 'python': return S.packagePython(a ?? name);
			case 'no-version': return S.packageNoVersion(a ?? name, b || '*');
			case 'conflict': return S.packageConflict(a ?? name, b ?? '', c ?? '');
			case 'too-many': return S.packageTooMany(Number(a));
			case 'bad-wheel': return S.packageBadWheel(name);
			case 'hash': return S.packageHash(e.message);
		}
	}
	if (e instanceof NetError) return S.packageNetwork(e.message);
	return S.packageNetwork(e instanceof Error ? e.message : String(e));
}

const VERDICT_TEXT = { ok: S.verdictOk, warnings: S.verdictWarnings, incompatible: S.verdictIncompatible };

export function PackageDialog({ files, entry, lock, onApply, onDismiss }: { files: DraftFiles; entry: string; lock: Lock; onApply: (changes: Changes, message: string) => void; onDismiss: () => void }) {
	const [query, setQuery] = useState('');
	const [stage, setStage] = useState<Stage>({ kind: 'idle' });
	const [version, setVersion] = useState('');
	const [collision, setCollision] = useState<string | null>(null);
	const abort = useRef<AbortController | null>(null);
	useEffect(() => () => abort.current?.abort(), []);
	const checkable = Py.pipAvailable().analyse;
	const installed = useMemo(() => Object.entries(lock.packages).sort(([a], [b]) => a.localeCompare(b)), [lock]);

	const signal = () => {
		abort.current?.abort();
		abort.current = new AbortController();
		return abort.current.signal;
	};

	const find = async () => {
		const request = parseRequirement(query);
		if (!request || request.url) return setStage({ kind: 'failed', message: S.packageBadName });
		setStage({ kind: 'finding' });
		setCollision(null);
		try {
			const project = await fetchProject(request.name, signal());
			const fits = project.versions.filter((v) => !v.yanked && satisfies(v.version, request.specifiers));
			const pick = fits.find((v) => v.pure && !v.pre) ?? fits.find((v) => v.pure) ?? fits.find((v) => !v.pre) ?? fits[0] ?? project.versions[0];
			setVersion(pick?.version ?? project.latest);
			setStage({ kind: 'found', project, request });
		} catch (e) {
			if (e instanceof NetError && e.kind === 'aborted') return;
			setStage({ kind: 'failed', message: messageOf(e, request.name) });
		}
	};

	const check = async (project: Project, request: Requirement) => {
		setStage({ kind: 'checking', project, request });
		setCollision(null);
		const s = signal();
		try {
			const plan = await resolvePlan(request, lock, {
				project: (name) => (normalizeName(name) === normalizeName(project.name) ? Promise.resolve(project) : fetchProject(name, s)),
				release: (name, v) => fetchRelease(name, v, s),
				standIn: (key) => Py.pipStandIn(key, key, '')?.source ?? null,
			}, version || undefined);
			const notes: string[][] = plan.packages.map(() => []);
			const contents = await Promise.all(plan.packages.map(async (p, i) => {
				if (p.wheel.packagetype === STAND_IN) {
					const s = standInContents(p);
					if (!s) throw new PypiError('compiled', p.name);
					notes[i]!.push(s.note);
					return s.contents;
				}
				try {
					// Its data files go into generated modules, as wmpy pip writes them.
					const withData = withDataModules(readWheel(await downloadWheel(p.wheel, s)));
					notes[i]!.push(...withData.notes);
					return withData.contents;
				} catch (e) {
					if (e instanceof PypiError && e.kind === 'bad-wheel') throw new PypiError('bad-wheel', p.name);
					throw e;
				}
			}));
			// What each package can import besides the standard library: the other packages, and the plugin's own modules.
			const replaced = new Set(plan.packages.map((p) => p.key));
			const held = lockRoots({ version: 1, packages: Object.fromEntries(Object.entries(lock.packages).filter(([k]) => !replaced.has(k))) });
			const own = moduleNames(Object.keys(files), entry).filter((n) => !n.includes('.'));
			const prepared: PreparedPackage[] = plan.packages.map((planned, i) => {
				const available = [...new Set([...held, ...own, ...contents.flatMap((c, j) => (j === i ? [] : c.roots))])].sort();
				// The small fixes the keyboard keeps for a few packages, as wmpy pip applies them.
				const patched = patchPackage(planned.key, contents[i]!);
				return { planned, contents: patched.contents, patched: [...notes[i]!, ...patched.notes], analysis: Py.pipAnalyse(packageSources(patched.contents), { package: planned.name, available }) };
			});
			setStage({ kind: 'checked', project, request, plan, prepared });
		} catch (e) {
			if (e instanceof NetError && e.kind === 'aborted') return;
			if (e instanceof DOMException && e.name === 'AbortError') return;
			setStage({ kind: 'failed', message: messageOf(e, request.name), project, request });
		}
	};

	const add = (prepared: PreparedPackage[]) => {
		const result = addPackages(files, entry, lock, prepared);
		if (result.collisions.length) return setCollision(S.packageCollision(result.collisions.slice(0, 6).join(', ') + (result.collisions.length > 6 ? ', …' : '')));
		onApply(result.changes, S.packageAdded(prepared.length));
	};

	const remove = (key: string) => {
		const { changes } = removePackage(lock, key);
		onApply(changes, S.packageRemoved(key));
	};

	const busy = stage.kind === 'finding' || stage.kind === 'checking';
	const project = 'project' in stage ? stage.project : undefined;
	// Its compiled-only releases go in as the keyboard's pure-Python stand-in.
	const hasStandIn = project ? Py.pipStandIn(normalizeName(project.name), project.name, '') !== null : false;
	const request = 'request' in stage ? stage.request : undefined;
	const incompatible = stage.kind === 'checked' && stage.prepared.some((p) => p.analysis?.verdict === 'incompatible');
	return (
		<Dialog
			title={S.packagesTitle}
			wide
			onDismiss={onDismiss}
			actions={
				<>
					<TextButton onClick={onDismiss}>{S.close}</TextButton>
					{stage.kind === 'checked' && <TextButton onClick={() => add(stage.prepared)}>{incompatible ? S.packageAddAnyway : S.packageAdd}</TextButton>}
				</>
			}
		>
			<div class="wm-row-between">
				<label class="st-field" style="flex:1 1 auto">
					<span>{S.packageName}</span>
					<input class="st-input" style="font-family:var(--wmc-mono)" placeholder="requests" value={query} autoFocus spellcheck={false} autocapitalize="off" disabled={busy}
						onInput={(e) => setQuery((e.target as HTMLInputElement).value)}
						onKeyDown={(e) => { if (e.key === 'Enter' && query.trim()) void find(); }} />
				</label>
				<button type="button" class="st-btn st-btn-sm" disabled={busy || !query.trim()} onClick={() => void find()}>{S.packageFind}</button>
			</div>
			<div class="wm-caption">{S.packageNameHelp}</div>
			{!checkable && <div class="wm-caption">{S.packageUnchecked}</div>}
			{stage.kind === 'finding' && <div class="wm-caption">{S.packageFinding}</div>}
			{project && request && (
				<div class="wm-row-between">
					<label class="st-field" style="flex:1 1 auto">
						<span>{S.packageVersion}</span>
						<select class="st-select" value={version} disabled={busy} onChange={(e) => { setVersion((e.target as HTMLSelectElement).value); setStage({ kind: 'found', project, request }); }}>
							{project.versions.filter((v) => !v.yanked).map((v) => (
								<option key={v.version} value={v.version}>
									{v.version === project.latest ? S.packageVersionLatest(v.version) : v.pure ? v.version : hasStandIn ? S.packageVersionStandIn(v.version) : v.sdistOnly ? S.packageVersionSource(v.version) : S.packageVersionCompiled(v.version)}
								</option>
							))}
						</select>
					</label>
					<button type="button" class="st-btn st-btn-sm st-btn-primary" disabled={busy} onClick={() => void check(project, request)}>{S.packageCheck}</button>
				</div>
			)}
			{project?.summary && <div class="wm-caption">{project.summary}</div>}
			{stage.kind === 'checking' && <div class="wm-caption">{S.packageChecking}</div>}
			{stage.kind === 'failed' && <div class="wm-error-text">{stage.message}</div>}
			{stage.kind === 'checked' && (
				<div style="display:flex;flex-direction:column;gap:8px">
					{stage.prepared.map((p) => <PlannedRow key={p.planned.key} p={p} />)}
					{stage.plan.satisfied.length > 0 && <div class="wm-caption">{S.packageAlready(stage.plan.satisfied.join(', '))}</div>}
				</div>
			)}
			{collision && <div class="wm-error-text">{collision}</div>}
			<h3 class="wm-heading">{S.packageInstalled}</h3>
			{installed.length === 0 && <div class="wm-caption">{S.packageNone}</div>}
			{installed.map(([key, e]) => {
				const by = neededBy(lock, key);
				return (
					<div key={key} class="wm-pkg">
						<div style="flex:1 1 auto;min-width:0">
							<div><b>{key}</b> {e.version} <span class={`wm-verdict wm-verdict-${e.verdict}`}>{VERDICT_TEXT[e.verdict]}</span></div>
							<div class="wm-caption">{e.license ? S.packageLicense(e.license) : S.packageNoLicense}{by.length ? ` · ${S.packageNeededBy(by.join(', '))}` : ''}</div>
						</div>
						<TextButton onClick={() => remove(key)}>{S.packageRemove}</TextButton>
					</div>
				);
			})}
		</Dialog>
	);
}

function PlannedRow({ p }: { p: PreparedPackage }) {
	const size = Object.values(p.contents.modules).reduce((n, b) => n + b.byteLength, 0);
	const a = p.analysis;
	const notes = [
		p.planned.requiredBy ? S.packageDependency(p.planned.requiredBy) : null,
		p.planned.replaces ? S.packageReplaces(p.planned.replaces) : null,
		p.contents.license ?? p.planned.release.license ? S.packageLicense((p.contents.license ?? p.planned.release.license)!) : S.packageNoLicense,
		S.packageFiles(Object.keys(p.contents.modules).length, fmtBytes(size)),
	].filter(Boolean);
	return (
		<div class="wm-pkg wm-pkg-planned">
			<div style="flex:1 1 auto;min-width:0">
				<div><b>{p.planned.name}</b> {p.planned.version}{a && <> <span class={`wm-verdict wm-verdict-${a.verdict}`}>{VERDICT_TEXT[a.verdict]}</span></>}</div>
				<div class="wm-caption">{notes.join(' · ')}</div>
				{a && a.imports.missing.length > 0 && <div class="wm-caption">{S.packageMissing(a.imports.missing.join(', '))}</div>}
				{(p.patched ?? []).map((n, i) => <div key={`patch${i}`} class="wm-caption">{n}</div>)}
				{a && a.problems.length > 0 && (
					<ul class="wm-pkg-problems">
						{a.problems.slice(0, 20).map((x, i) => (
							<li key={i} class={x.severity === 'error' ? 'wm-error-text' : 'wm-caption'}>
								<span class="wm-line-chip">{S.packageProblemAt(x.file, x.line)}</span> {x.message}
							</li>
						))}
						{a.problems.length > 20 && <li class="wm-caption">…</li>}
					</ul>
				)}
			</div>
		</div>
	);
}

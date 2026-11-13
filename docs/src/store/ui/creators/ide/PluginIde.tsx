/**
 * The plugin editor (PluginIdeScreen.kt and its panes, dialogs and history):
 * the drafts list, and one draft open for editing with the code full height and
 * under it a panel that shows the plugin running and what it printed.
 */
import { signal, useSignal } from '@preact/signals';
import { useCallback, useEffect, useMemo, useRef, useState } from 'preact/hooks';
import type { ComponentChildren } from 'preact';
import { readPlugin } from '../../../lib/payloads';
import { writeZip } from '../../../lib/zip';
import { CopyButton } from '../../common';
import { fmtBytes } from '../../../lib/net';
import { sha256Hex, slugify } from '../../../lib/util';
import { saveBytes } from '../shared';
import { PluginPreviewSession, renderOnce, type ConsoleEntry, type PluginEvent, type PluginTargets, type PluginWidget, type PreviewState, type RenderedUi, type Usage, PreviewStorage, Dates, targetsEmpty } from '../../../lib/plugin-runtime';
import * as Py from '../../../lib/python';
import type { PyCompletionItem, PyCompletions, PyDiagnostic, PyHost, PyOutlineItem, RenameProblem } from '../../../lib/python';
import { E, CodeSurface, SHORTCUT, type CodeDecorations, type CodeEditorController, type ScreenCommand, type Severity, NO_DECORATIONS } from './CodeSurface';
import { CodeFindBar, initialFind, type CodeFindState } from './CodeFindBar';
import { CodeAccessoryRow, PYTHON_ACCESSORY_KEYS, arrangeKeys } from './CodeAccessoryRow';
import { ID_PATTERN, MAX_NAME, MAX_SCRIPT_BYTES, PERMISSIONS, REMINDERS_PERMISSION, STORAGE_PERMISSION, blankManifest, editorPrefs, fileNameOf, keyPrefs, sanitise, sanitised, takenIds, uniquePluginId, workspace, type PluginDraft, type PluginManifest, type PluginSnapshot, type SnapshotReason } from './ide-store';
import { TEMPLATES, type PluginTemplate } from './templates';
import { S, relativeTime } from './strings';
import { V5Widget } from './V5Widgets';
import './ide.css';

const AUTOSAVE_MS = 400;
const PERIODIC_VERSION_MS = 5 * 60 * 1000;
const AUTO_RUN_MS = 600;
const FIND_DELAY_MS = 120;
const DIAGNOSTICS_MS = 250;
const DEFAULT_TEXT_SIZE = 14;
const MIN_TEXT_SIZE = 11;
const MAX_TEXT_SIZE = 22;
const MAX_BUDGET_HISTORY = 20;
const WARN_SHARE = 0.8;
const PANEL_FRACTION = 0.42;

type IdePanel = 'CLOSED' | 'PREVIEW' | 'CONSOLE' | 'PROBLEMS' | 'OUTLINE' | 'EVENTS' | 'STORAGE' | 'DETAILS';

/** Wide enough for the desktop layout: the code on the left, the live preview and a docked pane on the right. */
const DESKTOP_QUERY = '(min-width: 1024px)';

function useMedia(query: string): boolean {
	const [matches, setMatches] = useState(() => (typeof window === 'undefined' ? false : window.matchMedia(query).matches));
	useEffect(() => {
		const m = window.matchMedia(query);
		const on = () => setMatches(m.matches);
		on();
		m.addEventListener('change', on);
		return () => m.removeEventListener('change', on);
	}, [query]);
	return matches;
}

const numbers = new Intl.NumberFormat();

/** Whether a physical keyboard is likely: a fine pointer. The code key row steps aside, and menus show keys. */
function hardwareKeyboardAttached(): boolean {
	if (typeof window === 'undefined') return true;
	return !window.matchMedia('(pointer: coarse)').matches;
}

/** What the importer would refuse the manifest for, in field order (PluginManifestCodec.problems). */
export function manifestProblems(m: PluginManifest): string[] {
	const out: string[] = [];
	if (!ID_PATTERN.test(sanitise(m.id, 'ID'))) out.push(S.rejectBadId);
	const name = sanitise(m.name, 'NAME');
	if (!name) out.push(S.rejectNoName);
	if (!sanitise(m.pluginVersion, 'VERSION')) out.push(S.rejectNoVersion(name));
	return out;
}

const utf8 = new TextEncoder();

/** The .wmplugin file for a draft, as PluginFile.write makes it. */
function pluginFile(manifest: PluginManifest, script: string): Uint8Array {
	const m = sanitised(manifest);
	return writeZip([{ name: 'plugin.json', data: JSON.stringify(m, null, 2) + '\n' }, { name: m.entry, data: script }]);
}

// ---------------------------------------------------------------------------
// The entry: drafts list or one draft
// ---------------------------------------------------------------------------

const OPEN_KEY = 'wm.ide.open';

export function PluginIde() {
	const [open, setOpen] = useState<string | null>(() => {
		try {
			return sessionStorage.getItem(OPEN_KEY);
		} catch {
			return null;
		}
	});
	const navigate = (draftId: string | null) => {
		try {
			if (draftId) sessionStorage.setItem(OPEN_KEY, draftId);
			else sessionStorage.removeItem(OPEN_KEY);
		} catch {
			/* fine */
		}
		setOpen(draftId);
	};
	if (open) return <IdeScreen key={open} draftId={open} onBack={() => navigate(null)} />;
	return <ProjectsScreen onOpen={navigate} />;
}

// ---------------------------------------------------------------------------
// The drafts list
// ---------------------------------------------------------------------------

function ProjectsScreen({ onOpen }: { onOpen: (draftId: string) => void }) {
	const [revision, setRevision] = useState(0);
	const drafts = useMemo(() => workspace.drafts().map((d) => [d, workspace.manifest(d.draftId)] as const), [revision]);
	const [creating, setCreating] = useState<PluginTemplate | null>(null);
	const [deleting, setDeleting] = useState<PluginDraft | null>(null);
	const [toast, showToast] = useToast();
	const file = useRef<HTMLInputElement>(null);
	const now = Date.now();

	const create = (template: PluginTemplate, name: string) => {
		setCreating(null);
		const manifest: PluginManifest = {
			...blankManifest(), id: uniquePluginId(name, takenIds()), name: sanitise(name, 'NAME'), pluginVersion: '0.1.0', permissions: [...template.permissions],
			...(template.apiVersion ? { apiVersion: template.apiVersion } : {}),
			...(template.hosts ? { hosts: [...template.hosts] } : {}),
			...(template.settings ? { settings: template.settings.map((s) => ({ ...s })) } : {}),
		};
		const draft = workspace.create(manifest, template.script, `template:${template.id.toLowerCase()}`);
		if (draft) onOpen(draft.draftId);
		else showToast(S.newError);
	};

	const importFile = async (f: File) => {
		try {
			const read = readPlugin(new Uint8Array(await f.arrayBuffer()));
			const refusal = read.problems.find((p) => /"id"|"name"|"pluginVersion"|apiVersion|Permission|"language"|no longer runs/.test(p));
			if (refusal) return showToast(refusal);
			const script = read.files.find((x) => x.name === read.manifest.entry)?.text;
			if (script === null || script === undefined) return showToast(S.importNotAPlugin);
			const draft = workspace.create({ ...blankManifest(), ...read.manifest }, script, 'import');
			if (!draft) return showToast(S.newError);
			workspace.snapshot(draft.draftId, 'import');
			onOpen(draft.draftId);
		} catch (e) {
			showToast(/plugin\.json|zip/i.test(String((e as Error).message)) ? S.importNotAPlugin : S.importFailed);
		}
	};

	return (
		<div class="wm-projects">
			<div style="margin-bottom:0.6rem">
				<span class="st-kicker">Plugin</span>
				<h1 style="font-size:1.5rem;font-weight:800">{S.projectsTitle}</h1>
			</div>
			<div class="wm-group">
				<RowItem title={S.newTitle} subtitle={S.newSubtitle} onClick={() => setCreating(TEMPLATES[0]!)} />
			</div>
			<div class="wm-group">
				<h2 class="wm-group-title">{S.templatesTitle}</h2>
				<div class="wm-templates">
					{TEMPLATES.filter((t) => t.id !== 'BLANK').map((t) => (
						<TemplateCard key={t.id} template={t} onClick={() => setCreating(t)} />
					))}
				</div>
				<RowItem title={S.importTitle} subtitle={S.importSubtitle} onClick={() => file.current?.click()} />
				<input ref={file} type="file" accept=".wmplugin,.zip,application/zip" hidden onChange={(e) => { const f = (e.target as HTMLInputElement).files?.[0]; if (f) void importFile(f); (e.target as HTMLInputElement).value = ''; }} />
			</div>
			<div class="wm-group">
				<h2 class="wm-group-title">{S.draftsTitle}</h2>
				{drafts.length === 0 && <div class="wm-caption" style="padding:0.4rem 0.2rem">{S.draftsEmpty}</div>}
				{drafts.map(([draft, manifest]) => {
					const edited = relativeTime(draft.updatedAt, now);
					return (
						<RowItem
							key={draft.draftId}
							title={manifest?.name?.trim() || S.draftUntitled}
							subtitle={draft.publishedVersion ? S.draftPublished(draft.publishedVersion, edited) : S.draftEdited(edited)}
							onClick={() => onOpen(draft.draftId)}
							trailing={
								<>
									<IconButton label={S.duplicateDraftDesc} onClick={(e) => { e.stopPropagation(); if (!workspace.duplicate(draft.draftId)) showToast(S.newError); setRevision((n) => n + 1); }}><IconCopySvg /></IconButton>
									<IconButton label={S.deleteDraftDesc} onClick={(e) => { e.stopPropagation(); setDeleting(draft); }}><IconDeleteSvg /></IconButton>
								</>
							}
						/>
					);
				})}
			</div>
			{creating && <NewPluginDialog initialName={creating.id === 'BLANK' ? null : creating.name} onDismiss={() => setCreating(null)} onCreate={(name) => create(creating, name)} />}
			{deleting && (
				<Dialog title={S.deleteDraftTitle} onDismiss={() => setDeleting(null)} actions={<><TextButton onClick={() => setDeleting(null)}>{S.cancel}</TextButton><TextButton onClick={() => { workspace.delete(deleting.draftId); setDeleting(null); setRevision((n) => n + 1); }}>{S.delete}</TextButton></>}>
					<p>{S.deleteDraftBody}</p>
				</Dialog>
			)}
			{toast}
		</div>
	);
}

function RowItem({ title, subtitle, onClick, trailing }: { title: string; subtitle: string; onClick: () => void; trailing?: ComponentChildren }) {
	return (
		<div class="wm-rowitem" role="button" tabIndex={0} onClick={onClick} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick(); } }}>
			<div class="wm-rowitem-text">
				<div class="wm-rowitem-title">{title}</div>
				<div class="wm-rowitem-subtitle">{subtitle}</div>
			</div>
			{trailing ?? <IconForwardSvg />}
		</div>
	);
}

/** A template: its name and one line about it, over the panel it draws before anyone touches it. */
function TemplateCard({ template, onClick }: { template: PluginTemplate; onClick: () => void }) {
	const [drawn, setDrawn] = useState<RenderedUi | null>(null);
	useEffect(() => {
		let live = true;
		const idle = (cb: () => void) => ('requestIdleCallback' in window ? (window as Window & { requestIdleCallback: (cb: () => void) => number }).requestIdleCallback(cb) : setTimeout(cb, 0));
		idle(() => {
			if (!live) return;
			// A thumbnail never reaches the network: no bridge, so the Weather card draws its empty state.
			setDrawn(renderOnce({ id: `template.${template.id.toLowerCase()}`, name: template.name, version: '0.1.0', storage: template.permissions.includes(STORAGE_PERMISSION), reminders: template.permissions.includes(REMINDERS_PERMISSION), apiVersion: template.apiVersion, permissions: template.permissions }, template.script));
		});
		return () => {
			live = false;
		};
	}, [template]);
	return (
		<div>
			<RowItem title={template.name} subtitle={template.description} onClick={onClick} />
			{drawn && drawn.root.length > 0 && (
				<div class="wm-thumb" aria-hidden="true" onClick={onClick}>
					<div class="wm-w-still">
						<PluginPanel widgets={drawn.root} inputs={{}} onEvent={() => {}} onInsert={() => {}} onCopy={() => {}} still />
					</div>
				</div>
			)}
		</div>
	);
}

function NewPluginDialog({ initialName, onDismiss, onCreate }: { initialName: string | null; onDismiss: () => void; onCreate: (name: string) => void }) {
	const [name, setName] = useState(initialName ?? S.newDefaultName);
	const submit = () => onCreate(name.trim() || S.newDefaultName);
	return (
		<Dialog title={S.newTitle} onDismiss={onDismiss} actions={<><TextButton onClick={onDismiss}>{S.cancel}</TextButton><TextButton onClick={submit}>{S.newAction}</TextButton></>}>
			<label class="st-field">
				<span>{S.newNameHint}</span>
				<input class="st-input" value={name} maxLength={MAX_NAME} autoFocus onInput={(e) => setName((e.target as HTMLInputElement).value.substring(0, MAX_NAME))} onKeyDown={(e) => { if (e.key === 'Enter') submit(); }} />
			</label>
		</Dialog>
	);
}

// ---------------------------------------------------------------------------
// The code screen
// ---------------------------------------------------------------------------

interface Inspection {
	diagnostics: PyDiagnostic[];
	outline: PyOutlineItem[];
}

interface RenamePlan {
	oldName: string;
	spans: Py.Range[];
	snapshot: string;
	caret: number;
}

function IdeScreen({ draftId, onBack }: { draftId: string; onBack: () => void }) {
	const draft = useMemo(() => workspace.draft(draftId), [draftId]);
	if (!draft) {
		return (
			<div>
				<div class="wm-ide-bar"><IconButton label={S.back} onClick={onBack}><IconBackSvg /></IconButton></div>
				<div class="wm-pane-empty">{S.missing}</div>
			</div>
		);
	}
	return <IdeBody draftId={draftId} onBack={onBack} />;
}

function IdeBody({ draftId, onBack }: { draftId: string; onBack: () => void }) {
	const [manifest, setManifest] = useState<PluginManifest>(() => workspace.manifest(draftId) ?? blankManifest());
	const savedText = useRef(workspace.script(draftId) ?? '');
	const controller = useSignal<CodeEditorController | null>(null);
	const preview = useMemo(() => new PluginPreviewSession((id) => workspace.storageKey(id)), []);
	const previewState = preview.state.value;
	const prefs = useMemo(() => editorPrefs.read(), []);
	const [textSize, setTextSize] = useState(prefs.textSize);
	const [wrap, setWrap] = useState(prefs.wrap);
	const [autoRun, setAutoRun] = useState(prefs.autoRun);
	useEffect(() => editorPrefs.write({ textSize, wrap, autoRun }), [textSize, wrap, autoRun]);
	const desktop = useMedia(DESKTOP_QUERY);
	const [panel, setPanel] = useState<IdePanel>(() => (typeof window !== 'undefined' && window.matchMedia(DESKTOP_QUERY).matches ? 'CONSOLE' : 'CLOSED'));
	const [menuOpen, setMenuOpen] = useState(false);
	const [detailsOpen, setDetailsOpen] = useState(false);
	const [keysOpen, setKeysOpen] = useState(false);
	const [keys, setKeys] = useState(() => keyPrefs.read());
	const [findOpen, setFindOpen] = useState(false);
	const [find, setFind] = useState<CodeFindState>(initialFind);
	const [findFocusRequests, setFindFocusRequests] = useState(0);
	const [lineOpen, setLineOpen] = useState(false);
	const [renamePlan, setRenamePlan] = useState<RenamePlan | null>(null);
	const [versions, setVersions] = useState<PluginSnapshot[] | null>(null);
	const [comparing, setComparing] = useState<PluginSnapshot | null>(null);
	const [comparison, setComparison] = useState<E.DiffRow[] | null>(null);
	const [toast, showToast] = useToast();
	const lastRun = useRef<string | null>(null);
	const [renderHistory, setRenderHistory] = useState<number[]>([]);
	const [inspection, setInspection] = useState<Inspection>({ diagnostics: [], outline: [] });
	const [matches, setMatches] = useState<E.Range[]>([]);
	const [docOpen, setDocOpen] = useState<string | null>(null);
	const keyboard = useMemo(hardwareKeyboardAttached, []);
	const suggestionBar = useMemo(() => ({ shown: signal(null as PyCompletions | null), choose: (item: PyCompletionItem) => controller.value?.choose(item) }), [controller]);
	const storage = manifest.permissions.includes(STORAGE_PERMISSION);
	const reminders = manifest.permissions.includes(REMINDERS_PERMISSION);
	const host = useMemo<PyHost>(() => ({ storage, reminders, panel: true, selection: false, actions: [] }), [storage, reminders]);

	const c = controller.value;
	// Read for its subscription: the screen re-renders on every change of the text or the caret.
	void c?.revision.value;
	const text = c?.text ?? savedText.current;
	const caret = c ? Math.min(Math.max(c.selection.end, 0), text.length) : 0;
	const lineStarts = useMemo(() => E.lineStartOffsets(text), [text]);
	const positionLine = E.lineOf(lineStarts, caret);
	const positionColumn = caret - lineStarts[positionLine]!;
	const apiHere = useMemo(() => (c ? Py.apiAt(text, caret) : null), [c, text, caret]);

	// ---- saving, versions, running ----------------------------------------------------
	const save = useCallback((current: string) => {
		if (current === savedText.current) return true;
		if (!workspace.writeScript(draftId, current)) return false;
		savedText.current = current;
		return true;
	}, [draftId]);
	useEffect(() => {
		const t = setTimeout(() => save(text), AUTOSAVE_MS);
		return () => clearTimeout(t);
	}, [text, save]);
	useEffect(() => {
		const t = setInterval(() => {
			const current = controller.value?.text;
			if (current !== undefined && save(current)) workspace.snapshot(draftId, 'periodic');
		}, PERIODIC_VERSION_MS);
		return () => clearInterval(t);
	}, [draftId, save, controller]);
	const identity = useCallback(() => ({
		id: sanitise(manifest.id, 'ID') || draftId, name: manifest.name, version: manifest.pluginVersion, storage, reminders,
		apiVersion: manifest.apiVersion, permissions: manifest.permissions, hosts: manifest.hosts ?? [], settings: settingDefaults(manifest),
	}), [manifest, draftId, storage, reminders]);
	const runPlugin = useCallback(() => {
		const current = controller.value?.text ?? savedText.current;
		lastRun.current = current;
		preview.run(draftId, current, identity());
		setPanel((p) => (p === 'CLOSED' ? 'PREVIEW' : p));
	}, [controller, preview, draftId, identity]);
	useEffect(() => {
		if (!autoRun) return;
		const t = setTimeout(() => {
			if (autoRun && Py.check(text) === null && text !== lastRun.current) {
				lastRun.current = text;
				preview.run(draftId, text, identity());
			}
		}, AUTO_RUN_MS);
		return () => clearTimeout(t);
	}, [text, autoRun, preview, draftId, identity]);
	const renderUsage = previewState.usage.RENDER;
	useEffect(() => {
		if (!renderUsage || renderUsage.running) return;
		setRenderHistory((h) => [...h, renderUsage.steps].slice(-MAX_BUDGET_HISTORY));
	}, [renderUsage]);
	useEffect(() => () => {
		const current = controller.value?.text;
		if (current !== undefined) save(current);
		preview.shutdown();
	}, [preview, save, controller]);

	// ---- the checks --------------------------------------------------------------------
	useEffect(() => {
		const t = setTimeout(() => {
			const found = Py.analyse(text, host);
			// A file that stops parsing keeps the outline it had.
			setInspection((previous) => ({ diagnostics: found.diagnostics, outline: found.outline.length || Py.check(text) === null ? found.outline : previous.outline }));
		}, DIAGNOSTICS_MS);
		return () => clearTimeout(t);
	}, [text, host]);
	useEffect(() => {
		if (!findOpen || !find.query) {
			setMatches([]);
			return;
		}
		const t = setTimeout(() => setMatches(E.findMatches(text, find.query, find.options)), FIND_DELAY_MS);
		return () => clearTimeout(t);
	}, [text, find.query, find.options, findOpen]);
	const failureLine = previewState.failure?.line ? previewState.failure.line - 1 : null;
	const diagnostics = inspection.diagnostics;
	useEffect(() => {
		if (!c) return;
		const marks = new Map<number, Severity>();
		const rank = { ERROR: 0, WARNING: 1, INFO: 2 };
		for (const d of diagnostics) {
			const line = E.lineOf(lineStarts, Math.min(Math.max(d.start, 0), lineStarts[lineStarts.length - 1]!));
			const sev = d.severity;
			const held = marks.get(line);
			if (held === undefined || rank[sev] < rank[held]) marks.set(line, sev);
		}
		if (failureLine !== null && failureLine >= 0 && failureLine < lineStarts.length) marks.set(failureLine, 'ERROR');
		const decorations: CodeDecorations = marks.size === 0 && matches.length === 0 ? NO_DECORATIONS : { matches, activeMatch: matches.length ? Math.min(Math.max(find.active, 0), matches.length - 1) : -1, squiggles: diagnostics.map((d) => ({ start: d.start, end: d.end, severity: d.severity })), gutterMarks: marks };
		c.setDecorations(decorations);
	}, [c, diagnostics, lineStarts, failureLine, matches, find.active]);

	// ---- commands ---------------------------------------------------------------------------
	const openFind = (replace: boolean) => {
		const ed = controller.value;
		if (!ed) return;
		const sel = ed.selection;
		const selected = ed.text.substring(E.rangeMin(sel), E.rangeMax(sel));
		const next = { ...find };
		if (selected && !selected.includes('\n')) next.query = selected;
		if (replace) next.replacing = true;
		next.active = E.firstMatchFrom(E.findMatches(ed.text, next.query, next.options), E.rangeMin(sel));
		setFind(next);
		setFindOpen(true);
		setFindFocusRequests((n) => n + 1);
	};
	const goToDefinition = () => {
		const ed = controller.value;
		if (!ed) return;
		const span = Py.definition(ed.text, E.rangeMin(ed.selection));
		if (span) ed.select({ start: span.start, end: span.end });
		else showToast(S.noDefinition);
	};
	const startRename = () => {
		const ed = controller.value;
		if (!ed) return;
		const source = ed.text;
		const at = E.rangeMin(ed.selection);
		const spans = Py.rename(source, at, '')?.spans;
		const first = spans?.[0];
		if (!spans || !first) return showToast(S.noRename);
		setRenamePlan({ oldName: source.substring(first.start, first.end), spans, snapshot: source, caret: at });
	};
	const format = () => {
		const ed = controller.value;
		if (ed) ed.replace(Py.format(ed.text));
	};
	const stepFind = (delta: number) => {
		const ed = controller.value;
		if (!ed || !matches.length) return;
		const active = (((find.active + delta) % matches.length) + matches.length) % matches.length;
		setFind({ ...find, active });
		ed.select(matches[active]!);
	};
	const onCommand = (command: ScreenCommand): boolean => {
		const ed = controller.value;
		switch (command) {
			case 'FIND': openFind(false); break;
			case 'REPLACE': openFind(true); break;
			case 'FIND_NEXT':
			case 'FIND_PREVIOUS':
				if (!findOpen) openFind(false);
				else stepFind(command === 'FIND_NEXT' ? 1 : -1);
				break;
			case 'ESCAPE': setFindOpen(false); break;
			case 'GO_TO_LINE': setLineOpen(true); break;
			case 'GO_TO_DEFINITION': goToDefinition(); break;
			case 'RENAME': startRename(); break;
			case 'GO_TO_SYMBOL': setPanel('OUTLINE'); break;
			case 'FORMAT': format(); break;
			case 'TOGGLE_WRAP': setWrap((w) => !w); break;
			case 'ZOOM_IN': setTextSize((s) => Math.min(s + 1, MAX_TEXT_SIZE)); break;
			case 'ZOOM_OUT': setTextSize((s) => Math.max(s - 1, MIN_TEXT_SIZE)); break;
			case 'ZOOM_RESET': setTextSize(DEFAULT_TEXT_SIZE); break;
			case 'SAVE':
				if (ed) save(ed.text);
				showToast(S.saved);
				break;
			case 'RUN': runPlugin(); break;
			case 'STOP': preview.stop(); break;
			case 'NEXT_PROBLEM':
			case 'PREVIOUS_PROBLEM': {
				if (!ed) break;
				const next = E.nextProblem(diagnostics.map((d) => ({ start: d.start, end: d.end })), E.rangeMin(ed.selection), command === 'NEXT_PROBLEM');
				if (next) ed.select(next);
				break;
			}
			case 'SHOW_PROBLEMS': setPanel((p) => (p === 'PROBLEMS' ? 'CLOSED' : 'PROBLEMS')); break;
			case 'SHOW_CONSOLE': setPanel((p) => (p === 'CONSOLE' ? 'CLOSED' : 'CONSOLE')); break;
			case 'SHOW_COMMANDS': setMenuOpen(true); break;
			default: return false;
		}
		return true;
	};
	const publish = () => {
		const ed = controller.value;
		if (!ed) return;
		save(ed.text);
		const problem = manifestProblems(manifest)[0];
		if (problem) return showToast(problem);
		if (utf8.encode(ed.text).byteLength > MAX_SCRIPT_BYTES) return showToast(S.rejectScriptTooLarge(manifest.name, MAX_SCRIPT_BYTES / 1024));
		workspace.snapshot(draftId, 'publish');
		try {
			saveBytes(fileNameOf(manifest), pluginFile(manifest, ed.text));
			workspace.markPublished(draftId, sanitise(manifest.pluginVersion, 'VERSION'));
			showToast(S.exportSaved);
		} catch {
			showToast(S.exportFailed);
		}
	};
	const exportFile = () => {
		const ed = controller.value;
		if (!ed) return;
		if (manifestProblems(manifest).length) return showToast(S.exportProblems);
		try {
			saveBytes(fileNameOf(manifest), pluginFile(manifest, ed.text));
			showToast(S.exportSaved);
		} catch {
			showToast(S.exportFailed);
		}
	};
	const saveVersion = () => {
		const ed = controller.value;
		if (!ed) return;
		save(ed.text);
		showToast(workspace.snapshot(draftId, 'manual') ? S.versionSaved : S.versionSame);
	};
	const openVersions = () => {
		const ed = controller.value;
		if (ed) save(ed.text);
		setVersions(workspace.snapshots(draftId));
	};
	const compare = (version: PluginSnapshot) => {
		setComparing(version);
		setComparison(null);
		const body = workspace.snapshotBody(draftId, version.snapshotId);
		const current = controller.value?.text ?? '';
		if (body === null) {
			setComparing(null);
			showToast(S.restoreFailed);
			return;
		}
		setTimeout(() => setComparison(E.collapseDiff(E.lineDiff(current, body))), 0);
	};
	const restore = (version: PluginSnapshot) => {
		setComparing(null);
		setVersions(null);
		const ed = controller.value;
		if (!ed) return;
		if (!save(ed.text) || !workspace.restore(draftId, version.snapshotId)) return showToast(S.restoreFailed);
		const body = workspace.script(draftId);
		if (body === null) return showToast(S.restoreFailed);
		savedText.current = body;
		ed.replace(body);
		showToast(S.restored);
	};
	const replayable = useMemo(() => preview.recordedEvents(), [preview, previewState.runs]);
	const running = previewState.status === 'RUNNING';
	const title = manifest.name.trim() || S.draftUntitled;
	const menuItem = (label: string, run: () => void, command?: string, disabled = false, icon?: ComponentChildren) => (
		<button type="button" class="wm-menu-item" role="menuitem" disabled={disabled} onClick={() => { setMenuOpen(false); run(); }}>
			{icon ?? <span style="width:18px" />}
			<span class="wm-menu-text">{label}</span>
			{keyboard && command && SHORTCUT[command] && <kbd>{SHORTCUT[command]}</kbd>}
		</button>
	);
	const surfaceOptions = useMemo(() => ({ onCommand, onGutterPress: (line: number) => { setPanel('PROBLEMS'); controller.value?.moveTo(E.offsetOfLine(controller.value.text, line)); }, host: () => host, suggestionBar, completions: true }), [host, suggestionBar]);
	const arrangedKeys = useMemo(() => arrangeKeys(PYTHON_ACCESSORY_KEYS, keys.order, new Set(keys.hidden)), [keys]);

	const previewPane = <PreviewPane preview={preview} state={previewState} autoRun={autoRun} onAutoRun={setAutoRun} history={renderHistory} onCopied={() => showToast(S.copied)} />;
	const pane = (
		<>
			{panel === 'PREVIEW' && previewPane}
			{panel === 'CONSOLE' && <ConsolePane preview={preview} revision={previewState.consoleRevision} onJump={(line) => controller.value?.moveTo(E.offsetOfLine(controller.value.text, line - 1))} />}
			{panel === 'PROBLEMS' && <ProblemsPane diagnostics={diagnostics} lineStarts={lineStarts} onJump={(r) => controller.value?.select(r)} />}
			{panel === 'OUTLINE' && <OutlinePane outline={inspection.outline} lineStarts={lineStarts} onJump={(r) => controller.value?.select(r)} />}
			{panel === 'EVENTS' && <EventsPane targets={previewState.targets} earlier={replayable} onSend={(e) => preview.send(e)} />}
			{panel === 'STORAGE' && <StoragePane storage={preview.storageOf(draftId)} declared={storage} busy={previewState.busy} />}
			{panel === 'DETAILS' && <DetailsPane manifest={manifest} script={text} onManifest={(m) => { const withFormat = sanitised(m); setManifest(withFormat); workspace.writeManifest(draftId, withFormat); }} onExport={exportFile} />}
		</>
	);
	return (
		<div class={`wm-ide ${desktop ? 'wm-ide-desktop' : ''}`} onClickCapture={() => menuOpen && setMenuOpen(false)}>
			<div class="wm-ide-bar">
				<IconButton label={S.back} onClick={onBack}><IconBackSvg /></IconButton>
				<div class="wm-ide-title" title={title}>{title}</div>
				<IconButton label={S.undoDesc} disabled={!(c?.canUndo.value ?? false)} onClick={() => c?.undo()}><IconUndoSvg /></IconButton>
				<IconButton label={S.redoDesc} disabled={!(c?.canRedo.value ?? false)} onClick={() => c?.redo()}><IconRedoSvg /></IconButton>
				<IconButton label={running ? S.stopDesc : S.runDesc} onClick={() => (running ? preview.stop() : runPlugin())}>{running ? <IconStopSvg /> : <IconPlaySvg />}</IconButton>
				<IconButton label={S.publishDesc} onClick={publish}><IconPublishSvg /></IconButton>
				<div class="wm-menu-anchor">
					<IconButton label={S.moreDesc} expanded={menuOpen} onClick={(e) => { e.stopPropagation(); setMenuOpen((o) => !o); }}><IconMoreSvg /></IconButton>
					{menuOpen && (
						<div class="wm-menu" role="menu" onClick={(e) => e.stopPropagation()}>
							{menuItem(S.findAction, () => openFind(false), 'FIND', false, <IconFindSvg />)}
							{menuItem(S.goToLineAction, () => setLineOpen(true), 'GO_TO_LINE', false, <IconListSvg />)}
							{menuItem(S.definitionAction, goToDefinition, 'GO_TO_DEFINITION', false, <IconBracesSvg />)}
							{menuItem(S.renameAction, startRename, 'RENAME', false, <IconRenameSvg />)}
							{menuItem(S.exportAction, exportFile, undefined, false, <IconSaveSvg />)}
							{menuItem(S.versionsAction, openVersions, undefined, false, <IconHistorySvg />)}
							{menuItem(S.textLarger, () => setTextSize((s) => Math.min(s + 1, MAX_TEXT_SIZE)), 'ZOOM_IN', textSize >= MAX_TEXT_SIZE, <IconTextIncreaseSvg />)}
							{menuItem(S.textSmaller, () => setTextSize((s) => Math.max(s - 1, MIN_TEXT_SIZE)), 'ZOOM_OUT', textSize <= MIN_TEXT_SIZE, <IconTextDecreaseSvg />)}
							{menuItem(wrap ? S.wrapOffDesc : S.wrapOnDesc, () => setWrap((w) => !w), 'TOGGLE_WRAP', false, <IconWrapSvg />)}
							{menuItem(S.foldAll, () => c?.foldAll(), 'FOLD_ALL', false, <IconFoldSvg />)}
							{menuItem(S.unfoldAll, () => c?.unfoldAll(), 'UNFOLD_ALL', !(c && c.foldStarts.length > 0), <IconUnfoldSvg />)}
							{menuItem(S.codeKeysAction, () => setKeysOpen(true), undefined, false, <IconKeyboardSvg />)}
							{menuItem(S.formatAction, format, 'FORMAT', false, <IconWandSvg />)}
							{menuItem(S.detailsAction, () => setDetailsOpen(true), undefined, false, <IconTuneSvg />)}
							{menuItem(S.versionAction, saveVersion, undefined, false, <IconBookmarkSvg />)}
							{menuItem(S.copy, () => { if (c) void navigator.clipboard?.writeText(c.text).catch(() => {}); }, undefined, false, <IconCopySvg />)}
							{menuItem(S.paste, () => { navigator.clipboard?.readText().then((t) => { if (t && t.trim()) c?.replace(t); }).catch(() => {}); }, undefined, false, <IconPasteSvg />)}
						</div>
					)}
				</div>
			</div>
			<div class="wm-ide-columns">
			<div class="wm-ide-body">
				{findOpen && (
					<CodeFindBar
						find={find}
						setFind={setFind}
						matches={matches}
						onStep={stepFind}
						onReplace={() => {
							const ed = controller.value;
							if (!ed) return;
							const current = E.findMatches(ed.text, find.query, find.options);
							const match = current[Math.min(Math.max(find.active, 0), Math.max(0, current.length - 1))];
							if (match) ed.applyEdit(E.replaceMatch(ed.text, match, find.replacement, find.query, find.options));
						}}
						onReplaceAll={() => {
							const ed = controller.value;
							if (!ed) return;
							const edit = E.replaceAllMatches(ed.text, E.findMatches(ed.text, find.query, find.options), find.replacement, find.query, find.options);
							if (edit) ed.applyEdit(edit);
						}}
						onClose={() => setFindOpen(false)}
						onEscape={() => {
							setFindOpen(false);
							controller.value?.focus();
						}}
						onCommand={onCommand}
						focusRequests={findFocusRequests}
					/>
				)}
				<CodeSurface controller={controller} initial={savedText.current} options={surfaceOptions} wrap={wrap} fontSize={textSize} />
				{apiHere && (
					<div class={`wm-doc ${docOpen === apiHere.path ? 'wm-doc-open' : ''}`} role="button" aria-label={docOpen === apiHere.path ? S.docLess : S.docMore} onClick={() => setDocOpen(docOpen === apiHere.path ? null : apiHere.path)}>
						<div class="wm-doc-signature">{apiHere.signature ? (apiHere.parent ? `${apiHere.parent}.${apiHere.signature}` : apiHere.signature) : apiHere.path}</div>
						<div class="wm-doc-body">{apiHere.doc}</div>
					</div>
				)}
				<div class="wm-chipbar">
					<div class="wm-chipbar-scroll">
						{(desktop ? (['CONSOLE', 'PROBLEMS', 'OUTLINE', 'EVENTS', 'STORAGE', 'DETAILS'] as const) : (['PREVIEW', 'CONSOLE', 'PROBLEMS', 'OUTLINE', 'EVENTS', 'STORAGE', 'DETAILS'] as const)).map((p) => (
							<button key={p} type="button" class={`st-chip ${panel === p ? 'st-chip-on' : ''}`} aria-pressed={panel === p} onClick={() => setPanel(panel === p ? 'CLOSED' : p)}>
								{{ PREVIEW: S.previewLabel, CONSOLE: S.consoleLabel, PROBLEMS: S.problemsLabel, OUTLINE: S.outlineLabel, EVENTS: S.eventsLabel, STORAGE: S.storageLabel, DETAILS: S.detailsLabel }[p]}
								{p === 'PROBLEMS' && diagnostics.length > 0 && <span class="wm-badge">{numbers.format(diagnostics.length)}</span>}
							</button>
						))}
					</div>
					<span class="wm-position">{S.position(positionLine + 1, positionColumn + 1)}</span>
				</div>
				{!desktop && panel !== 'CLOSED' && (
					<div class="wm-panel" style={{ height: `${PANEL_FRACTION * 100}%` }}>
						{pane}
					</div>
				)}
				{!keyboard && keys.showRow && c && (
					<CodeAccessoryRow controller={c} keys={arrangedKeys} onFind={() => setFindOpen(true)} onFormat={format} onSuggest={() => c.suggest()} suggestions={suggestionBar.shown} />
				)}
			</div>
			{desktop && (
				<aside class="wm-dock">
					<section class="wm-dock-preview">
						<h2 class="wm-dock-title">{S.previewLabel}</h2>
						{previewPane}
					</section>
					{panel !== 'CLOSED' && panel !== 'PREVIEW' && (
						<section class="wm-dock-pane">
							<h2 class="wm-dock-title">{{ CONSOLE: S.consoleLabel, PROBLEMS: S.problemsLabel, OUTLINE: S.outlineLabel, EVENTS: S.eventsLabel, STORAGE: S.storageLabel, DETAILS: S.detailsLabel }[panel]}</h2>
							{pane}
						</section>
					)}
				</aside>
			)}
			</div>

			{lineOpen && <GoToLineDialog lines={lineStarts.length} onDismiss={() => setLineOpen(false)} onGo={(line) => { setLineOpen(false); controller.value?.moveTo(E.offsetOfLine(controller.value.text, line - 1)); }} />}
			{renamePlan && (
				<RenameDialog
					plan={renamePlan}
					onDismiss={() => setRenamePlan(null)}
					onRename={(name) => {
						setRenamePlan(null);
						const ed = controller.value;
						if (ed && ed.text === renamePlan.snapshot) {
							const edit = E.renameEdit(renamePlan.snapshot, renamePlan.spans, name, renamePlan.caret);
							if (edit) ed.applyEdit(edit);
						}
					}}
				/>
			)}
			{versions && <VersionsDialog versions={versions} onOpen={compare} onDismiss={() => setVersions(null)} />}
			{comparing && <VersionDiffDialog version={comparing} rows={comparison} onRestore={() => restore(comparing)} onDismiss={() => setComparing(null)} />}
			{keysOpen && (
				<CodeKeysDialog
					showRow={keys.showRow}
					order={keys.order}
					hidden={new Set(keys.hidden)}
					onChange={(next) => {
						setKeys(next);
						keyPrefs.write(next);
					}}
					onReset={() => {
						keyPrefs.reset();
						setKeys({ showRow: true, order: [], hidden: [] });
					}}
					onDismiss={() => setKeysOpen(false)}
				/>
			)}
			{detailsOpen && (
				<PluginDetailsDialog
					manifest={manifest}
					onDismiss={() => setDetailsOpen(false)}
					onSave={(updated) => {
						setDetailsOpen(false);
						const withFormat = sanitised(updated);
						if (workspace.writeManifest(draftId, withFormat)) setManifest(withFormat);
					}}
				/>
			)}
			{toast}
		</div>
	);
}

// ---------------------------------------------------------------------------
// The panes
// ---------------------------------------------------------------------------

function PreviewPane({ preview, state, autoRun, onAutoRun, history, onCopied }: { preview: PluginPreviewSession; state: PreviewState; autoRun: boolean; onAutoRun: (v: boolean) => void; history: number[]; onCopied: () => void }) {
	const copy = (text: string) => {
		void navigator.clipboard?.writeText(text).then(onCopied).catch(() => {});
	};
	return (
		<div class="wm-pane" style="display:flex;flex-direction:column;gap:6px">
			<label class="wm-switch">
				<span class="wm-label">{S.autoRunLabel}</span>
				<input type="checkbox" role="switch" checked={autoRun} onChange={(e) => onAutoRun((e.target as HTMLInputElement).checked)} />
			</label>
			{state.usage.RENDER && <BudgetMeter usage={state.usage.RENDER} history={history} />}
			{state.failure && <div class="wm-error-text" style="display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden">{state.failure.text.split('\n')[0]}</div>}
			{state.ui.root.length === 0 && <div class="wm-caption" style="padding-top:8px">{S.previewEmpty}</div>}
			{state.host && (state.host.canBack || state.host.title || state.host.commands.length > 0) && (
				<div class="wm-w-row wm-w5-header">
					{state.host.canBack && <button type="button" class="wm-w-button" aria-label={S.back} onClick={() => preview.send({ type: 'back' })}>‹</button>}
					<span class="wm-w-label-title" style="flex:1">{state.host.title ?? ''}</span>
					{state.host.commands.slice(0, 3).map((c) => (
						<button key={c.id} type="button" class="wm-w-button" onClick={() => preview.send({ type: 'command', id: c.id })}>{c.label}</button>
					))}
				</div>
			)}
			<div class="wm-plugin-panel" style="padding-top:6px">
				<PluginPanel widgets={state.ui.root} inputs={state.inputs} onEvent={(e) => preview.send(e)} onInsert={copy} onCopy={copy} />
			</div>
		</div>
	);
}

/** The instructions the last draw used against its budget, with a sparkline of the draws before it. */
function BudgetMeter({ usage, history }: { usage: Usage; history: number[] }) {
	const share = usage.stepLimit <= 0 ? 0 : Math.min(Math.max(usage.steps / usage.stepLimit, 0), 1);
	const warn = share > WARN_SHARE;
	const shares = history.map((v) => (usage.stepLimit <= 0 ? 0 : Math.min(Math.max(v / usage.stepLimit, 0), 1)));
	const step = 80 / (MAX_BUDGET_HISTORY - 1);
	const path = shares.map((v, i) => `${i === 0 ? 'M' : 'L'}${(i * step).toFixed(1)} ${(20 * (1 - v)).toFixed(1)}`).join(' ');
	return (
		<div>
			<div class="wm-caption" style="font-size:0.72rem">{S.usage(numbers.format(usage.steps), numbers.format(usage.stepLimit))}</div>
			<div class="wm-budget">
				<div class={`wm-budget-bar ${warn ? 'wm-budget-warn' : ''}`}><i style={{ width: `${share * 100}%` }} /></div>
				<svg width="80" height="20" viewBox="0 0 80 20" role="img" aria-label={S.budgetHistoryDesc}>{shares.length >= 2 && <path d={path} fill="none" stroke={warn ? 'var(--wmc-warning)' : 'var(--st-accent)'} stroke-width="1.5" />}</svg>
			</div>
		</div>
	);
}

function ConsolePane({ preview, revision, onJump }: { preview: PluginPreviewSession; revision: number; onJump: (line: number) => void }) {
	const entries = useMemo(() => preview.console(), [preview, revision]);
	const list = useRef<HTMLDivElement>(null);
	useEffect(() => {
		if (entries.length) list.current?.lastElementChild?.scrollIntoView({ block: 'nearest' });
	}, [entries.length]);
	return (
		<div style="display:flex;flex-direction:column;min-height:0;flex:1 1 auto">
			<div class="wm-row-between" style="padding:0 8px">
				{entries.length === 0 && <span class="wm-caption" style="padding-left:4px">{S.consoleEmpty}</span>}
				<span style="flex:1 1 auto" />
				<TextButton onClick={() => preview.clearConsole()}>{S.consoleClear}</TextButton>
			</div>
			<div ref={list} class="wm-pane" style="padding:4px 12px">
				{entries.map((entry, i) => <ConsoleLine key={i} entry={entry} onJump={onJump} />)}
			</div>
		</div>
	);
}

function ConsoleLine({ entry, onJump }: { entry: ConsoleEntry; onJump: (line: number) => void }) {
	const words = entry.kind === 'STARTED' ? S.consoleStarted : entry.kind === 'CANCELLED' ? S.consoleCancelled : entry.text || entry.message || '';
	const colour = entry.kind === 'ERROR' || entry.kind === 'STOPPED' ? 'wm-console-error' : entry.kind === 'PRINT' ? '' : 'wm-console-muted';
	const line = entry.line ?? null;
	const click = line !== null ? () => onJump(line) : undefined;
	return (
		<div class={`wm-console-line ${colour} ${click ? 'wm-clickable' : ''}`} onClick={click}>
			<span style="flex:1 1 auto;min-width:0">{words}</span>
			{line !== null && <span class="wm-line-chip">{S.lineLabel(line)}</span>}
		</div>
	);
}

function ProblemsPane({ diagnostics, lineStarts, onJump }: { diagnostics: PyDiagnostic[]; lineStarts: number[]; onJump: (r: E.Range) => void }) {
	if (!diagnostics.length) return <div class="wm-pane-empty">{S.problemsEmpty}</div>;
	const rank = { ERROR: 0, WARNING: 1, INFO: 2 };
	const ordered = [...diagnostics].sort((a, b) => rank[a.severity] - rank[b.severity] || a.start - b.start);
	return (
		<div class="wm-pane" style="padding:4px 12px">
			{ordered.map((d, i) => {
				const sev = d.severity;
				const line = E.lineOf(lineStarts, Math.min(Math.max(d.start, 0), lineStarts[lineStarts.length - 1]!)) + 1;
				return (
					<div key={i} class="wm-problem" onClick={() => onJump({ start: d.start, end: d.end })}>
						<span class="wm-dot" role="img" aria-label={sev === 'ERROR' ? S.severityError : sev === 'WARNING' ? S.severityWarning : S.severityInfo} style={{ background: sev === 'ERROR' ? 'var(--wmc-problem)' : sev === 'WARNING' ? 'var(--wmc-warning)' : 'var(--wmc-gutter-text)' }} />
						<span style="flex:1 1 auto">{Py.diagnosticText(d)}</span>
						<span class="wm-line-chip">{S.lineLabel(line)}</span>
					</div>
				);
			})}
		</div>
	);
}

function OutlinePane({ outline, lineStarts, onJump }: { outline: PyOutlineItem[]; lineStarts: number[]; onJump: (r: E.Range) => void }) {
	if (!outline.length) return <div class="wm-pane-empty">{S.outlineEmpty}</div>;
	return (
		<div class="wm-pane" style="padding:4px 0">
			{outline.map((item, i) => {
				const line = E.lineOf(lineStarts, Math.min(Math.max(item.nameStart, 0), lineStarts[lineStarts.length - 1]!)) + 1;
				return (
					<div key={i} class="wm-outline-item" style={{ paddingLeft: `${12 + item.depth * 16}px` }} onClick={() => onJump({ start: item.nameStart, end: item.nameEnd })}>
						<span class="wm-f" aria-hidden="true">{item.kind === 'function' ? 'f' : 'x'}</span>
						<span style="flex:1 1 auto;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{item.name}</span>
						<span class="wm-line-chip" style="color:var(--st-muted)">{S.lineLabel(line)}</span>
					</div>
				);
			})}
		</div>
	);
}

interface Injectable {
	event: PluginEvent;
	widgetEnabled: boolean;
}

function injectableEvents(targets: PluginTargets): Injectable[] {
	const out: Injectable[] = [];
	for (const b of targets.buttons) out.push({ event: { type: 'click', id: b.id }, widgetEnabled: b.enabled });
	for (const t of targets.toggles) out.push({ event: { type: 'toggle', id: t.id, value: !t.checked }, widgetEnabled: true });
	for (const tabs of targets.tabs) for (let i = 0; i < tabs.pages; i++) out.push({ event: { type: 'tab_selected', id: tabs.id, index: i }, widgetEnabled: true });
	for (const d of targets.dates) {
		const next = d.value !== null ? Dates.addDays(d.value, 1) : Dates.initial(Date.now(), d.time);
		out.push({ event: { type: 'date_changed', id: d.id, value: next }, widgetEnabled: true });
		out.push({ event: { type: 'date_changed', id: d.id, value: null }, widgetEnabled: true });
	}
	return out;
}

function eventLabel(item: Injectable): string {
	const e = item.event;
	switch (e.type) {
		case 'click': return item.widgetEnabled ? S.eventClick(e.id) : S.eventClickDisabled(e.id);
		case 'toggle': return e.value ? S.eventToggleOn(e.id) : S.eventToggleOff(e.id);
		case 'tab_selected': return S.eventTab(e.id, e.index + 1);
		case 'input_changed': return e.id;
		case 'date_changed': return e.value === null ? S.eventDateClear(e.id) : S.eventDate(e.id, new Date(e.value * 1000).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }));
		default: return `${e.type}${e.id ? ` ${e.id}` : ''}`;
	}
}

function customEvent(type: string, id: string, value: string): PluginEvent | null {
	const name = id.trim();
	if (!name) return null;
	switch (type) {
		case 'click': return { type: 'click', id: name };
		case 'toggle': return { type: 'toggle', id: name, value: value.trim().toLowerCase() === 'true' };
		case 'input_changed': return { type: 'input_changed', id: name, value };
		case 'tab_selected': return { type: 'tab_selected', id: name, index: Number.parseInt(value.trim(), 10) || 0 };
		case 'date_changed': {
			const n = Number.parseInt(value.trim(), 10);
			return { type: 'date_changed', id: name, value: Number.isFinite(n) ? n : null };
		}
		default: return null;
	}
}

function EventsPane({ targets, earlier, onSend }: { targets: PluginTargets; earlier: PluginEvent[]; onSend: (e: PluginEvent) => void }) {
	const events = useMemo(() => injectableEvents(targets), [targets]);
	const [texts, setTexts] = useState<Record<string, string>>({});
	const eventTypes = Py.apiEntries().eventTypes;
	const [type, setType] = useState<string>(eventTypes[0] ?? 'click');
	const [id, setId] = useState('');
	const [value, setValue] = useState('');
	return (
		<div class="wm-pane" style="display:flex;flex-direction:column;gap:8px">
			{targetsEmpty(targets) && <div class="wm-caption">{S.eventsEmpty}</div>}
			<div class="wm-events-chips">
				{events.map((item, i) => <button key={i} type="button" class="st-chip" onClick={() => onSend(item.event)}>{eventLabel(item)}</button>)}
			</div>
			{targets.inputs.map((input) => (
				<div key={input.id} class="wm-row-between">
					<label class="st-field" style="flex:1 1 auto">
						<span>{S.eventInputLabel(input.id)}</span>
						<input class="st-input" value={texts[input.id] ?? ''} onInput={(e) => setTexts({ ...texts, [input.id]: (e.target as HTMLInputElement).value })} />
					</label>
					<TextButton onClick={() => onSend({ type: 'input_changed', id: input.id, value: texts[input.id] ?? '' })}>{S.eventSend}</TextButton>
				</div>
			))}
			{earlier.length > 0 && <div><TextButton onClick={() => earlier.forEach(onSend)}>{S.eventReplay(earlier.length)}</TextButton></div>}
			<hr class="wm-w-divider" />
			<h3 class="wm-heading">{S.eventCustomTitle}</h3>
			<div class="wm-events-chips">
				{eventTypes.map((option) => <button key={option} type="button" class={`st-chip ${type === option ? 'st-chip-on' : ''}`} aria-pressed={type === option} onClick={() => setType(option)}><code>{option}</code></button>)}
			</div>
			<div class="wm-row-between">
				<label class="st-field" style="flex:1 1 auto"><span>{S.eventIdLabel}</span><input class="st-input" value={id} onInput={(e) => setId((e.target as HTMLInputElement).value)} /></label>
				{type !== 'click' && <label class="st-field" style="flex:1 1 auto"><span>{S.eventValueLabel}</span><input class="st-input" value={value} onInput={(e) => setValue((e.target as HTMLInputElement).value)} /></label>}
				<TextButton disabled={!id.trim()} onClick={() => { const e = customEvent(type, id, value); if (e) onSend(e); }}>{S.eventSend}</TextButton>
			</div>
		</div>
	);
}

function StoragePane({ storage, declared, busy }: { storage: PreviewStorage; declared: boolean; busy: boolean }) {
	const [revision, setRevision] = useState(0);
	const [key, setKey] = useState('');
	const [value, setValue] = useState('');
	const [refusal, setRefusal] = useState<string | null>(null);
	const entries = useMemo(() => storage.keys().sort().map((k) => [k, storage.get(k) ?? ''] as const), [storage, revision, busy]);
	if (!declared) return <div class="wm-pane-empty">{S.storageUndeclared}</div>;
	return (
		<div class="wm-pane" style="display:flex;flex-direction:column;gap:6px">
			<div class="wm-events-chips">
				<button type="button" class="st-chip st-chip-on" aria-pressed="true">{S.storageScopePreview}</button>
			</div>
			{busy && <div class="wm-caption">{S.storageBusy}</div>}
			{entries.length === 0 && <div class="wm-caption">{S.storageEmpty}</div>}
			{entries.map(([name, stored]) => (
				<div key={name} class="wm-storage-entry">
					<div class="wm-kv"><b>{name}</b><span>{stored}</span></div>
					<IconButton label={S.storageRemoveDesc(name)} disabled={busy} onClick={() => { storage.remove(name); setRevision((n) => n + 1); }}><IconDeleteSvg /></IconButton>
				</div>
			))}
			<div class="wm-row-between">
				<label class="st-field" style="flex:2 1 0"><span>{S.storageKey}</span><input class="st-input" value={key} disabled={busy} onInput={(e) => setKey((e.target as HTMLInputElement).value)} /></label>
				<label class="st-field" style="flex:3 1 0"><span>{S.storageValue}</span><input class="st-input" value={value} disabled={busy} onInput={(e) => setValue((e.target as HTMLInputElement).value)} /></label>
			</div>
			{refusal && <div class="wm-error-text">{S.storageRefused(refusal)}</div>}
			<div><TextButton disabled={busy || !key} onClick={() => { const refused = storage.set(key, value); setRefusal(refused); if (!refused) { setKey(''); setValue(''); } setRevision((n) => n + 1); }}>{S.storageSave}</TextButton></div>
		</div>
	);
}

/**
 * The web editor's own pane: the manifest as a form, and the file as the
 * store would list it: its size, a download, and a repository entry with the
 * sha256 the app insists on for plugins.
 */
function DetailsPane({ manifest, script, onManifest, onExport }: { manifest: PluginManifest; script: string; onManifest: (m: PluginManifest) => void; onExport: () => void }) {
	const [draft, setDraft] = useState(manifest);
	useEffect(() => setDraft(manifest), [manifest]);
	const problems = manifestProblems(draft);
	const commit = (next: PluginManifest) => {
		setDraft(next);
		onManifest(next);
	};
	const zip = useMemo(() => pluginFile(draft, script), [draft, script]);
	const [sha, setSha] = useState<string | null>(null);
	useEffect(() => {
		let live = true;
		setSha(null);
		const t = setTimeout(() => void sha256Hex(zip).then((h) => live && setSha(h)), 300);
		return () => {
			live = false;
			clearTimeout(t);
		};
	}, [zip]);
	const scriptBytes = utf8.encode(script).byteLength;
	const slug = slugify(draft.id.split('.').pop() || draft.name) || 'plugin';
	const field = (label: string, key: 'name' | 'id' | 'pluginVersion' | 'author', mono = false) => (
		<label class="st-field"><span>{label}</span><input class="st-input" style={mono ? 'font-family:var(--wmc-mono)' : undefined} value={draft[key]} onInput={(e) => commit({ ...draft, [key]: (e.target as HTMLInputElement).value })} /></label>
	);
	return (
		<div class="wm-pane" style="display:flex;flex-direction:column;gap:8px">
			<div class="wm-details-grid">
				{field(S.detailsName, 'name')}
				{field(S.detailsId, 'id', true)}
				{field(S.detailsVersion, 'pluginVersion', true)}
				{field(S.detailsAuthor, 'author')}
			</div>
			<label class="st-field"><span>{S.detailsDescription}</span><textarea class="st-input" rows={2} value={draft.description} onInput={(e) => commit({ ...draft, description: (e.target as HTMLTextAreaElement).value })} /></label>
			{PERMISSIONS.map((p) => (
				<label key={p} class="wm-w-toggle">
					<input
						type="checkbox"
						checked={draft.permissions.includes(p)}
						onChange={(e) => {
							const on = (e.target as HTMLInputElement).checked;
							const next = new Set(draft.permissions.filter((x) => x !== p));
							if (on) next.add(p);
							commit({ ...draft, permissions: PERMISSIONS.filter((x) => next.has(x)) });
						}}
					/>
					<span>{p === STORAGE_PERMISSION ? S.permissionStorage : S.permissionReminders}</span>
				</label>
			))}
			{problems.map((p, i) => <div key={i} class="wm-error-text">{p}</div>)}
			<hr class="wm-w-divider" />
			<div class="wm-caption">{fmtBytes(zip.byteLength)} · 2 entries · Python {fmtBytes(scriptBytes)}{scriptBytes > MAX_SCRIPT_BYTES ? ` · ${S.rejectScriptTooLarge(draft.name, MAX_SCRIPT_BYTES / 1024)}` : ''}</div>
			<div class="wm-w-row">
				<button type="button" class="st-btn st-btn-sm st-btn-primary" disabled={problems.length > 0} onClick={onExport}>{S.exportAction}</button>
				{sha && <CopyButton text={JSON.stringify({ id: slug, type: 'plugin', name: sanitise(draft.name, 'NAME'), version: sanitise(draft.pluginVersion, 'VERSION'), author: sanitise(draft.author, 'AUTHOR'), description: sanitise(draft.description, 'DESCRIPTION'), path: `plugins/${slug}.wmplugin`, sha256: sha, sizeBytes: zip.byteLength }, null, 2)} label="Copy repository entry (with sha256)" class="st-btn st-btn-sm" />}
			</div>
			<div class="wm-caption">A repository lists a plugin with its sha256, and the app refuses one without it. Full API reference: <a href="/plugins/api-reference/">/plugins/api-reference</a>.</div>
		</div>
	);
}

// ---------------------------------------------------------------------------
// The plugin's panel as the keyboard would draw it
// ---------------------------------------------------------------------------

export function PluginPanel({ widgets, inputs, onEvent, onInsert, onCopy, still }: { widgets: PluginWidget[]; inputs: Record<string, string>; onEvent: (e: PluginEvent) => void; onInsert: (text: string) => void; onCopy: (text: string) => void; still?: boolean }) {
	return <>{widgets.map((w, i) => <Widget key={i} w={w} inputs={inputs} onEvent={onEvent} onInsert={onInsert} onCopy={onCopy} still={!!still} />)}</>;
}

/** The values a plugin's settings page starts with, typed the way the app hands them to the script. */
function settingDefaults(m: PluginManifest): Record<string, unknown> {
	const out: Record<string, unknown> = {};
	for (const s of m.settings ?? []) {
		const raw = s.default ?? '';
		out[s.key] = s.type === 'switch' ? raw.trim() === 'true' : s.type === 'number' ? (Number.isFinite(Number(raw)) && raw.trim() !== '' ? Number(raw) : null) : raw;
	}
	return out;
}

function Widget({ w, inputs, onEvent, onInsert, onCopy, still }: { w: PluginWidget; inputs: Record<string, string>; onEvent: (e: PluginEvent) => void; onInsert: (text: string) => void; onCopy: (text: string) => void; still: boolean }) {
	const [tab, setTab] = useState(0);
	const kids = (list: PluginWidget[]) => <PluginPanel widgets={list} inputs={inputs} onEvent={onEvent} onInsert={onInsert} onCopy={onCopy} still={still} />;
	switch (w.kind) {
		case 'v5': return <V5Widget w={w} inputs={inputs} onEvent={onEvent} onInsert={onInsert} onCopy={onCopy} still={still} />;
		case 'column': return <div class="wm-w-column">{kids(w.children)}</div>;
		case 'row': return <div class="wm-w-row">{kids(w.children)}</div>;
		case 'label': return <div class={`wm-w-label ${w.style === 'TITLE' ? 'wm-w-label-title' : w.style === 'CAPTION' ? 'wm-w-label-caption' : ''}`}>{w.text}</div>;
		case 'output': return (
			<div class="wm-w-output">
				<div class={`wm-w-output-text ${w.mono ? 'wm-w-output-mono' : ''}`}>{w.text}</div>
				{(w.insertable || w.copyable) && (
					<div class="wm-w-output-actions">
						{w.insertable && <button type="button" class="wm-w-button" onClick={() => onInsert(w.text)}>{S.insert}</button>}
						{w.copyable && <button type="button" class="wm-w-button" onClick={() => onCopy(w.text)}>{S.copy}</button>}
					</div>
				)}
			</div>
		);
		case 'button': return <button type="button" class={`wm-w-button ${w.primary ? 'wm-w-button-primary' : ''}`} disabled={!w.enabled} onClick={() => onEvent({ type: 'click', id: w.id })}>{w.text}</button>;
		case 'toggle': return (
			<label class="wm-w-toggle">
				<input type="checkbox" role="switch" checked={w.checked} onChange={(e) => onEvent({ type: 'toggle', id: w.id, value: (e.target as HTMLInputElement).checked })} />
				<span>{w.label}</span>
			</label>
		);
		case 'input': return (
			<div class="wm-w-input">
				{w.label && <label>{w.label}</label>}
				<input class="st-input" placeholder={w.placeholder} value={still ? '' : inputs[w.id] ?? ''} readOnly={still} onInput={(e) => onEvent({ type: 'input_changed', id: w.id, value: (e.target as HTMLInputElement).value })} />
			</div>
		);
		case 'date': return <DateWidget w={w} onEvent={onEvent} />;
		case 'spacer': return <div style={{ height: `${w.height}px` }} />;
		case 'divider': return <hr class="wm-w-divider" />;
		case 'progress': return <div class="wm-w-progress"><i /></div>;
		case 'tabs': {
			const current = Math.min(tab, w.pages.length - 1);
			return (
				<div class="wm-w-column">
					<div class="wm-w-tabs" role="tablist">
						{w.pages.map((p, i) => (
							<button key={i} type="button" role="tab" class={`st-chip ${i === current ? 'st-chip-on' : ''}`} aria-selected={i === current} onClick={() => { setTab(i); onEvent({ type: 'tab_selected', id: w.id, index: i }); }}>
								{p.title || S.tabDefaultTitle(i + 1)}
							</button>
						))}
					</div>
					{w.pages[current] && kids(w.pages[current]!.children)}
				</div>
			);
		}
	}
}

/** A plugin's ui.date, drawn with the same buttons the keyboard draws (PluginWidgets.kt DateWidget). */
function DateWidget({ w, onEvent }: { w: Extract<PluginWidget, { kind: 'date' }>; onEvent: (e: PluginEvent) => void }) {
	const send = (value: number | null) => onEvent({ type: 'date_changed', id: w.id, value });
	const value = w.value;
	const step = (label: string, aria: string, onClick: () => void) => <button type="button" class="wm-w-button" aria-label={aria} onClick={onClick}>{label}</button>;
	return (
		<div class="wm-w-input">
			{w.label && <label>{w.label}</label>}
			{value === null ? (
				<div class="wm-w-row">
					<span class="wm-w-label wm-w-label-caption" style="flex:1 1 auto">{S.dateNone}</span>
					<button type="button" class="wm-w-button" onClick={() => send(Dates.initial(Date.now(), w.time))}>{S.dateSet}</button>
				</div>
			) : (
				<>
					<div class="wm-w-row">
						{step('‹', S.datePreviousDay, () => send(Dates.addDays(value, -1)))}
						<span class="wm-w-label" style="flex:1 1 auto;text-align:center">{new Date(value * 1000).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })}</span>
						{step('›', S.dateNextDay, () => send(Dates.addDays(value, 1)))}
					</div>
					{w.time && (
						<div class="wm-w-row">
							{step(S.dateHourEarlier, S.dateHourEarlier, () => send(Dates.addMinutes(value, -60)))}
							{step('‹', S.dateEarlier(w.step), () => send(Dates.addMinutes(value, -w.step)))}
							<span class="wm-w-label" style="flex:1 1 auto;text-align:center">{new Date(value * 1000).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })}</span>
							{step('›', S.dateLater(w.step), () => send(Dates.addMinutes(value, w.step)))}
							{step(S.dateHourLater, S.dateHourLater, () => send(Dates.addMinutes(value, 60)))}
						</div>
					)}
					<div class="wm-w-row">
						<button type="button" class="wm-w-button" onClick={() => send(Dates.today(value, Date.now()))}>{S.dateToday}</button>
						<button type="button" class="wm-w-button" onClick={() => send(null)}>{S.dateClear}</button>
					</div>
				</>
			)}
		</div>
	);
}

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

function Dialog({ title, children, actions, onDismiss }: { title: string; children: ComponentChildren; actions: ComponentChildren; onDismiss: () => void }) {
	useEffect(() => {
		const onKey = (e: KeyboardEvent) => {
			if (e.key === 'Escape') {
				e.stopPropagation();
				onDismiss();
			}
		};
		window.addEventListener('keydown', onKey, true);
		return () => window.removeEventListener('keydown', onKey, true);
	}, [onDismiss]);
	return (
		<div class="wm-dialog-backdrop" onClick={onDismiss}>
			<div class="wm-dialog" role="dialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()}>
				<h2>{title}</h2>
				<div class="wm-dialog-body">{children}</div>
				<div class="wm-dialog-actions">{actions}</div>
			</div>
		</div>
	);
}

function TextButton({ children, onClick, disabled }: { children: ComponentChildren; onClick: () => void; disabled?: boolean }) {
	return <button type="button" class="st-btn st-btn-sm st-btn-ghost" disabled={disabled} onClick={onClick}>{children}</button>;
}

function IconButton({ label, onClick, disabled, expanded, children }: { label: string; onClick: (e: MouseEvent) => void; disabled?: boolean; expanded?: boolean; children: ComponentChildren }) {
	return <button type="button" class="st-icon-btn" aria-label={label} title={label} disabled={disabled} aria-expanded={expanded} onClick={onClick}>{children}</button>;
}

function GoToLineDialog({ lines, onDismiss, onGo }: { lines: number; onDismiss: () => void; onGo: (line: number) => void }) {
	const [input, setInput] = useState('');
	const line = E.parseLineNumber(input, lines);
	const bad = input.trim() !== '' && line === null;
	return (
		<Dialog title={S.goToLineTitle} onDismiss={onDismiss} actions={<><TextButton onClick={onDismiss}>{S.cancel}</TextButton><TextButton disabled={line === null} onClick={() => line !== null && onGo(line)}>{S.goToLineGo}</TextButton></>}>
			<label class="st-field">
				<span>{S.goToLineLabel}</span>
				<input class="st-input" inputMode="numeric" autoFocus value={input} aria-invalid={bad} onInput={(e) => setInput((e.target as HTMLInputElement).value)} onKeyDown={(e) => { if (e.key === 'Enter' && line !== null) onGo(line); }} />
				{bad && <span class="wm-error-text">{S.goToLineError(lines)}</span>}
			</label>
		</Dialog>
	);
}

const RENAME_PROBLEMS: Record<RenameProblem, string> = { not_a_name: S.renameNotAName, keyword: S.renameKeyword, builtin: S.renameBuiltin, taken: S.renameTaken, api: S.renameApi, hook: S.renameHook };

function RenameDialog({ plan, onDismiss, onRename }: { plan: RenamePlan; onDismiss: () => void; onRename: (name: string) => void }) {
	const [name, setName] = useState(plan.oldName);
	const field = useRef<HTMLInputElement>(null);
	useEffect(() => {
		field.current?.focus();
		field.current?.select();
	}, []);
	const problem: RenameProblem | null = name === plan.oldName ? null : Py.rename(plan.snapshot, plan.caret, name)?.problem ?? null;
	const ready = name !== plan.oldName && problem === null;
	const problemText = problem ? RENAME_PROBLEMS[problem] : null;
	return (
		<Dialog title={S.renameTitle(plan.oldName)} onDismiss={onDismiss} actions={<><TextButton onClick={onDismiss}>{S.cancel}</TextButton><TextButton disabled={!ready} onClick={() => onRename(name)}>{S.renameAction}</TextButton></>}>
			<label class="st-field">
				<span>{S.renameLabel}</span>
				<input ref={field} class="st-input" style="font-family:var(--wmc-mono)" value={name} autocapitalize="off" autocorrect="off" spellcheck={false} aria-invalid={problem !== null} onInput={(e) => setName((e.target as HTMLInputElement).value)} onKeyDown={(e) => { if (e.key === 'Enter' && ready) onRename(name); }} />
				<span class={problemText ? 'wm-error-text' : 'wm-caption'}>{problemText ?? S.renameCount(plan.spans.length)}</span>
			</label>
		</Dialog>
	);
}

function reasonLabel(reason: SnapshotReason): string {
	switch (reason) {
		case 'periodic': return S.reasonPeriodic;
		case 'before_restore': return S.reasonBeforeRestore;
		case 'publish': return S.reasonPublish;
		case 'import': return S.reasonImport;
		default: return S.reasonManual;
	}
}

function VersionsDialog({ versions, onOpen, onDismiss }: { versions: PluginSnapshot[]; onOpen: (v: PluginSnapshot) => void; onDismiss: () => void }) {
	const now = Date.now();
	return (
		<Dialog title={S.versionsTitle} onDismiss={onDismiss} actions={<TextButton onClick={onDismiss}>{S.close}</TextButton>}>
			{versions.length === 0 ? (
				<p>{S.versionsEmpty}</p>
			) : (
				<div style="max-height:380px;overflow-y:auto">
					{versions.map((v) => (
						<div key={v.snapshotId} class="wm-version" role="button" tabIndex={0} onClick={() => onOpen(v)} onKeyDown={(e) => { if (e.key === 'Enter') onOpen(v); }}>
							<div>{relativeTime(v.at, now)}</div>
							<div class="wm-caption">{reasonLabel(v.reason)}<br />{S.versionLines(v.lines)}</div>
							{v.note && <div class="wm-caption">{v.note}</div>}
						</div>
					))}
				</div>
			)}
		</Dialog>
	);
}

function VersionDiffDialog({ version, rows, onRestore, onDismiss }: { version: PluginSnapshot; rows: E.DiffRow[] | null; onRestore: () => void; onDismiss: () => void }) {
	const changed = rows?.some((r) => r.row === 'line' && r.line.kind !== 'SAME') === true;
	return (
		<Dialog title={S.versionDiffTitle(relativeTime(version.at))} onDismiss={onDismiss} actions={<><TextButton onClick={onDismiss}>{S.cancel}</TextButton><TextButton disabled={!changed} onClick={onRestore}>{S.restoreAction}</TextButton></>}>
			{rows === null ? (
				<div class="wm-w-progress"><i /></div>
			) : !changed ? (
				<p>{S.versionSameAsNow}</p>
			) : (
				<>
					<div class="wm-caption">{S.versionDiffCaption}</div>
					<div class="wm-code-list">
						{rows.map((row, i) =>
							row.row === 'unchanged' ? (
								<div key={i} class="wm-diff-fold">{S.unchangedLines(row.count)}</div>
							) : (
								<div key={i} class={row.line.kind === 'ADDED' ? 'wm-diff-added' : row.line.kind === 'REMOVED' ? 'wm-diff-removed' : 'wm-diff-same'}>{(row.line.kind === 'ADDED' ? '+ ' : row.line.kind === 'REMOVED' ? '- ' : '  ') + row.line.text}</div>
							)
						)}
					</div>
				</>
			)}
		</Dialog>
	);
}

function CodeKeysDialog({ showRow, order, hidden, onChange, onReset, onDismiss }: { showRow: boolean; order: string[]; hidden: Set<string>; onChange: (v: { showRow: boolean; order: string[]; hidden: string[] }) => void; onReset: () => void; onDismiss: () => void }) {
	const labels = arrangeKeys(PYTHON_ACCESSORY_KEYS, order, new Set()).map((k) => k.label);
	const move = (index: number, delta: number) => {
		const next = labels.slice();
		const target = index + delta;
		if (target < 0 || target >= next.length) return;
		[next[index], next[target]] = [next[target]!, next[index]!];
		onChange({ showRow, order: next, hidden: [...hidden] });
	};
	return (
		<Dialog title={S.keysTitle} onDismiss={onDismiss} actions={<><TextButton onClick={onReset}>{S.keysReset}</TextButton><TextButton onClick={onDismiss}>{S.done}</TextButton></>}>
			<label class="wm-switch"><span>{S.keysShowLabel}</span><input type="checkbox" role="switch" checked={showRow} onChange={(e) => onChange({ showRow: (e.target as HTMLInputElement).checked, order, hidden: [...hidden] })} /></label>
			<div class="wm-caption">{S.keysCaption}</div>
			<div style="max-height:380px;overflow-y:auto">
				{labels.map((label, index) => (
					<div key={label} class="wm-keys-item">
						<input type="checkbox" aria-label={S.keyShowDesc(label)} checked={!hidden.has(label)} onChange={(e) => { const next = new Set(hidden); if ((e.target as HTMLInputElement).checked) next.delete(label); else next.add(label); onChange({ showRow, order: labels, hidden: [...next] }); }} />
						<code>{label}</code>
						<IconButton label="Move up" disabled={index === 0} onClick={() => move(index, -1)}><svg viewBox="0 0 24 24" width="18" height="18"><path d="M7 14l5-5 5 5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg></IconButton>
						<IconButton label="Move down" disabled={index === labels.length - 1} onClick={() => move(index, 1)}><svg viewBox="0 0 24 24" width="18" height="18"><path d="M7 10l5 5 5-5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg></IconButton>
					</div>
				))}
			</div>
		</Dialog>
	);
}

function PluginDetailsDialog({ manifest, onDismiss, onSave }: { manifest: PluginManifest; onDismiss: () => void; onSave: (m: PluginManifest) => void }) {
	const [name, setName] = useState(manifest.name);
	const [id, setId] = useState(manifest.id);
	const [version, setVersion] = useState(manifest.pluginVersion);
	const [author, setAuthor] = useState(manifest.author);
	const [description, setDescription] = useState(manifest.description);
	const [storage, setStorage] = useState(manifest.permissions.includes(STORAGE_PERMISSION));
	const edited: PluginManifest = { ...manifest, name, id, pluginVersion: version, author, description, permissions: storage ? [STORAGE_PERMISSION] : [] };
	const problems = manifestProblems(edited);
	const field = (label: string, value: string, set: (v: string) => void, mono = false) => (
		<label class="st-field"><span>{label}</span><input class="st-input" style={mono ? 'font-family:var(--wmc-mono)' : undefined} value={value} onInput={(e) => set((e.target as HTMLInputElement).value)} /></label>
	);
	return (
		<Dialog title={S.detailsTitle} onDismiss={onDismiss} actions={<><TextButton onClick={onDismiss}>{S.cancel}</TextButton><TextButton onClick={() => onSave(edited)}>{S.save}</TextButton></>}>
			{field(S.detailsName, name, setName)}
			{field(S.detailsId, id, setId, true)}
			{field(S.detailsVersion, version, setVersion, true)}
			{field(S.detailsAuthor, author, setAuthor)}
			<label class="st-field"><span>{S.detailsDescription}</span><textarea class="st-input" rows={3} value={description} onInput={(e) => setDescription((e.target as HTMLTextAreaElement).value)} /></label>
			<label class="wm-w-toggle"><input type="checkbox" checked={storage} onChange={(e) => setStorage((e.target as HTMLInputElement).checked)} /><span>{S.permissionStorage}</span></label>
			{problems.map((p, i) => <div key={i} class="wm-error-text">{p}</div>)}
		</Dialog>
	);
}

// ---------------------------------------------------------------------------
// Small parts
// ---------------------------------------------------------------------------

function useToast(): [ComponentChildren, (text: string) => void] {
	const [text, setText] = useState<string | null>(null);
	const timer = useRef<number | null>(null);
	const show = useCallback((t: string) => {
		setText(t);
		if (timer.current !== null) window.clearTimeout(timer.current);
		timer.current = window.setTimeout(() => setText(null), 4000);
	}, []);
	return [text ? <div class="wm-snackbar" role="status">{text}</div> : null, show];
}

const svg = (d: string) => () => <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path d={d} fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" /></svg>;
const IconBackSvg = svg('M19 12H5 M12 19l-7-7 7-7');
const IconForwardSvg = svg('M5 12h14 M12 5l7 7-7 7');
const IconUndoSvg = svg('M9 14 4 9l5-5 M4 9h10a6 6 0 0 1 0 12h-3');
const IconRedoSvg = svg('m15 14 5-5-5-5 M20 9H10a6 6 0 0 0 0 12h3');
const IconPlaySvg = () => <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path d="M8 5v14l11-7z" fill="currentColor" /></svg>;
const IconStopSvg = () => <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><rect x="6" y="6" width="12" height="12" rx="1" fill="currentColor" /></svg>;
const IconPublishSvg = svg('M5 4h14 M12 20V8 M7 13l5-5 5 5');
const IconMoreSvg = () => <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><circle cx="12" cy="5" r="1.8" fill="currentColor" /><circle cx="12" cy="12" r="1.8" fill="currentColor" /><circle cx="12" cy="19" r="1.8" fill="currentColor" /></svg>;
const IconFindSvg = svg('M21 21l-4.35-4.35 M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z');
const IconListSvg = svg('M8 6h13 M8 12h13 M8 18h13 M3 6h.01 M3 12h.01 M3 18h.01');
const IconBracesSvg = svg('M8 3H7a2 2 0 0 0-2 2v5a2 2 0 0 1-2 2 2 2 0 0 1 2 2v5a2 2 0 0 0 2 2h1 M16 3h1a2 2 0 0 1 2 2v5a2 2 0 0 0 2 2 2 2 0 0 0-2 2v5a2 2 0 0 1-2 2h-1');
const IconRenameSvg = svg('M4 20h4l10-10-4-4L4 16z m13 7 4 4');
const IconSaveSvg = svg('M12 3v12 m7 10 5 5 5-5 M4 19h16');
const IconHistorySvg = svg('M3 12a9 9 0 1 0 3-6.7L3 8 M3 3v5h5 M12 7v5l3 2');
const IconTextIncreaseSvg = svg('M3 18 8 6l5 12 M5 14h6 M17 9v6 M14 12h6');
const IconTextDecreaseSvg = svg('M3 18 8 6l5 12 M5 14h6 M14 12h6');
const IconWrapSvg = svg('M3 6h18 M3 12h13a3 3 0 0 1 0 6h-4 M3 18h6 m6 2-2-2 2-2');
const IconFoldSvg = svg('M4 4h16 M4 20h16 m8 9 4 3 4-3 m-8 6 4-3 4 3');
const IconUnfoldSvg = svg('M4 4h16 M4 20h16 m8 10 4-3 4 3 m-8 4 4 3 4-3');
const IconKeyboardSvg = svg('M3 6h18v12H3z M7 10h.01 M11 10h.01 M15 10h.01 M7 14h10');
const IconWandSvg = svg('m15 4 5 5 M5 19 14 10 M18 2v3 M21 5h-3 M4 9l1.5 1.5 M9 4 7.5 5.5');
const IconTuneSvg = svg('M4 6h10 M18 6h2 M4 12h2 M10 12h10 M4 18h12 M20 18h0 M14 4v4 M6 10v4 M16 16v4');
const IconBookmarkSvg = svg('M6 3h12v18l-6-4-6 4z M12 8v6 M9 11h6');
const IconCopySvg = svg('M9 9h10v10H9z M5 15V5h10');
const IconPasteSvg = svg('M8 4h8v3H8z M6 6H5v15h14V6h-1 M9 12h6 M9 16h6');
const IconDeleteSvg = svg('M4 7h16 M9 7V4h6v3 M6 7l1 13h10l1-13 M10 11v6 M14 11v6');

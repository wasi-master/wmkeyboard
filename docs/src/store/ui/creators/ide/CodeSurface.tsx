/**
 * The app's code field (CodeSurface.kt, CodeEditor.kt, CodeCompletionList.kt)
 * on CodeMirror 6. CodeMirror draws the text, the gutter and the folds; the
 * rules are the app's own: the smart edits, the history with its coalescing,
 * every key of CodeShortcuts.kt, the suggestion list and its ranking, the
 * bracket box, the squiggles and the exact palette.
 */
import { signal, type Signal } from '@preact/signals';
import { useEffect, useRef, useState } from 'preact/hooks';
import { Annotation, Compartment, EditorState, RangeSet, RangeSetBuilder, StateEffect, StateField, type Extension, type Transaction, type TransactionSpec } from '@codemirror/state';
import { Decoration, EditorView, GutterMarker, drawSelection, gutterLineClass, highlightActiveLine, highlightActiveLineGutter, keymap, lineNumbers, type DecorationSet, type KeyBinding, type ViewUpdate } from '@codemirror/view';
import { codeFolding, foldEffect, foldGutter as cmFoldGutter, foldService, foldedRanges, unfoldEffect } from '@codemirror/language';
import { cursorPageDown, cursorPageUp, selectPageDown, selectPageUp } from '@codemirror/commands';
import { complete, folds, lex, PyNameStyle, PyTokenKind, type PyCompletionItem, type PyCompletionKind, type PyCompletions, type PyHost, type PyTokens } from '../../../lib/python';
import * as E from './edits';

export { E };

// ---------------------------------------------------------------------------
// Commands (CodeShortcuts.kt)
// ---------------------------------------------------------------------------

export type ScreenCommand =
	| 'FIND' | 'REPLACE' | 'FIND_NEXT' | 'FIND_PREVIOUS' | 'GO_TO_LINE' | 'GO_TO_DEFINITION' | 'RENAME' | 'GO_TO_SYMBOL'
	| 'FORMAT' | 'TOGGLE_WRAP' | 'ZOOM_IN' | 'ZOOM_OUT' | 'ZOOM_RESET' | 'SAVE' | 'RUN' | 'STOP' | 'NEXT_PROBLEM' | 'PREVIOUS_PROBLEM'
	| 'SHOW_PROBLEMS' | 'SHOW_CONSOLE' | 'SHOW_COMMANDS' | 'ESCAPE';

/** The keys a menu shows beside a command, as VS Code writes them. */
export const SHORTCUT: Record<string, string> = {
	FIND: 'Ctrl+F', REPLACE: 'Ctrl+H', FIND_NEXT: 'F3', FIND_PREVIOUS: 'Shift+F3', GO_TO_LINE: 'Ctrl+G', GO_TO_DEFINITION: 'F12', RENAME: 'F2',
	GO_TO_SYMBOL: 'Ctrl+Shift+O', FORMAT: 'Shift+Alt+F', TOGGLE_WRAP: 'Alt+Z', ZOOM_IN: 'Ctrl+=', ZOOM_OUT: 'Ctrl+-', ZOOM_RESET: 'Ctrl+0', SAVE: 'Ctrl+S',
	RUN: 'F5', STOP: 'Shift+F5', NEXT_PROBLEM: 'F8', PREVIOUS_PROBLEM: 'Shift+F8', SHOW_PROBLEMS: 'Ctrl+Shift+M', SHOW_CONSOLE: 'Ctrl+Shift+Y',
	SHOW_COMMANDS: 'F1', FOLD_ALL: 'Ctrl+K Ctrl+0', UNFOLD_ALL: 'Ctrl+K Ctrl+J', UNDO: 'Ctrl+Z', REDO: 'Ctrl+Y',
};

// ---------------------------------------------------------------------------
// Decorations the screen asks for (CodeDecorations)
// ---------------------------------------------------------------------------

export type Severity = 'ERROR' | 'WARNING' | 'INFO';

export interface Squiggle {
	start: number;
	end: number;
	severity: Severity;
}

export interface CodeDecorations {
	matches: E.Range[];
	activeMatch: number;
	squiggles: Squiggle[];
	/** Line index to the worst severity on it. */
	gutterMarks: Map<number, Severity>;
}

export const NO_DECORATIONS: CodeDecorations = { matches: [], activeMatch: -1, squiggles: [], gutterMarks: new Map() };

const setDecorations = StateEffect.define<CodeDecorations>();
const historyKind = Annotation.define<'push' | 'skip'>();

const UNDO_DEPTH = 100;
const COALESCE_MS = 700;
const MAX_SQUIGGLES = 200;
const COMPACT_LINES = 3;

interface Snapshot {
	text: string;
	anchor: number;
	head: number;
}

// ---------------------------------------------------------------------------
// Tokens and colours
// ---------------------------------------------------------------------------

const tokensField = StateField.define<PyTokens>({
	create: (state) => lex(state.doc.toString()),
	update: (value, tr) => (tr.docChanged ? lex(tr.newDoc.toString()) : value),
});

const styles = {
	comment: Decoration.mark({ class: 'wmc-comment' }),
	string: Decoration.mark({ class: 'wmc-string' }),
	number: Decoration.mark({ class: 'wmc-number' }),
	keyword: Decoration.mark({ class: 'wmc-keyword' }),
	fn: Decoration.mark({ class: 'wmc-function' }),
	key: Decoration.mark({ class: 'wmc-key' }),
	problem: Decoration.mark({ class: 'wmc-problem' }),
};

/** PythonCode.styleOf: colours from the token and, for a name, what the tooling says it is. */
function styleOf(tokens: PyTokens, index: number): Decoration | null {
	switch (tokens.kind(index)) {
		case PyTokenKind.COMMENT: return styles.comment;
		case PyTokenKind.STRING:
		case PyTokenKind.FSTRING:
			return tokens.unterminated(index) ? styles.problem : styles.string;
		case PyTokenKind.NUMBER: return styles.number;
		case PyTokenKind.KEYWORD:
		case PyTokenKind.SOFT_KEYWORD:
			return styles.keyword;
		case PyTokenKind.UNKNOWN: return styles.problem;
		case PyTokenKind.NAME:
			switch (tokens.nameStyle(index)) {
				case PyNameStyle.FUNCTION:
				case PyNameStyle.BUILTIN:
					return styles.fn;
				case PyNameStyle.ATTRIBUTE:
				case PyNameStyle.API:
					return styles.key;
				case PyNameStyle.CONSTANT: return styles.number;
				default: return null;
			}
		default: return null;
	}
}

const highlightField = StateField.define<DecorationSet>({
	create: (state) => colour(state.field(tokensField)),
	update: (value, tr) => (tr.docChanged ? colour(tr.state.field(tokensField)) : value),
	provide: (f) => EditorView.decorations.from(f),
});

function colour(tokens: PyTokens): DecorationSet {
	const builder = new RangeSetBuilder<Decoration>();
	for (let i = 0; i < tokens.size; i++) {
		const style = styleOf(tokens, i);
		if (!style) continue;
		const start = tokens.start(i);
		const end = tokens.end(i);
		if (end > start) builder.add(start, end, style);
	}
	return builder.finish();
}

/** Round, curly and square brackets that are code. */
const bracketsField = StateField.define<number[]>({
	create: (state) => bracketsOf(state.field(tokensField), state.doc.toString()),
	update: (value, tr) => (tr.docChanged ? bracketsOf(tr.state.field(tokensField), tr.newDoc.toString()) : value),
});

function bracketsOf(tokens: PyTokens, source: string): number[] {
	const found: number[] = [];
	for (let i = 0; i < tokens.size; i++) {
		if (tokens.kind(i) !== PyTokenKind.OPERATOR) continue;
		const start = tokens.start(i);
		if (tokens.end(i) - start === 1 && '(){}[]'.includes(source[start]!)) found.push(start);
	}
	return found;
}

const bracketMark = Decoration.mark({ class: 'wmc-bracket' });

const bracketField = StateField.define<DecorationSet>({
	create: () => Decoration.none,
	update: (value, tr) => {
		if (!tr.docChanged && !tr.selection) return value;
		const brackets = tr.state.field(bracketsField);
		const source = tr.state.field(tokensField).source;
		const pair = E.matchingBracket(source, brackets, tr.state.selection.main.head);
		if (!pair) return Decoration.none;
		const [a, b] = pair;
		return Decoration.set([bracketMark.range(a, a + 1), bracketMark.range(b, b + 1)], true);
	},
	provide: (f) => EditorView.decorations.from(f),
});

/** The fold regions of the text, from tokens, in document order. */
const regionsField = StateField.define<E.Range[]>({
	create: (state) => folds(state.doc.toString()),
	update: (value, tr) => (tr.docChanged ? folds(tr.newDoc.toString()) : value),
});

// ---------------------------------------------------------------------------
// Squiggles, find matches and gutter marks
// ---------------------------------------------------------------------------

const squiggle = { ERROR: Decoration.mark({ class: 'wmc-squiggle wmc-squiggle-error' }), WARNING: Decoration.mark({ class: 'wmc-squiggle wmc-squiggle-warning' }), INFO: Decoration.mark({ class: 'wmc-squiggle wmc-squiggle-info' }) };
const matchMark = Decoration.mark({ class: 'wmc-match' });
const activeMatchMark = Decoration.mark({ class: 'wmc-match wmc-match-active' });

const decorationsField = StateField.define<{ shown: CodeDecorations; set: DecorationSet }>({
	create: () => ({ shown: NO_DECORATIONS, set: Decoration.none }),
	update: (value, tr) => {
		let shown = value.shown;
		for (const e of tr.effects) if (e.is(setDecorations)) shown = e.value;
		if (shown === value.shown && !tr.docChanged) return value;
		if (shown !== value.shown) return { shown, set: decorate(shown, tr.newDoc.length) };
		return { shown, set: value.set.map(tr.changes) };
	},
	provide: (f) => EditorView.decorations.from(f, (v) => v.set),
});

function decorate(d: CodeDecorations, length: number): DecorationSet {
	const ranges: { from: number; to: number; deco: Decoration }[] = [];
	d.matches.forEach((m, index) => {
		const from = Math.min(Math.max(E.rangeMin(m), 0), length);
		const to = Math.min(Math.max(E.rangeMax(m), from), length);
		if (to > from) ranges.push({ from, to, deco: index === d.activeMatch ? activeMatchMark : matchMark });
	});
	for (const s of d.squiggles.slice(0, MAX_SQUIGGLES)) {
		const from = Math.min(Math.max(s.start, 0), length);
		let to = Math.min(Math.max(s.end, from), length);
		if (to === from) to = Math.min(from + 1, length);
		if (to > from) ranges.push({ from, to, deco: squiggle[s.severity] });
	}
	ranges.sort((a, b) => a.from - b.from || a.to - b.to);
	return Decoration.set(ranges.map((r) => r.deco.range(r.from, r.to)), true);
}

class LineMark extends GutterMarker {
	constructor(readonly elementClass: string) {
		super();
	}
}
const errorLine = new LineMark('wmc-line-error');
const warningLine = new LineMark('wmc-line-warning');

const gutterMarksField = StateField.define<RangeSet<GutterMarker>>({
	create: () => RangeSet.empty,
	update: (value, tr) => {
		let shown: CodeDecorations | null = null;
		for (const e of tr.effects) if (e.is(setDecorations)) shown = e.value;
		if (shown === null) return tr.docChanged ? value.map(tr.changes) : value;
		const builder = new RangeSetBuilder<GutterMarker>();
		const doc = tr.newDoc;
		const lines = [...shown.gutterMarks.entries()].filter(([line, sev]) => line >= 0 && line < doc.lines && sev !== 'INFO').sort((a, b) => a[0] - b[0]);
		for (const [line, sev] of lines) {
			const from = doc.line(line + 1).from;
			builder.add(from, from, sev === 'ERROR' ? errorLine : warningLine);
		}
		return builder.finish();
	},
	provide: (f) => gutterLineClass.from(f),
});

// ---------------------------------------------------------------------------
// Folding (CodeFolding.kt over CodeMirror's fold state)
// ---------------------------------------------------------------------------

const pythonFolds = foldService.of((state, lineStart, lineEnd) => {
	const regions = state.field(regionsField, false);
	if (!regions) return null;
	const text = state.field(tokensField).source;
	// The outermost region that starts on this line and has a whole line to hide.
	let best: E.Range | null = null;
	for (const r of regions) {
		if (r.start < lineStart) continue;
		if (r.start > lineEnd) break;
		if (!E.hiddenRangeOf(text, r)) continue;
		if (!best || r.end > best.end) best = r;
	}
	const hidden = best ? E.hiddenRangeOf(text, best) : null;
	return hidden ? { from: hidden.start, to: hidden.end } : null;
});

function chevron(open: boolean): HTMLElement {
	const el = document.createElement('span');
	el.className = 'wmc-chevron' + (open ? '' : ' wmc-chevron-closed');
	el.innerHTML = '<svg viewBox="0 0 10 10" width="10" height="10"><path d="M2 3.5 L5 6.5 L8 3.5" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>';
	return el;
}

// ---------------------------------------------------------------------------
// The controller (CodeEditorState)
// ---------------------------------------------------------------------------

export interface CodeSuggestionBar {
	shown: Signal<PyCompletions | null>;
	choose: (item: PyCompletionItem) => void;
}

export interface SurfaceOptions {
	onCommand: (command: ScreenCommand) => boolean;
	onGutterPress?: (line: number) => void;
	host?: () => PyHost | null;
	suggestionBar?: CodeSuggestionBar;
	/** When the screen closes over the code, the field takes the keys back. */
	completions: boolean;
}

export class CodeEditorController {
	view: EditorView;
	/** Bumped on every change of the text or the selection, for the screen to read the newest through the getters. */
	readonly revision = signal(0);
	readonly canUndo = signal(false);
	readonly canRedo = signal(false);
	readonly suggestions = signal<PyCompletions | null>(null);
	readonly chosen = signal(0);
	readonly compact = signal(false);
	private past: Snapshot[] = [];
	private future: Snapshot[] = [];
	private lastEditAt = 0;
	private seen: string;
	private justChose = false;
	private lineClip: string | null = null;
	private grownFrom: E.Range[] = [];
	private grownTo: E.Range | null = null;
	private wrapComp = new Compartment();
	private sizeComp = new Compartment();
	private focusedFlag = false;

	constructor(parent: HTMLElement, initial: string, private readonly options: SurfaceOptions, wrap: boolean, fontSize: number) {
		this.seen = initial;
		const state = EditorState.create({
			doc: initial,
			extensions: [
				tokensField,
				highlightField,
				bracketsField,
				bracketField,
				regionsField,
				decorationsField,
				gutterMarksField,
				lineNumbers({
					domEventHandlers: {
						mousedown: (view, line) => {
							const press = this.options.onGutterPress;
							if (!press) return false;
							press(view.state.doc.lineAt(line.from).number - 1);
							return true;
						},
					},
				}),
				highlightActiveLineGutter(),
				cmFoldGutter({ markerDOM: chevron }),
				codeFolding({ placeholderText: E.FOLD_PLACEHOLDER }),
				pythonFolds,
				highlightActiveLine(),
				drawSelection(),
				EditorState.allowMultipleSelections.of(false),
				EditorState.tabSize.of(E.CODE_TAB_STOP),
				EditorState.transactionFilter.of((tr) => this.smartEdit(tr)),
				EditorView.updateListener.of((u) => this.onUpdate(u)),
				keymap.of(withMetaAsCtrl(this.bindings())),
				this.wrapComp.of(wrap ? EditorView.lineWrapping : []),
				this.sizeComp.of(sizeTheme(fontSize)),
				baseTheme,
				EditorView.contentAttributes.of({ autocapitalize: 'off', autocorrect: 'off', spellcheck: 'false', 'aria-label': 'Python code' }),
			],
		});
		this.view = new EditorView({ state, parent });
		// For the screen's own tests: the controller behind a field, found from its DOM.
		(this.view.dom as HTMLElement & { wmController?: CodeEditorController }).wmController = this;
		this.view.contentDOM.addEventListener('focus', () => (this.focusedFlag = true));
		this.view.contentDOM.addEventListener('blur', () => (this.focusedFlag = false));
		this.view.contentDOM.addEventListener('keydown', (e) => this.onKeyDown(e), true);
	}

	// ---- reading -------------------------------------------------------------

	get text(): string {
		return this.view.state.field(tokensField).source;
	}

	get tokens(): PyTokens {
		return this.view.state.field(tokensField);
	}

	get selection(): E.Range {
		const s = this.view.state.selection.main;
		return { start: s.anchor, end: s.head };
	}

	get regions(): E.Range[] {
		return this.view.state.field(regionsField);
	}

	get foldStarts(): number[] {
		const folded = foldedRanges(this.view.state);
		const text = this.text;
		const starts: number[] = [];
		folded.between(0, text.length, (from, to) => {
			for (const r of this.regions) {
				const hidden = E.hiddenRangeOf(text, r);
				if (hidden && hidden.start === from && hidden.end === to) {
					starts.push(r.start);
					break;
				}
			}
		});
		return starts;
	}

	get focused(): boolean {
		return this.focusedFlag;
	}

	// ---- history (CodeEditorState) --------------------------------------------

	private snapshot(): Snapshot {
		const s = this.view.state.selection.main;
		return { text: this.text, anchor: s.anchor, head: s.head };
	}

	private record(oldText: string, oldSel: E.Range, newText: string, newHead: number) {
		if (oldText === newText) return;
		const now = Date.now();
		const ch = newText[newHead - 1];
		const typed = newText.length === oldText.length + 1 && ch !== undefined && !/\s/.test(ch);
		if (!(typed && this.past.length > 0 && now - this.lastEditAt < COALESCE_MS)) {
			this.past.push({ text: oldText, anchor: oldSel.start, head: oldSel.end });
			while (this.past.length > UNDO_DEPTH) this.past.shift();
		}
		this.lastEditAt = now;
		this.future = [];
		this.canUndo.value = this.past.length > 0;
		this.canRedo.value = false;
	}

	private push() {
		this.past.push(this.snapshot());
		while (this.past.length > UNDO_DEPTH) this.past.shift();
		this.future = [];
		this.lastEditAt = 0;
		this.canUndo.value = true;
		this.canRedo.value = false;
	}

	private restore(s: Snapshot) {
		const current = this.text;
		const change = minimalChange(current, s.text);
		this.view.dispatch({
			changes: change ? { from: change.from, to: change.to, insert: change.insert } : undefined,
			selection: { anchor: Math.min(s.anchor, s.text.length), head: Math.min(s.head, s.text.length) },
			annotations: historyKind.of('skip'),
			scrollIntoView: true,
		});
	}

	undo() {
		const previous = this.past.pop();
		if (!previous) return;
		this.future.push(this.snapshot());
		this.restore(previous);
		this.lastEditAt = 0;
		this.canUndo.value = this.past.length > 0;
		this.canRedo.value = true;
	}

	redo() {
		const next = this.future.pop();
		if (!next) return;
		this.past.push(this.snapshot());
		this.restore(next);
		this.lastEditAt = 0;
		this.canUndo.value = true;
		this.canRedo.value = this.future.length > 0;
	}

	// ---- edits ------------------------------------------------------------------

	/** Replaces the whole document, as Format and Paste do. One step of history. */
	replace(newText: string) {
		if (newText === this.text) return;
		this.push();
		const change = minimalChange(this.text, newText);
		const caret = Math.min(this.view.state.selection.main.head, newText.length);
		this.view.dispatch({ changes: change ? { from: change.from, to: change.to, insert: change.insert } : undefined, selection: { anchor: caret }, annotations: historyKind.of('skip'), scrollIntoView: true });
	}

	/** One prepared change as one step of history. A change that leaves the text alone only moves the selection. */
	applyEdit(edit: E.CodeTextEdit) {
		const current = this.text;
		const min = Math.min(Math.max(E.rangeMin(edit.range), 0), current.length);
		const max = Math.min(Math.max(E.rangeMax(edit.range), min), current.length);
		const next = current.substring(0, min) + edit.text + current.substring(max);
		const anchor = Math.min(Math.max(edit.selection.start, 0), next.length);
		const head = Math.min(Math.max(edit.selection.end, 0), next.length);
		const sel = this.view.state.selection.main;
		if (next === current && anchor === sel.anchor && head === sel.head) return;
		if (next !== current) this.push();
		this.view.dispatch({ changes: next !== current ? { from: min, to: max, insert: edit.text } : undefined, selection: { anchor, head }, annotations: historyKind.of('skip'), scrollIntoView: true });
	}

	/** The key row's symbols: typed over the selection, so brackets and quotes pair as when typed. */
	type(text: string) {
		const sel = this.view.state.selection.main;
		this.view.dispatch({ changes: { from: sel.from, to: sel.to, insert: text }, selection: { anchor: sel.from + text.length }, userEvent: 'input.type', scrollIntoView: true });
	}

	moveTo(offset: number) {
		const at = Math.min(Math.max(offset, 0), this.text.length);
		this.view.dispatch({ selection: { anchor: at }, scrollIntoView: true });
	}

	select(range: E.Range) {
		const length = this.text.length;
		this.view.dispatch({ selection: { anchor: Math.min(Math.max(range.start, 0), length), head: Math.min(Math.max(range.end, 0), length) }, scrollIntoView: true });
	}

	shiftLines(levels: number, wholeLines = false) {
		const edit = E.shiftLines(this.text, this.selection, levels, wholeLines);
		if (edit) this.applyEdit(edit);
	}

	focus() {
		this.view.focus();
	}

	setWrap(wrap: boolean) {
		this.view.dispatch({ effects: this.wrapComp.reconfigure(wrap ? EditorView.lineWrapping : []) });
	}

	setFontSize(size: number) {
		this.view.dispatch({ effects: this.sizeComp.reconfigure(sizeTheme(size)) });
	}

	setDecorations(d: CodeDecorations) {
		this.view.dispatch({ effects: setDecorations.of(d) });
	}

	// ---- folding ---------------------------------------------------------------

	private hiddenOf(start: number): E.Range | null {
		const text = this.text;
		let best: E.Range | null = null;
		for (const r of this.regions) if (r.start === start && (!best || r.end > best.end)) best = r;
		return best ? E.hiddenRangeOf(text, best) : null;
	}

	toggleFold(start: number) {
		if (this.foldStarts.includes(start)) this.unfold(start);
		else {
			const hidden = this.hiddenOf(start);
			if (hidden) this.view.dispatch({ effects: foldEffect.of({ from: hidden.start, to: hidden.end }) });
		}
	}

	unfold(start: number) {
		const hidden = this.hiddenOf(start);
		if (hidden) this.view.dispatch({ effects: unfoldEffect.of({ from: hidden.start, to: hidden.end }) });
	}

	foldAll() {
		const text = this.text;
		const folds = E.foldsFor(text, this.regions, new Set(this.regions.map((r) => r.start)));
		if (folds.length) this.view.dispatch({ effects: folds.map((f) => foldEffect.of({ from: f.hidden.start, to: f.hidden.end })) });
	}

	unfoldAll() {
		const effects: StateEffect<unknown>[] = [];
		foldedRanges(this.view.state).between(0, this.text.length, (from, to) => {
			effects.push(unfoldEffect.of({ from, to }));
		});
		if (effects.length) this.view.dispatch({ effects });
	}

	// ---- suggestions -------------------------------------------------------------

	private ask(explicit: boolean) {
		if (!this.options.completions) return;
		const found = complete(this.text, this.view.state.selection.main.head, explicit, this.options.host?.() ?? null);
		this.suggestions.value = found && found.items.length ? found : null;
		this.chosen.value = 0;
		this.publish();
	}

	suggest() {
		this.ask(true);
	}

	choose = (item: PyCompletionItem) => {
		const shown = this.suggestions.value;
		if (!shown) return;
		this.suggestions.value = null;
		this.justChose = true;
		const start = E.rangeMin(shown.replace);
		this.applyEdit({ range: shown.replace, text: item.insert, selection: E.rangeAt(start + Math.min(Math.max(item.caret, 0), item.insert.length)) });
		this.publish();
		this.view.focus();
	};

	closeSuggestions() {
		this.suggestions.value = null;
		this.publish();
	}

	private publish() {
		const bar = this.options.suggestionBar;
		if (bar) bar.shown.value = this.compact.value ? this.suggestions.value : null;
	}

	setCompact(compact: boolean) {
		if (this.compact.value === compact) return;
		this.compact.value = compact;
		this.publish();
	}

	// ---- what the view reports -----------------------------------------------------

	private smartEdit(tr: Transaction): TransactionSpec | readonly TransactionSpec[] {
		if (!tr.docChanged || tr.annotation(historyKind) === 'skip') return tr;
		const oldSel = tr.startState.selection.main;
		if (!oldSel.empty) return tr;
		let count = 0;
		let fromA = 0;
		let toA = 0;
		let inserted = '';
		tr.changes.iterChanges((a, b, _c, _d, text) => {
			count++;
			fromA = a;
			toA = b;
			inserted = text.toString();
		});
		if (count !== 1) return tr;
		// Typed input and a DOM-driven deletion both arrive as "input.type"; a key-driven one as "delete".
		if (!tr.isUserEvent('input') && !tr.isUserEvent('delete')) return tr;
		const oldText = tr.startState.doc.toString();
		if (toA === fromA && inserted.length === 1 && fromA === oldSel.head) {
			const smart = E.smartInsert(oldText, fromA, inserted);
			if (!smart) return tr;
			return { changes: smart.text ? { from: E.rangeMin(smart.range), to: E.rangeMax(smart.range), insert: smart.text } : undefined, selection: { anchor: smart.selection.start, head: smart.selection.end }, userEvent: 'input.type', scrollIntoView: true };
		}
		// A backspace-shaped deletion: one character, ending at the caret.
		if (inserted === '' && toA - fromA === 1 && toA === oldSel.head) {
			const smart = E.pairBackspace(oldText, toA);
			if (!smart) return tr;
			return { changes: { from: E.rangeMin(smart.range), to: E.rangeMax(smart.range), insert: '' }, selection: { anchor: smart.selection.start }, userEvent: 'delete.backward', scrollIntoView: true };
		}
		return tr;
	}

	private onUpdate(u: ViewUpdate) {
		if (u.docChanged) {
			const kind = u.transactions.map((t) => t.annotation(historyKind)).find((k) => k !== undefined);
			if (kind === undefined) {
				const old = u.startState;
				this.record(old.doc.toString(), { start: old.selection.main.anchor, end: old.selection.main.head }, u.state.doc.toString(), u.state.selection.main.head);
			}
		}
		if (u.docChanged || u.selectionSet) {
			this.revision.value++;
			// A caret or a selection that lands in folded text opens the fold.
			const sel = u.state.selection.main;
			const effects: StateEffect<unknown>[] = [];
			foldedRanges(u.state).between(0, u.state.doc.length, (from, to) => {
				if ((sel.from > from && sel.from < to) || (sel.to > from && sel.to < to)) effects.push(unfoldEffect.of({ from, to }));
			});
			if (effects.length) queueMicrotask(() => this.view.dispatch({ effects, scrollIntoView: true }));
			this.completionStep(u);
		}
	}

	/** Typing asks; deleting asks again only while a list is open; moving the caret keeps a list only inside its word. */
	private completionStep(u: ViewUpdate) {
		const before = this.seen;
		const text = u.state.doc.toString();
		this.seen = text;
		if (!this.options.completions || this.justChose) {
			this.justChose = false;
			if (this.suggestions.value) {
				this.suggestions.value = null;
				this.publish();
			}
			return;
		}
		const caret = u.state.selection.main.head;
		const shown = this.suggestions.value;
		if (text.length > before.length) this.ask(false);
		else if (text !== before) {
			if (shown) this.ask(false);
			else this.closeSuggestions();
		} else if (shown && caret >= E.rangeMin(shown.replace) && caret <= E.rangeMax(shown.replace)) {
			/* keep */
		} else if (shown) this.closeSuggestions();
	}

	/** The list's own keys, before CodeMirror sees them. */
	private onKeyDown(e: KeyboardEvent) {
		const shown = this.suggestions.value;
		if (!shown || !shown.items.length || this.compact.value) return;
		const size = shown.items.length;
		switch (e.key) {
			case 'ArrowDown': this.chosen.value = (this.chosen.value + 1) % size; break;
			case 'ArrowUp': this.chosen.value = (this.chosen.value - 1 + size) % size; break;
			case 'Enter':
			case 'Tab': {
				const item = shown.items[this.chosen.value];
				if (item) this.choose(item);
				break;
			}
			case 'Escape': this.closeSuggestions(); break;
			default: return;
		}
		e.preventDefault();
		e.stopPropagation();
	}

	// ---- keys (CodeShortcuts.kt) --------------------------------------------------------

	private screen(command: ScreenCommand): boolean {
		return this.options.onCommand(command);
	}

	private bindings(): KeyBinding[] {
		const edit = (f: () => E.CodeTextEdit | null) => () => {
			const e = f();
			if (e) this.applyEdit(e);
			return true;
		};
		const moveCaret = (to: number, select: boolean) => {
			const sel = this.view.state.selection.main;
			this.select(select ? { start: sel.anchor, end: to } : { start: to, end: to });
			return true;
		};
		const scrollLine = (delta: number) => () => {
			this.view.scrollDOM.scrollTop += delta * this.view.defaultLineHeight;
			return true;
		};
		const text = () => this.text;
		const sel = () => this.selection;
		const caret = () => Math.min(Math.max(this.view.state.selection.main.head, 0), this.text.length);
		return [
			{ key: 'Tab', run: () => (E.tabShiftsLines(text(), sel()) ? (this.shiftLines(1), true) : (this.applyEdit(E.insertIndent(text(), sel())), true)) },
			{ key: 'Shift-Tab', run: () => (this.shiftLines(-1), true) },
			{ key: 'Mod-]', run: () => (this.shiftLines(1, true), true) },
			{ key: 'Mod-[', run: () => (this.shiftLines(-1), true) },
			{ key: 'Mod-z', run: () => (this.undo(), true) },
			{ key: 'Mod-y', run: () => (this.redo(), true) },
			{ key: 'Mod-Shift-z', run: () => (this.redo(), true) },
			{ key: 'Mod-/', run: edit(() => E.toggleLineComment(text(), sel(), E.PY_LINE_COMMENT)) },
			{ key: 'Alt-ArrowUp', run: edit(() => E.moveLines(text(), sel(), -1)) },
			{ key: 'Alt-ArrowDown', run: edit(() => E.moveLines(text(), sel(), 1)) },
			{ key: 'Shift-Alt-ArrowUp', run: edit(() => E.copyLines(text(), sel(), true)) },
			{ key: 'Shift-Alt-ArrowDown', run: edit(() => E.copyLines(text(), sel(), false)) },
			{ key: 'Mod-Shift-k', run: edit(() => E.deleteLines(text(), sel())) },
			{ key: 'Mod-Enter', run: edit(() => E.insertLine(text(), sel(), false)) },
			{ key: 'Mod-Shift-Enter', run: edit(() => E.insertLine(text(), sel(), true)) },
			{ key: 'Mod-x', run: () => this.cutCopy(true) },
			{ key: 'Mod-c', run: () => this.cutCopy(false) },
			{ key: 'Mod-v', run: () => this.pasteLine() },
			{ key: 'Home', run: () => moveCaret(E.smartHome(text(), caret()), false) },
			{ key: 'Shift-Home', run: () => moveCaret(E.smartHome(text(), caret()), true) },
			{ key: 'Mod-Home', run: () => moveCaret(0, false) },
			{ key: 'Mod-Shift-Home', run: () => moveCaret(0, true) },
			{ key: 'Mod-End', run: () => moveCaret(text().length, false) },
			{ key: 'Mod-Shift-End', run: () => moveCaret(text().length, true) },
			{ key: 'PageUp', run: cursorPageUp, shift: selectPageUp },
			{ key: 'PageDown', run: cursorPageDown, shift: selectPageDown },
			{ key: 'Mod-ArrowUp', run: scrollLine(-1) },
			{ key: 'Mod-ArrowDown', run: scrollLine(1) },
			{ key: 'Mod-l', run: () => (this.select(E.expandLineSelection(text(), sel())), true) },
			{
				key: 'Shift-Alt-ArrowRight',
				run: () => {
					const current = sel();
					const next = E.expandSelection(text(), current, this.regions);
					if (!next) return true;
					if (!sameRange(this.grownTo, current)) this.grownFrom = [];
					this.grownFrom.push(current);
					this.grownTo = next;
					this.select(next);
					return true;
				},
			},
			{
				key: 'Shift-Alt-ArrowLeft',
				run: () => {
					const current = sel();
					if (sameRange(this.grownTo, current) && this.grownFrom.length) {
						const back = this.grownFrom.pop()!;
						this.grownTo = back;
						this.select(back);
					}
					return true;
				},
			},
			{
				key: 'Mod-Shift-\\',
				run: () => {
					const brackets = this.view.state.field(bracketsField);
					const to = E.bracketJump(text(), brackets, caret(), (at) => E.matchingBracket(text(), brackets, at));
					if (to !== null) this.moveTo(to);
					return true;
				},
			},
			{
				key: 'Mod-Shift-[',
				run: () => {
					const region = E.foldToClose(text(), this.regions, new Set(this.foldStarts), caret());
					if (!region) return true;
					const hidden = E.hiddenRangeOf(text(), region);
					const s = sel();
					if (hidden && E.rangeMax(s) > hidden.start && E.rangeMin(s) < hidden.end) this.moveTo(hidden.start);
					this.toggleFold(region.start);
					return true;
				},
			},
			{
				key: 'Mod-Shift-]',
				run: () => {
					const start = E.foldToOpen(text(), this.regions, new Set(this.foldStarts), caret());
					if (start !== null) this.unfold(start);
					return true;
				},
			},
			{ key: 'Mod-k Mod-0', run: () => (this.foldAll(), true) },
			{ key: 'Mod-k Mod-j', run: () => (this.unfoldAll(), true) },
			{ key: 'Mod-Space', run: () => (this.suggest(), true) },
			{ key: 'Mod-i', run: () => (this.suggest(), true) },
			{ key: 'Mod-f', run: () => this.screen('FIND') },
			{ key: 'Mod-h', run: () => this.screen('REPLACE') },
			{ key: 'F3', run: () => this.screen('FIND_NEXT'), shift: () => this.screen('FIND_PREVIOUS') },
			{ key: 'Mod-g', run: () => this.screen('GO_TO_LINE') },
			{ key: 'F12', run: () => this.screen('GO_TO_DEFINITION') },
			{ key: 'F2', run: () => this.screen('RENAME') },
			{ key: 'Mod-Shift-o', run: () => this.screen('GO_TO_SYMBOL') },
			{ key: 'Shift-Alt-f', run: () => this.screen('FORMAT') },
			{ key: 'Alt-z', run: () => this.screen('TOGGLE_WRAP') },
			{ key: 'Mod-=', run: () => this.screen('ZOOM_IN') },
			{ key: 'Mod-Shift-=', run: () => this.screen('ZOOM_IN') },
			{ key: 'Mod-+', run: () => this.screen('ZOOM_IN') },
			{ key: 'Mod--', run: () => this.screen('ZOOM_OUT') },
			{ key: 'Mod-0', run: () => this.screen('ZOOM_RESET') },
			{ key: 'Mod-s', run: () => this.screen('SAVE') },
			{ key: 'F5', run: () => this.screen('RUN'), shift: () => this.screen('STOP') },
			{ key: 'F8', run: () => this.screen('NEXT_PROBLEM'), shift: () => this.screen('PREVIOUS_PROBLEM') },
			{ key: 'Mod-Shift-m', run: () => this.screen('SHOW_PROBLEMS') },
			{ key: 'Mod-Shift-y', run: () => this.screen('SHOW_CONSOLE') },
			{ key: 'F1', run: () => this.screen('SHOW_COMMANDS') },
			{ key: 'Mod-Shift-p', run: () => this.screen('SHOW_COMMANDS') },
			{ key: 'Escape', run: () => (this.screen('ESCAPE'), true) },
			// Enter is typed through the smart edit above, which carries the indentation on.
			{ key: 'Enter', run: () => (this.type('\n'), true), shift: () => (this.type('\n'), true) },
		];
	}

	private cutCopy(cut: boolean): boolean {
		const sel = this.view.state.selection.main;
		if (!sel.empty) {
			this.lineClip = null;
			return false;
		}
		const clip = E.lineText(this.text, sel.head);
		this.lineClip = clip;
		void navigator.clipboard?.writeText(clip).catch(() => {});
		if (cut) this.applyEdit(E.deleteLines(this.text, this.selection));
		return true;
	}

	private pasteLine(): boolean {
		const clip = this.lineClip;
		const sel = this.view.state.selection.main;
		if (clip === null || !sel.empty || !navigator.clipboard?.readText) return false;
		navigator.clipboard
			.readText()
			.then((pasted) => {
				const now = this.view.state.selection.main;
				if (pasted === clip && now.empty) this.applyEdit(E.pasteLines(this.text, now.head, clip));
				else this.view.dispatch({ changes: { from: now.from, to: now.to, insert: pasted }, selection: { anchor: now.from + pasted.length }, userEvent: 'input.paste', scrollIntoView: true });
			})
			.catch(() => {});
		return true;
	}

	destroy() {
		this.view.destroy();
	}
}

/**
 * The app folds Meta into Ctrl, for keyboards made for a Mac; so does the web:
 * every `Mod-` binding answers to both Ctrl and Meta on every platform.
 */
function withMetaAsCtrl(bindings: KeyBinding[]): KeyBinding[] {
	const out: KeyBinding[] = [];
	for (const b of bindings) {
		if (!b.key || !b.key.includes('Mod-')) {
			out.push(b);
			continue;
		}
		out.push({ ...b, key: b.key.replaceAll('Mod-', 'Ctrl-') });
		out.push({ ...b, key: b.key.replaceAll('Mod-', 'Meta-') });
	}
	return out;
}

function sameRange(a: E.Range | null, b: E.Range): boolean {
	return !!a && a.start === b.start && a.end === b.end;
}

/** The one change that turns `from` into `to`, by common prefix and suffix, or null for equal texts. */
export function minimalChange(from: string, to: string): { from: number; to: number; insert: string } | null {
	if (from === to) return null;
	const shortest = Math.min(from.length, to.length);
	let prefix = 0;
	while (prefix < shortest && from[prefix] === to[prefix]) prefix++;
	let suffix = 0;
	while (suffix < shortest - prefix && from[from.length - 1 - suffix] === to[to.length - 1 - suffix]) suffix++;
	return { from: prefix, to: from.length - suffix, insert: to.substring(prefix, to.length - suffix) };
}

const LINE_HEIGHT_RATIO = 22 / 14;

function sizeTheme(fontSize: number): Extension {
	return EditorView.theme({
		'&': { fontSize: `${fontSize}px` },
		'.cm-content, .cm-gutter': { lineHeight: `${(fontSize * LINE_HEIGHT_RATIO).toFixed(2)}px` },
	});
}

const baseTheme = EditorView.theme({
	'&': { height: '100%', backgroundColor: 'var(--wmc-background)', color: 'var(--wmc-text)' },
	'.cm-scroller': { fontFamily: 'var(--wmc-mono)', overflow: 'auto' },
	'.cm-content': { padding: '10px 0', caretColor: 'var(--wmc-caret)' },
	'.cm-line': { padding: '0 10px' },
	'&.cm-focused': { outline: 'none' },
	'.cm-cursor, .cm-dropCursor': { borderLeftColor: 'var(--wmc-caret)', borderLeftWidth: '2px' },
	'&.cm-focused > .cm-scroller > .cm-selectionLayer .cm-selectionBackground, .cm-selectionBackground': { backgroundColor: 'var(--wmc-selection) !important' },
	'.cm-activeLine': { backgroundColor: 'var(--wmc-active-line)' },
	'.cm-gutters': { backgroundColor: 'var(--wmc-gutter)', color: 'var(--wmc-gutter-text)', borderRight: '1px solid var(--wmc-border)', paddingTop: '10px' },
	'.cm-lineNumbers .cm-gutterElement': { padding: '0 10px', minWidth: '2.5em' },
	'.cm-activeLineGutter': { backgroundColor: 'transparent', color: 'var(--wmc-gutter-active-text)', fontWeight: '500' },
	'.cm-foldGutter .cm-gutterElement': { width: '14px', padding: '0', display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', color: 'var(--wmc-fold-mark)' },
	'.cm-foldPlaceholder': { backgroundColor: 'transparent', border: 'none', color: 'var(--wmc-fold-mark)', padding: '0', margin: '0' },
});

// ---------------------------------------------------------------------------
// The component
// ---------------------------------------------------------------------------

export interface CodeSurfaceProps {
	controller: Signal<CodeEditorController | null>;
	initial: string;
	options: SurfaceOptions;
	wrap: boolean;
	fontSize: number;
	class?: string;
}

export function CodeSurface({ controller, initial, options, wrap, fontSize, class: cls }: CodeSurfaceProps) {
	const host = useRef<HTMLDivElement>(null);
	const optionsRef = useRef(options);
	optionsRef.current = options;
	useEffect(() => {
		if (!host.current) return;
		const c = new CodeEditorController(host.current, initial, { ...options, onCommand: (cmd) => optionsRef.current.onCommand(cmd), onGutterPress: (line) => optionsRef.current.onGutterPress?.(line), host: () => optionsRef.current.host?.() ?? null }, wrap, fontSize);
		controller.value = c;
		const frame = host.current;
		const observer = new ResizeObserver(() => {
			const lineHeight = fontSize * LINE_HEIGHT_RATIO;
			c.setCompact(!!optionsRef.current.suggestionBar && frame.clientHeight > 0 && frame.clientHeight < lineHeight * COMPACT_LINES + 20);
		});
		observer.observe(frame);
		return () => {
			observer.disconnect();
			controller.value = null;
			c.destroy();
		};
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, []);
	useEffect(() => {
		controller.value?.setWrap(wrap);
	}, [wrap, controller]);
	useEffect(() => {
		controller.value?.setFontSize(fontSize);
	}, [fontSize, controller]);
	return (
		<div class={`wmc-surface ${cls ?? ''}`} style={{ '--wmc-font-size': `${fontSize}px` }}>
			<div ref={host} class="wmc-host" />
			<CompletionList controller={controller} host={host} />
		</div>
	);
}

/** The suggestion list, drawn inside the field's frame at the caret: below it when it fits, above it otherwise. */
function CompletionList({ controller, host }: { controller: Signal<CodeEditorController | null>; host: { current: HTMLDivElement | null } }) {
	const c = controller.value;
	const shown = c?.suggestions.value ?? null;
	const chosen = c?.chosen.value ?? 0;
	const compact = c?.compact.value ?? false;
	const list = useRef<HTMLDivElement>(null);
	const [pos, setPos] = useState<{ x: number; y: number } | null>(null);
	useEffect(() => {
		if (!c || !shown || compact || !host.current) {
			setPos(null);
			return;
		}
		const place = () => {
			const view = c.view;
			const frame = host.current!.getBoundingClientRect();
			const coords = view.coordsAtPos(view.state.selection.main.head);
			const size = list.current?.getBoundingClientRect() ?? { width: 200, height: 36 * Math.min(shown.items.length, 5) };
			if (!coords) return;
			const below = coords.bottom - frame.top;
			const above = coords.top - frame.top - size.height;
			const y = below + size.height <= frame.height || above < 0 ? below : above;
			const x = Math.min(Math.max(coords.left - frame.left, 0), Math.max(0, frame.width - size.width));
			setPos({ x, y });
		};
		place();
		const view = c.view;
		view.scrollDOM.addEventListener('scroll', place);
		return () => view.scrollDOM.removeEventListener('scroll', place);
	}, [c, shown, compact, host, c?.revision.value]);
	useEffect(() => {
		const el = list.current?.children[chosen] as HTMLElement | undefined;
		el?.scrollIntoView({ block: 'nearest' });
	}, [chosen]);
	if (!c || !shown || compact || !pos) return null;
	return (
		<div ref={list} class="wmc-completions" role="listbox" style={{ left: `${pos.x}px`, top: `${pos.y}px` }} onMouseDown={(e) => e.preventDefault()}>
			{shown.items.map((item, index) => (
				<div key={index} role="option" aria-selected={index === chosen} class={`wmc-completion ${index === chosen ? 'wmc-chosen' : ''}`} onClick={() => c.choose(item)}>
					<span class={`wmc-kind wmc-kind-${kindClass(item.kind)}`} aria-hidden="true">{kindMark(item.kind)}</span>
					<span class="wmc-label">{item.label}</span>
					{item.detail && <span class="wmc-detail">{item.detail}</span>}
				</div>
			))}
		</div>
	);
}

/** A mark in code characters rather than words. */
function kindMark(kind: PyCompletionKind): string {
	switch (kind) {
		case 'variable':
		case 'parameter': return 'x';
		case 'function':
		case 'method': return 'f';
		case 'field': return '.';
		case 'module': return '{}';
		case 'constant': return '=';
		case 'keyword': return 'k';
		case 'snippet': return '<>';
		case 'value': return '""';
	}
}

function kindClass(kind: PyCompletionKind): string {
	switch (kind) {
		case 'variable':
		case 'parameter': return 'variable';
		case 'function':
		case 'method': return 'function';
		default: return kind;
	}
}

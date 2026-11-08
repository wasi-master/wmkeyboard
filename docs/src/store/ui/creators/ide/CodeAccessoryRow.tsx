/**
 * The row of keys above the soft keyboard (CodeAccessoryRow.kt, CodeKeys.kt):
 * an indent key, the symbols or the editing commands, a page key, and two caret
 * keys that turn into a trackpad under a sliding finger.
 */
import { useEffect, useRef, useState } from 'preact/hooks';
import type { Signal } from '@preact/signals';
import * as E from './edits';
import type { PyCompletions, PyCompletionItem } from '../../../lib/python';
import type { CodeEditorController } from './CodeSurface';
import { S } from './strings';

export interface AccessoryKey {
	label: string;
	insert: string;
	held?: string;
}

/**
 * The symbol keys, in the order a Python author reaches for them: what a phone
 * keyboard hides behind a layer switch first, then the keywords (PythonAccessoryKeys).
 */
export const PYTHON_ACCESSORY_KEYS: AccessoryKey[] = [
	{ label: '=', insert: '=', held: '==' },
	{ label: '(', insert: '(' },
	{ label: ')', insert: ')' },
	{ label: ':', insert: ':' },
	{ label: '"', insert: '"' },
	{ label: ',', insert: ',' },
	{ label: '.', insert: '.' },
	{ label: '[', insert: '[' },
	{ label: ']', insert: ']' },
	{ label: '{', insert: '{' },
	{ label: '}', insert: '}' },
	{ label: '\'', insert: '\'' },
	{ label: '#', insert: '# ' },
	{ label: '!=', insert: '!=' },
	{ label: '<', insert: '<', held: '<=' },
	{ label: '>', insert: '>', held: '>=' },
	{ label: '+', insert: '+', held: '+=' },
	{ label: '-', insert: '-', held: '-=' },
	{ label: '*', insert: '*', held: '**' },
	{ label: '/', insert: '/', held: '//' },
	{ label: '%', insert: '%' },
	{ label: '_', insert: '_' },
	{ label: 'def', insert: 'def ' },
	{ label: 'return', insert: 'return ' },
	{ label: 'if', insert: 'if ' },
	{ label: 'elif', insert: 'elif ' },
	{ label: 'else', insert: 'else:' },
	{ label: 'for', insert: 'for ' },
	{ label: 'in', insert: 'in ' },
	{ label: 'while', insert: 'while ' },
	{ label: 'global', insert: 'global ' },
	{ label: 'None', insert: 'None' },
	{ label: 'True', insert: 'True' },
	{ label: 'False', insert: 'False' },
	{ label: 'and', insert: 'and ' },
	{ label: 'or', insert: 'or ' },
	{ label: 'not', insert: 'not ' },
];

/** `keys` in the saved `order`, then any key the order does not name, the hidden ones left out. */
export function arrangeKeys(keys: AccessoryKey[], order: string[], hidden: Set<string>): AccessoryKey[] {
	const byLabel = new Map(keys.map((k) => [k.label, k]));
	const placed: AccessoryKey[] = [];
	const named = new Set<string>();
	for (const label of order) {
		const key = byLabel.get(label);
		if (key && !named.has(label)) {
			placed.push(key);
			named.add(label);
		}
	}
	return [...placed, ...keys.filter((k) => !named.has(k.label))].filter((k) => !hidden.has(k.label));
}

const LONG_PRESS_MS = 400;
const CHARACTER_TRAVEL = 12;
const LINE_TRAVEL = 24;

function useLongPress(onClick: () => void, onLongClick?: () => void) {
	const timer = useRef<number | null>(null);
	const fired = useRef(false);
	const down = () => {
		fired.current = false;
		if (!onLongClick) return;
		timer.current = window.setTimeout(() => {
			fired.current = true;
			onLongClick();
		}, LONG_PRESS_MS);
	};
	const up = () => {
		if (timer.current !== null) window.clearTimeout(timer.current);
		timer.current = null;
		if (!fired.current) onClick();
		fired.current = false;
	};
	const cancel = () => {
		if (timer.current !== null) window.clearTimeout(timer.current);
		timer.current = null;
		fired.current = false;
	};
	return { onPointerDown: down, onPointerUp: up, onPointerCancel: cancel, onPointerLeave: cancel, onContextMenu: (e: Event) => e.preventDefault() };
}

function TextKey({ label, onClick, onLongClick }: { label: string; onClick: () => void; onLongClick?: () => void }) {
	const press = useLongPress(onClick, onLongClick);
	return (
		<button type="button" class="wm-key" {...press} onMouseDown={(e) => e.preventDefault()}>
			{label}
		</button>
	);
}

function CaretKey({ controller, direction, label }: { controller: CodeEditorController; direction: -1 | 1; label: string }) {
	const state = useRef({ dx: 0, dy: 0, slid: false, id: -1 });
	const step = () => controller.moveTo(E.caretStep(controller.text, controller.selection.end, direction, false));
	return (
		<button
			type="button"
			class="wm-key wm-key-icon"
			aria-label={label}
			title={label}
			onMouseDown={(e) => e.preventDefault()}
			onPointerDown={(e) => {
				state.current = { dx: 0, dy: 0, slid: false, id: e.pointerId };
				(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
			}}
			onPointerMove={(e) => {
				const s = state.current;
				if (s.id !== e.pointerId || !(e.buttons & 1)) return;
				s.dx += e.movementX;
				s.dy += e.movementY;
				while (Math.abs(s.dx) >= CHARACTER_TRAVEL) {
					const sideways = Math.sign(s.dx) as -1 | 1;
					controller.moveTo(E.caretStep(controller.text, controller.selection.end, sideways, false));
					s.dx -= sideways * CHARACTER_TRAVEL;
					s.slid = true;
				}
				while (Math.abs(s.dy) >= LINE_TRAVEL) {
					const vertical = Math.sign(s.dy);
					controller.moveTo(E.caretLines(controller.text, controller.selection.end, vertical));
					s.dy -= vertical * LINE_TRAVEL;
					s.slid = true;
				}
			}}
			onPointerUp={(e) => {
				if (state.current.id !== e.pointerId) return;
				if (!state.current.slid) step();
				state.current.id = -1;
			}}
			onPointerCancel={() => (state.current.id = -1)}
		>
			<svg viewBox="0 0 24 24" width="20" height="20">{direction < 0 ? <path d="M15 6l-6 6 6 6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /> : <path d="M9 6l6 6-6 6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />}</svg>
		</button>
	);
}

export function CodeAccessoryRow({ controller, keys, onFind, onFormat, onSuggest, suggestions }: {
	controller: CodeEditorController;
	keys: AccessoryKey[];
	onFind: () => void;
	onFormat: () => void;
	onSuggest: () => void;
	/** Suggestions the field hands over when it is too short to list them at the caret. */
	suggestions: Signal<PyCompletions | null>;
}) {
	const [page, setPage] = useState<'SYMBOLS' | 'COMMANDS'>('SYMBOLS');
	const offered = suggestions.value?.items ?? [];
	const indent = useLongPress(() => controller.shiftLines(1), () => controller.shiftLines(-1));
	const strip = useRef<HTMLDivElement>(null);
	useEffect(() => {
		strip.current?.scrollTo({ left: 0 });
	}, [page, offered.length > 0]);
	const commands: { label: string; run: () => void }[] = [
		{ label: S.commandSuggest, run: onSuggest },
		{ label: S.commandUndo, run: () => controller.undo() },
		{ label: S.commandRedo, run: () => controller.redo() },
		{ label: S.commandComment, run: () => controller.applyEdit(E.toggleLineComment(controller.text, controller.selection, E.PY_LINE_COMMENT)) },
		{ label: S.commandDuplicate, run: () => controller.applyEdit(E.duplicateLines(controller.text, controller.selection)) },
		{ label: S.commandLineUp, run: () => { const e = E.moveLines(controller.text, controller.selection, -1); if (e) controller.applyEdit(e); } },
		{ label: S.commandLineDown, run: () => { const e = E.moveLines(controller.text, controller.selection, 1); if (e) controller.applyEdit(e); } },
		{ label: S.commandSelectWord, run: () => controller.select(E.wordRangeAt(controller.text, controller.selection.end)) },
		{ label: S.commandSelectLine, run: () => controller.select(E.lineRangeAt(controller.text, controller.selection.end)) },
		{ label: S.commandBlockStart, run: () => { const b = E.blockAround(controller.regions, controller.selection.end); if (b) controller.moveTo(b.start); } },
		{ label: S.commandBlockEnd, run: () => { const b = E.blockAround(controller.regions, controller.selection.end); if (b) controller.moveTo(b.end); } },
		{ label: S.commandFind, run: onFind },
		{ label: S.commandFormat, run: onFormat },
	];
	const choose = (item: PyCompletionItem) => controller.choose(item);
	return (
		<div class="wm-keys" role="toolbar">
			<button type="button" class="wm-key wm-key-icon" aria-label={S.keyIndentDesc} title={S.keyIndentDesc} {...indent} onMouseDown={(e) => e.preventDefault()}>
				<svg viewBox="0 0 24 24" width="20" height="20"><path d="M3 5h18M11 9h10M11 13h10M3 17h18M3 9l4 3-4 3" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg>
			</button>
			<div ref={strip} class="wm-keys-strip">
				{offered.length > 0
					? offered.map((item, i) => <TextKey key={i} label={item.label} onClick={() => choose(item)} />)
					: page === 'SYMBOLS'
						? keys.map((k) => <TextKey key={k.label} label={k.label} onClick={() => controller.type(k.insert)} onLongClick={k.held ? () => controller.type(k.held!) : undefined} />)
						: commands.map((c) => <TextKey key={c.label} label={c.label} onClick={c.run} />)}
			</div>
			<button type="button" class="wm-key wm-key-icon" aria-label={S.keysPageDesc} title={S.keysPageDesc} onMouseDown={(e) => e.preventDefault()} onClick={() => setPage(page === 'SYMBOLS' ? 'COMMANDS' : 'SYMBOLS')}>
				<svg viewBox="0 0 24 24" width="20" height="20"><circle cx="6" cy="12" r="1.8" fill="currentColor" /><circle cx="12" cy="12" r="1.8" fill="currentColor" /><circle cx="18" cy="12" r="1.8" fill="currentColor" /></svg>
			</button>
			<CaretKey controller={controller} direction={-1} label={S.keyLeftDesc} />
			<CaretKey controller={controller} direction={1} label={S.keyRightDesc} />
		</div>
	);
}

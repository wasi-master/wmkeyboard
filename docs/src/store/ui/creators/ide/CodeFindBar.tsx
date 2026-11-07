/** The find bar (CodeFindBar.kt): the query, the count, the three options and the replace row. */
import { useEffect, useRef, useState } from 'preact/hooks';
import { findPattern, type CodeFindOptions, type Range } from './edits';
import { S } from './strings';
import type { ScreenCommand } from './CodeSurface';

export interface CodeFindState {
	query: string;
	replacement: string;
	options: CodeFindOptions;
	replacing: boolean;
	/** The match that is showing, as an index into the matches. */
	active: number;
}

export const initialFind = (): CodeFindState => ({ query: '', replacement: '', options: { regex: false, caseSensitive: false, wholeWord: false }, replacing: false, active: 0 });

/** The screen commands a key names while the bar has the keys, as codeCommandFor would. */
function commandOf(e: KeyboardEvent): ScreenCommand | 'FIND' | 'REPLACE' | 'FIND_NEXT' | 'FIND_PREVIOUS' | null {
	const ctrl = e.ctrlKey || e.metaKey;
	const key = e.key.length === 1 ? e.key.toLowerCase() : e.key;
	if (ctrl && !e.shiftKey && !e.altKey) {
		switch (key) {
			case 'f': return 'FIND';
			case 'h': return 'REPLACE';
			case 'g': return 'GO_TO_LINE';
			case 's': return 'SAVE';
			case '=': case '+': return 'ZOOM_IN';
			case '-': return 'ZOOM_OUT';
			case '0': return 'ZOOM_RESET';
		}
	}
	if (ctrl && e.shiftKey && !e.altKey) {
		switch (key) {
			case 'o': return 'GO_TO_SYMBOL';
			case 'm': return 'SHOW_PROBLEMS';
			case 'y': return 'SHOW_CONSOLE';
			case 'p': return 'SHOW_COMMANDS';
			case '=': case '+': return 'ZOOM_IN';
		}
	}
	if (!ctrl && !e.altKey) {
		if (key === 'F3') return e.shiftKey ? 'FIND_PREVIOUS' : 'FIND_NEXT';
		if (key === 'F5') return e.shiftKey ? 'STOP' : 'RUN';
		if (key === 'F8') return e.shiftKey ? 'PREVIOUS_PROBLEM' : 'NEXT_PROBLEM';
		if (key === 'F12' && !e.shiftKey) return 'GO_TO_DEFINITION';
		if (key === 'F2' && !e.shiftKey) return 'RENAME';
		if (key === 'F1' && !e.shiftKey) return 'SHOW_COMMANDS';
	}
	if (!ctrl && e.altKey && e.shiftKey && key === 'f') return 'FORMAT';
	if (!ctrl && e.altKey && !e.shiftKey && key === 'z') return 'TOGGLE_WRAP';
	return null;
}

export function CodeFindBar({ find, setFind, matches, onStep, onReplace, onReplaceAll, onClose, onEscape, onCommand, focusRequests }: {
	find: CodeFindState;
	setFind: (next: CodeFindState) => void;
	matches: Range[];
	onStep: (delta: number) => void;
	onReplace: () => void;
	onReplaceAll: () => void;
	onClose: () => void;
	onEscape: () => void;
	onCommand: (command: ScreenCommand) => boolean;
	focusRequests: number;
}) {
	const invalid = find.options.regex && find.query.length > 0 && findPattern(find.query, find.options) === null;
	const queryRef = useRef<HTMLInputElement>(null);
	const replaceRef = useRef<HTMLInputElement>(null);
	const [inReplace, setInReplace] = useState(false);
	const [replaceRequests, setReplaceRequests] = useState(0);
	useEffect(() => {
		queryRef.current?.focus();
		queryRef.current?.select();
	}, [focusRequests]);
	useEffect(() => {
		if (replaceRequests === 0) return;
		requestAnimationFrame(() => replaceRef.current?.focus());
	}, [replaceRequests]);
	const count = find.query === '' || invalid ? '' : matches.length === 0 ? S.findNone : S.findCount(Math.min(Math.max(find.active, 0), matches.length - 1) + 1, matches.length);
	const option = (patch: Partial<CodeFindOptions>) => setFind({ ...find, options: { ...find.options, ...patch }, active: 0 });
	const onKeyDown = (e: KeyboardEvent) => {
		const ctrl = e.ctrlKey || e.metaKey;
		const enter = e.key === 'Enter';
		const alt = e.altKey && !ctrl && !e.shiftKey;
		const key = e.key.length === 1 ? e.key.toLowerCase() : e.key;
		if (e.key === 'Escape') onEscape();
		else if (enter && ctrl && e.altKey) onReplaceAll();
		else if (enter && !ctrl && !e.altKey) {
			if (inReplace && !e.shiftKey) onReplace();
			else onStep(e.shiftKey ? -1 : 1);
		} else if (alt && key === 'c') option({ caseSensitive: !find.options.caseSensitive });
		else if (alt && key === 'w') option({ wholeWord: !find.options.wholeWord });
		else if (alt && key === 'r') option({ regex: !find.options.regex });
		else {
			const command = commandOf(e);
			if (command === 'FIND') queryRef.current?.focus();
			else if (command === 'REPLACE') {
				setFind({ ...find, replacing: true });
				setReplaceRequests((n) => n + 1);
			} else if (command === 'FIND_NEXT') onStep(1);
			else if (command === 'FIND_PREVIOUS') onStep(-1);
			else if (command === null) return;
			else if (!onCommand(command)) return;
		}
		e.preventDefault();
		e.stopPropagation();
	};
	return (
		<div class="wm-find" onKeyDown={onKeyDown}>
			<div class="wm-find-row">
				<input ref={queryRef} class={`st-input wm-find-input ${invalid ? 'wm-find-invalid' : ''}`} type="search" placeholder={S.findLabel} value={find.query} autocapitalize="off" autocorrect="off" spellcheck={false} onInput={(e) => setFind({ ...find, query: (e.target as HTMLInputElement).value, active: 0 })} />
				<span class="wm-find-count">{count}</span>
				<button class="st-icon-btn" type="button" title={S.findPreviousDesc} aria-label={S.findPreviousDesc} disabled={!matches.length} onClick={() => onStep(-1)}>
					<svg viewBox="0 0 24 24" width="18" height="18"><path d="M7 14l5-5 5 5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg>
				</button>
				<button class="st-icon-btn" type="button" title={S.findNextDesc} aria-label={S.findNextDesc} disabled={!matches.length} onClick={() => onStep(1)}>
					<svg viewBox="0 0 24 24" width="18" height="18"><path d="M7 10l5 5 5-5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg>
				</button>
				<button class="st-icon-btn" type="button" title={S.findCloseDesc} aria-label={S.findCloseDesc} onClick={onClose}>
					<svg viewBox="0 0 24 24" width="18" height="18"><path d="M6 6l12 12M18 6L6 18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" /></svg>
				</button>
			</div>
			<div class="wm-find-row wm-find-options">
				<button type="button" class={`st-chip ${find.options.caseSensitive ? 'st-chip-on' : ''}`} aria-pressed={find.options.caseSensitive} aria-label={S.findCaseDesc} title={S.findCaseDesc} onClick={() => option({ caseSensitive: !find.options.caseSensitive })}><code>Aa</code></button>
				<button type="button" class={`st-chip ${find.options.wholeWord ? 'st-chip-on' : ''}`} aria-pressed={find.options.wholeWord} aria-label={S.findWordDesc} title={S.findWordDesc} onClick={() => option({ wholeWord: !find.options.wholeWord })}><code>ab</code></button>
				<button type="button" class={`st-chip ${find.options.regex ? 'st-chip-on' : ''}`} aria-pressed={find.options.regex} aria-label={S.findRegexDesc} title={S.findRegexDesc} onClick={() => option({ regex: !find.options.regex })}><code>.*</code></button>
				<button type="button" class={`st-chip ${find.replacing ? 'st-chip-on' : ''}`} aria-pressed={find.replacing} onClick={() => setFind({ ...find, replacing: !find.replacing })}>{S.replaceToggle}</button>
				{invalid && <span class="wm-find-error">{S.findInvalid}</span>}
			</div>
			{find.replacing && (
				<div class="wm-find-row">
					<input ref={replaceRef} class="st-input wm-find-input" type="text" placeholder={S.replaceLabel} value={find.replacement} autocapitalize="off" autocorrect="off" spellcheck={false} onInput={(e) => setFind({ ...find, replacement: (e.target as HTMLInputElement).value })} onFocus={() => setInReplace(true)} onBlur={() => setInReplace(false)} />
					<button type="button" class="st-btn st-btn-sm st-btn-ghost" disabled={!matches.length} onClick={onReplace}>{S.replaceOne}</button>
					<button type="button" class="st-btn st-btn-sm st-btn-ghost" disabled={!matches.length} onClick={onReplaceAll}>{S.replaceAll}</button>
				</div>
			)}
		</div>
	);
}

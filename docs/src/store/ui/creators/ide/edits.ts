/**
 * Port of the app's pure editing functions: CodeEdits.kt (line commands),
 * the smart-edit rules of CodeEditor.kt with PythonCode.kt's PythonSmartRules
 * (auto-close, step-over, pair backspace, auto-indent after a colon),
 * CodeFolding.kt, CodeFind.kt and CodeDiff.kt. Everything is a function of a
 * string and a range.
 */
import { inInert, lex, opensBlock, PyTokenKind } from '../../../lib/python';

export interface Range {
	start: number;
	end: number;
}
export const rangeMin = (r: Range) => Math.min(r.start, r.end);
export const rangeMax = (r: Range) => Math.max(r.start, r.end);
export const rangeLength = (r: Range) => rangeMax(r) - rangeMin(r);
export const collapsed = (r: Range) => r.start === r.end;
export const rangeAt = (offset: number): Range => ({ start: offset, end: offset });

/** One change: `range` replaced by `text`, then `selection` set in the offsets after the change. */
export interface CodeTextEdit {
	range: Range;
	text: string;
	selection: Range;
}

export function applyEdit(document: string, e: CodeTextEdit): string {
	return document.substring(0, rangeMin(e.range)) + e.text + document.substring(rangeMax(e.range));
}

export function lineRangeAt(text: string, offset: number): Range {
	const at = Math.min(Math.max(offset, 0), text.length);
	const start = at === 0 ? 0 : text.lastIndexOf('\n', at - 1) + 1;
	const nl = text.indexOf('\n', at);
	return { start, end: nl < 0 ? text.length : nl };
}

export function lineStartOffsets(text: string): number[] {
	const starts = [0];
	for (let i = 0; i < text.length; i++) if (text[i] === '\n') starts.push(i + 1);
	return starts;
}

export function lineOf(lineStarts: number[], offset: number): number {
	let low = 0;
	let high = lineStarts.length - 1;
	while (low < high) {
		const mid = (low + high + 1) >>> 1;
		if (lineStarts[mid]! <= offset) low = mid;
		else high = mid - 1;
	}
	return low;
}

export function offsetOfLine(text: string, line: number): number {
	const starts = lineStartOffsets(text);
	return starts[Math.min(Math.max(line, 0), starts.length - 1)]!;
}

const isWordChar = (c: string) => /[\p{L}\p{N}_]/u.test(c);
const isBlankChar = (c: string) => c === ' ' || c === '\t';

export function wordRangeAt(text: string, offset: number): Range {
	const at = Math.min(Math.max(offset, 0), text.length);
	const here = text[at];
	const before = at > 0 ? text[at - 1] : undefined;
	let test: ((c: string) => boolean) | null = null;
	if (here !== undefined && isWordChar(here)) test = isWordChar;
	else if (before !== undefined && isWordChar(before)) test = isWordChar;
	else if (here !== undefined && isBlankChar(here)) test = isBlankChar;
	else if (before !== undefined && isBlankChar(before)) test = isBlankChar;
	if (!test) return here !== undefined ? { start: at, end: at + 1 } : rangeAt(at);
	let start = at;
	while (start > 0 && test(text[start - 1]!)) start--;
	let end = at;
	while (end < text.length && test(text[end]!)) end++;
	return { start, end };
}

export function caretStep(text: string, offset: number, delta: number, byWord: boolean): number {
	if (!byWord) return Math.min(Math.max(offset + delta, 0), text.length);
	let at = Math.min(Math.max(offset, 0), text.length);
	for (let i = 0; i < Math.abs(delta); i++) {
		if (delta > 0) {
			while (at < text.length && !isWordChar(text[at]!)) at++;
			while (at < text.length && isWordChar(text[at]!)) at++;
		} else {
			while (at > 0 && !isWordChar(text[at - 1]!)) at--;
			while (at > 0 && isWordChar(text[at - 1]!)) at--;
		}
	}
	return at;
}

function leading(line: string): number {
	let i = 0;
	while (i < line.length && (line[i] === ' ' || line[i] === '\t')) i++;
	return i;
}

/** The whole lines `selection` touches, newline excluded; a selection ending at a line start leaves that line out. */
function blockOf(text: string, selection: Range): Range {
	const first = lineRangeAt(text, rangeMin(selection)).start;
	const max = rangeMax(selection);
	const lastAnchor = max > rangeMin(selection) && text[max - 1] === '\n' ? max - 1 : max;
	return { start: first, end: lineRangeAt(text, lastAnchor).end };
}

export function toggleLineComment(text: string, selection: Range, marker: string): CodeTextEdit {
	const block = blockOf(text, selection);
	const lines = text.substring(block.start, block.end).split('\n');
	const written = lines.filter((l) => l.trim().length > 0);
	const uncomment = written.length > 0 && written.every((l) => l.substring(leading(l)).startsWith(marker));
	const indent = Math.min(...(written.length ? written : lines).map(leading));
	const changes = lines.map((line) => {
		if (line.trim().length === 0 && written.length > 0) return { text: line, at: 0, delta: 0 };
		if (uncomment) {
			const at = leading(line);
			const after = at + marker.length;
			const removed = marker.length + (line[after] === ' ' ? 1 : 0);
			return { text: line.substring(0, at) + line.substring(at + removed), at, delta: -removed };
		}
		return { text: line.substring(0, indent) + marker + ' ' + line.substring(indent), at: indent, delta: marker.length + 1 };
	});
	const replaced = changes.map((c) => c.text).join('\n');
	const map = (offset: number): number => {
		if (offset > block.end) return offset + replaced.length - (block.end - block.start);
		let oldStart = block.start;
		let newStart = block.start;
		for (let index = 0; index < changes.length; index++) {
			const change = changes[index]!;
			const oldLength = lines[index]!.length;
			if (offset <= oldStart + oldLength || index === changes.length - 1) {
				const column = offset - oldStart;
				const moved = change.delta >= 0 ? (column >= change.at ? column + change.delta : column) : column <= change.at ? column : Math.max(change.at, column + change.delta);
				return newStart + moved;
			}
			oldStart += oldLength + 1;
			newStart += change.text.length + 1;
		}
		return offset;
	};
	return { range: block, text: replaced, selection: { start: map(selection.start), end: map(selection.end) } };
}

export function duplicateLines(text: string, selection: Range): CodeTextEdit {
	const block = blockOf(text, selection);
	const inserted = '\n' + text.substring(block.start, block.end);
	const shift = inserted.length;
	return { range: rangeAt(block.end), text: inserted, selection: { start: selection.start + shift, end: selection.end + shift } };
}

export function moveLines(text: string, selection: Range, delta: number): CodeTextEdit | null {
	const block = blockOf(text, selection);
	const moving = text.substring(block.start, block.end);
	if (delta < 0) {
		if (block.start === 0) return null;
		const above = lineRangeAt(text, block.start - 1);
		const shift = -(rangeLength(above) + 1);
		return { range: { start: above.start, end: block.end }, text: moving + '\n' + text.substring(above.start, above.end), selection: { start: selection.start + shift, end: selection.end + shift } };
	}
	if (block.end >= text.length) return null;
	const below = lineRangeAt(text, block.end + 1);
	const shift = rangeLength(below) + 1;
	return { range: { start: block.start, end: below.end }, text: text.substring(below.start, below.end) + '\n' + moving, selection: { start: selection.start + shift, end: selection.end + shift } };
}

export function mergeEdits(text: string, changes: [Range, string][], selection: Range): CodeTextEdit | null {
	if (!changes.length) return null;
	const sorted = [...changes].sort((a, b) => rangeMin(a[0]) - rangeMin(b[0]));
	const start = rangeMin(sorted[0]![0]);
	const end = rangeMax(sorted[sorted.length - 1]![0]);
	let middle = '';
	let cursor = start;
	for (const [range, replacement] of sorted) {
		if (rangeMin(range) < cursor) return null;
		middle += text.substring(cursor, rangeMin(range)) + replacement;
		cursor = rangeMax(range);
	}
	return { range: { start, end }, text: middle, selection };
}

export const CODE_TAB_STOP = 4;
export const INDENT = ' '.repeat(CODE_TAB_STOP);

export function tabShiftsLines(text: string, selection: Range): boolean {
	if (collapsed(selection)) return false;
	const line = lineRangeAt(text, rangeMin(selection));
	return rangeMax(selection) > line.end || (rangeMin(selection) === line.start && rangeMax(selection) === line.end);
}

export function insertIndent(text: string, selection: Range): CodeTextEdit {
	const column = rangeMin(selection) - lineRangeAt(text, rangeMin(selection)).start;
	const spaces = ' '.repeat(CODE_TAB_STOP - (column % CODE_TAB_STOP));
	return { range: { start: rangeMin(selection), end: rangeMax(selection) }, text: spaces, selection: rangeAt(rangeMin(selection) + spaces.length) };
}

/**
 * `CodeEditorState.shiftLines`: Tab and Shift+Tab. With no selection, Tab is four
 * spaces; with one, every line the selection touches moves in or out by one
 * step, and so does `wholeLines` with none, as Ctrl+] does. Null when nothing changes.
 */
export function shiftLines(text: string, selection: Range, levels: number, wholeLines = false): CodeTextEdit | null {
	if (levels > 0 && collapsed(selection) && !wholeLines) {
		return { range: rangeAt(selection.end), text: INDENT, selection: rangeAt(selection.end + INDENT.length) };
	}
	const first = lineRangeAt(text, rangeMin(selection)).start;
	const nl = text.indexOf('\n', rangeMax(selection));
	const last = nl < 0 ? text.length : nl;
	const block = text.substring(first, last);
	const shifted = block.split('\n').map((line) => shiftOne(line, levels)).join('\n');
	if (shifted === block) return null;
	const grown = shifted.length - block.length;
	const end = Math.min(Math.max(rangeMax(selection) + grown, first), first + shifted.length);
	return { range: { start: first, end: last }, text: shifted, selection: { start: Math.max(rangeMin(selection), first), end } };
}

/** One line in or out by a single step. Out stops at the left margin. */
function shiftOne(line: string, levels: number): string {
	if (levels > 0) return INDENT + line;
	if (line.startsWith(INDENT)) return line.substring(INDENT.length);
	return line.replace(/^ +/, '');
}

export function deleteLines(text: string, selection: Range): CodeTextEdit {
	const block = blockOf(text, selection);
	const column = selection.end - lineRangeAt(text, selection.end).start;
	const range: Range = block.end < text.length ? { start: block.start, end: block.end + 1 } : block.start > 0 ? { start: block.start - 1, end: block.end } : { start: 0, end: text.length };
	const next = text.substring(0, range.start) + text.substring(range.end);
	const line = lineRangeAt(next, Math.min(range.start, next.length));
	return { range, text: '', selection: rangeAt(line.start + Math.min(column, rangeLength(line))) };
}

export function insertLine(text: string, selection: Range, above: boolean): CodeTextEdit {
	const line = lineRangeAt(text, selection.end);
	const content = text.substring(line.start, line.end);
	const indent = content.substring(0, leading(content));
	if (above) return { range: rangeAt(line.start), text: indent + '\n', selection: rangeAt(line.start + indent.length) };
	return { range: rangeAt(line.end), text: '\n' + indent, selection: rangeAt(line.end + 1 + indent.length) };
}

export function copyLines(text: string, selection: Range, up: boolean): CodeTextEdit {
	if (!up) return duplicateLines(text, selection);
	const block = blockOf(text, selection);
	return { range: rangeAt(block.end), text: '\n' + text.substring(block.start, block.end), selection };
}

export function expandLineSelection(text: string, selection: Range): Range {
	const start = lineRangeAt(text, rangeMin(selection)).start;
	const end = lineRangeAt(text, rangeMax(selection)).end;
	return { start, end: end < text.length ? end + 1 : end };
}

export function smartHome(text: string, offset: number): number {
	const line = lineRangeAt(text, offset);
	const written = line.start + leading(text.substring(line.start, line.end));
	return offset === written ? line.start : written;
}

export function lineText(text: string, offset: number): string {
	const line = lineRangeAt(text, offset);
	return text.substring(line.start, line.end) + '\n';
}

export function pasteLines(text: string, offset: number, clip: string): CodeTextEdit {
	const line = lineRangeAt(text, offset);
	return { range: rangeAt(line.start), text: clip, selection: rangeAt(offset + clip.length) };
}

export function expandSelection(text: string, selection: Range, regions: Range[]): Range | null {
	const min = rangeMin(selection);
	const max = rangeMax(selection);
	const line = lineRangeAt(text, min);
	const content = text.substring(line.start, line.end);
	const writtenEnd = line.start + content.trimEnd().length;
	const steps: Range[] = [wordRangeAt(text, min), { start: Math.min(line.start + leading(content), writtenEnd), end: writtenEnd }, line, ...regions, { start: 0, end: text.length }];
	let best: Range | null = null;
	for (const s of steps) {
		if (rangeMin(s) <= min && rangeMax(s) >= max && rangeLength(s) > max - min && (!best || rangeLength(s) < rangeLength(best))) best = s;
	}
	return best;
}

export function nextProblem(problems: Range[], from: number, forward: boolean): Range | null {
	if (!problems.length) return null;
	const sorted = [...problems].sort((a, b) => rangeMin(a) - rangeMin(b));
	if (forward) return sorted.find((p) => rangeMin(p) > from) ?? sorted[0]!;
	for (let i = sorted.length - 1; i >= 0; i--) if (rangeMin(sorted[i]!) < from) return sorted[i]!;
	return sorted[sorted.length - 1]!;
}

const OPENING = '([{';
const CLOSING = ')]}';

export function bracketJump(text: string, brackets: number[], caret: number, pair: (offset: number) => [number, number] | null): number | null {
	const here = pair(caret);
	if (here) {
		const [open, close] = here;
		return caret === open || caret === open + 1 ? close + 1 : open + 1;
	}
	let depth = 0;
	for (let i = brackets.length - 1; i >= 0; i--) {
		const at = brackets[i]!;
		if (at >= caret) continue;
		const c = text[at]!;
		if (CLOSING.includes(c)) depth++;
		else if (OPENING.includes(c)) {
			if (depth === 0) {
				const p = pair(at + 1);
				return p ? p[1] + 1 : null;
			}
			depth--;
		}
	}
	return null;
}

/** Every one of `spans` renamed to `name` as one edit, the caret kept on the same name. */
export function renameEdit(text: string, spans: Range[], name: string, caret: number): CodeTextEdit | null {
	if (!spans.length) return null;
	const sorted = [...spans].sort((a, b) => rangeMin(a) - rangeMin(b));
	const changes: [Range, string][] = sorted.map((s) => [s, name]);
	let shift = 0;
	for (const span of sorted) {
		if (rangeMax(span) <= caret) shift += name.length - rangeLength(span);
		else if (rangeMin(span) < caret) return mergeEdits(text, changes, rangeAt(rangeMin(span) + shift + Math.min(caret - rangeMin(span), name.length)));
		else break;
	}
	return mergeEdits(text, changes, rangeAt(caret + shift));
}

const MAX_LINE_DIGITS = 9;

/** The line a typed number names in a document of `lines` lines, read in any script's digits, or null. */
export function parseLineNumber(input: string, lines: number): number | null {
	const digits = input.trim();
	if (!digits || digits.length > MAX_LINE_DIGITS || !/^\p{Nd}+$/u.test(digits)) return null;
	let value = 0;
	for (const ch of digits) value = value * 10 + digitValue(ch);
	return value >= 1 && value <= lines ? value : null;
}

/** The numeric value of any script's decimal digit, as Character.digit(c, 10) reads it. */
function digitValue(ch: string): number {
	const n = Number(ch);
	if (!Number.isNaN(n)) return n;
	// A non-ASCII digit: walk back to the zero of its block.
	const code = ch.codePointAt(0)!;
	for (let zero = code; zero > code - 10; zero--) {
		if (!/\p{Nd}/u.test(String.fromCodePoint(zero - 1))) return code - zero;
	}
	return 0;
}

/** `typed` of CodeAccessoryRow: the selection replaced by `insert` with the caret after it, exactly as if typed. */
export function typed(selection: Range, insert: string): CodeTextEdit {
	const start = rangeMin(selection);
	return { range: { start, end: rangeMax(selection) }, text: insert, selection: rangeAt(start + insert.length) };
}

/** The offset `lines` lines below `offset`, or above for a negative count, at the same column or the end of a shorter line. */
export function caretLines(text: string, offset: number, lines: number): number {
	const here = lineRangeAt(text, offset);
	const column = Math.min(Math.max(offset, here.start), here.end) - here.start;
	let start = here.start;
	for (let i = 0; i < Math.abs(lines); i++) {
		if (lines < 0) {
			if (start > 0) start = lineRangeAt(text, start - 1).start;
		} else {
			const end = lineRangeAt(text, start).end;
			if (end < text.length) start = end + 1;
		}
	}
	const target = lineRangeAt(text, start);
	return Math.min(target.start + column, target.end);
}

/* ---------- brackets and smart edits (CodeEditor.kt / PythonCode.kt) ---------- */

export const PY_PAIRS: Record<string, string> = { '(': ')', '{': '}', '[': ']', '"': '"', "'": "'" };
export const PY_QUOTES = new Set(['"', "'"]);
const PY_CLOSERS = new Set([')', '}', ']']);
export const PY_LINE_COMMENT = '#';

/** Round, curly and square brackets that are code; one inside a string or comment is not a token of its own. */
export function pyBrackets(source: string): number[] {
	const tokens = lex(source);
	const found: number[] = [];
	for (let i = 0; i < tokens.size; i++) {
		if (tokens.kind(i) !== PyTokenKind.OPERATOR) continue;
		const start = tokens.start(i);
		if (tokens.end(i) - start === 1 && (OPENING.includes(source[start]!) || CLOSING.includes(source[start]!))) found.push(start);
	}
	return found;
}

function binarySearch(list: number[], value: number): number {
	let low = 0;
	let high = list.length - 1;
	while (low <= high) {
		const mid = (low + high) >>> 1;
		if (list[mid]! < value) low = mid + 1;
		else if (list[mid]! > value) high = mid - 1;
		else return mid;
	}
	return -1;
}

/** Pairs only brackets of one kind; the bracket behind the caret wins. */
export function matchingBracket(source: string, brackets: number[], caret: number): [number, number] | null {
	if (!brackets.length) return null;
	const behind = caret > 0 ? binarySearch(brackets, caret - 1) : -1;
	let index = behind;
	if (index < 0) {
		index = binarySearch(brackets, caret);
		if (index < 0) return null;
	}
	const at = brackets[index]!;
	if (at >= source.length) return null;
	const bracket = source[at]!;
	const opener = OPENING.indexOf(bracket);
	if (opener >= 0) {
		const closer = CLOSING[opener]!;
		let depth = 0;
		for (let i = index; i < brackets.length; i++) {
			const here = source[brackets[i]!];
			if (here === bracket) depth++;
			if (here === closer && --depth === 0) return [at, brackets[i]!];
		}
		return null;
	}
	const openerOfThis = OPENING[CLOSING.indexOf(bracket)]!;
	let depth = 0;
	for (let i = index; i >= 0; i--) {
		const here = source[brackets[i]!];
		if (here === bracket) depth++;
		if (here === openerOfThis && --depth === 0) return [brackets[i]!, at];
	}
	return null;
}

/**
 * What a typed character becomes (CodeEditor.smartEdit, insert branch):
 * Enter → auto-indent, one level deeper after a line ending in a colon; a closer or quote already ahead → step over; an opener
 * that may close → its pair. Null = pass the character through as typed.
 */
export function smartInsert(text: string, at: number, char: string): CodeTextEdit | null {
	if (char === '\n') return autoIndent(text, at);
	if ((PY_CLOSERS.has(char) || PY_QUOTES.has(char)) && text[at] === char) {
		return { range: rangeAt(at), text: '', selection: rangeAt(at + 1) };
	}
	const closer = PY_PAIRS[char];
	if (closer && shouldClose(text, at)) {
		return { range: rangeAt(at), text: char + closer, selection: rangeAt(at + 1) };
	}
	return null;
}

function shouldClose(text: string, at: number): boolean {
	if (inInert(text, at)) return false;
	if (at >= text.length) return true;
	const next = text[at]!;
	return /\s/.test(next) || next === ',' || PY_CLOSERS.has(next);
}

/** Backspace over an empty pair deletes both halves. */
export function pairBackspace(text: string, at: number): CodeTextEdit | null {
	if (at <= 0) return null;
	const removed = text[at - 1]!;
	const closer = PY_PAIRS[removed];
	if (closer && text[at] === closer) return { range: { start: at - 1, end: at + 1 }, text: '', selection: rangeAt(at - 1) };
	return null;
}

function autoIndent(text: string, at: number): CodeTextEdit {
	const lineStart = lineRangeAt(text, at).start;
	const prefix = text.substring(lineStart, at);
	const indent = prefix.substring(0, leading(prefix));
	const lastChar = prefix.trimEnd().slice(-1);
	const bracketOpens = lastChar !== '' && lastChar in PY_PAIRS && !PY_QUOTES.has(lastChar);
	const opens = bracketOpens || opensBlock(prefix);
	const body = opens ? indent + INDENT : indent;
	const closesNext = bracketOpens && at < text.length && PY_CLOSERS.has(text[at]!);
	if (closesNext) return { range: rangeAt(at), text: '\n' + body + '\n' + indent, selection: rangeAt(at + 1 + body.length) };
	return { range: rangeAt(at), text: '\n' + body, selection: rangeAt(at + 1 + body.length) };
}

/* ---------- folding (CodeFolding.kt) ---------- */

export const FOLD_PLACEHOLDER = ' …';

export interface CodeFold {
	start: number;
	hidden: Range;
}

export function hiddenRangeOf(text: string, region: Range): Range | null {
	const firstBreak = text.indexOf('\n', region.start);
	if (firstBreak < 0 || firstBreak >= region.end) return null;
	const lastBreak = text.lastIndexOf('\n', Math.max(region.end - 1, 0));
	if (lastBreak <= firstBreak) return null;
	return { start: firstBreak, end: lastBreak };
}

export function foldsFor(text: string, regions: Range[], starts: Set<number>): CodeFold[] {
	if (!starts.size) return [];
	const candidates: CodeFold[] = [];
	for (const region of regions) {
		if (!starts.has(region.start)) continue;
		const hidden = hiddenRangeOf(text, region);
		if (hidden) candidates.push({ start: region.start, hidden });
	}
	candidates.sort((a, b) => a.hidden.start - b.hidden.start || b.hidden.end - a.hidden.end);
	const folds: CodeFold[] = [];
	for (const f of candidates) {
		const last = folds[folds.length - 1];
		if (last && f.hidden.start < last.hidden.end) continue;
		folds.push(f);
	}
	return folds;
}

export function remapFoldStarts(starts: Set<number>, oldText: string, newText: string): Set<number> {
	if (!starts.size || oldText === newText) return starts;
	const shortest = Math.min(oldText.length, newText.length);
	let prefix = 0;
	while (prefix < shortest && oldText[prefix] === newText[prefix]) prefix++;
	let suffix = 0;
	while (suffix < shortest - prefix && oldText[oldText.length - 1 - suffix] === newText[newText.length - 1 - suffix]) suffix++;
	const oldEnd = oldText.length - suffix;
	const moved = newText.length - oldText.length;
	const out = new Set<number>();
	for (const s of starts) {
		if (s < prefix) out.add(s);
		else if (s >= oldEnd) out.add(s + moved);
	}
	return out;
}

export function blockAround(regions: Range[], caret: number): Range | null {
	let best: Range | null = null;
	for (const r of regions) if (caret >= r.start && caret < r.end && (!best || rangeLength(r) < rangeLength(best))) best = r;
	return best;
}

export function foldableStarts(text: string, regions: Range[], lineStarts: number[]): Map<number, number> {
	const lines = new Map<number, number>();
	for (const region of [...regions].sort((a, b) => a.start - b.start || b.end - a.end)) {
		if (!hiddenRangeOf(text, region)) continue;
		const line = lineOf(lineStarts, region.start);
		if (!lines.has(line)) lines.set(line, region.start);
	}
	return lines;
}

export function foldToClose(text: string, regions: Range[], folded: Set<number>, caret: number): Range | null {
	const line = lineRangeAt(text, caret);
	let best: Range | null = null;
	for (const r of regions) {
		if (folded.has(r.start)) continue;
		if (!((r.start >= line.start && r.start <= line.end) || (caret >= r.start && caret <= r.end))) continue;
		if (!hiddenRangeOf(text, r)) continue;
		if (!best || rangeLength(r) < rangeLength(best)) best = r;
	}
	return best;
}

export function foldToOpen(text: string, regions: Range[], folded: Set<number>, caret: number): number | null {
	const line = lineRangeAt(text, caret);
	let best: Range | null = null;
	for (const r of regions) {
		if (!folded.has(r.start)) continue;
		if (!((r.start >= line.start && r.start <= line.end) || (caret >= r.start && caret <= r.end))) continue;
		if (!best || rangeLength(r) < rangeLength(best)) best = r;
	}
	return best ? best.start : null;
}

/* ---------- find (CodeFind.kt) ---------- */

export interface CodeFindOptions {
	regex: boolean;
	caseSensitive: boolean;
	wholeWord: boolean;
}

export const MAX_FIND_MATCHES = 10_000;

export function findPattern(query: string, options: CodeFindOptions): RegExp | null {
	if (!query) return null;
	let source = options.regex ? query : query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
	if (options.wholeWord) source = `(?<![\\p{L}\\p{N}_])(?:${source})(?![\\p{L}\\p{N}_])`;
	let flags = 'gmu';
	if (!options.caseSensitive) flags += 'i';
	try {
		return new RegExp(source, flags);
	} catch {
		return null;
	}
}

export function findMatches(text: string, query: string, options: CodeFindOptions): Range[] {
	const pattern = findPattern(query, options);
	if (!pattern) return [];
	const found: Range[] = [];
	pattern.lastIndex = 0;
	let m: RegExpExecArray | null;
	while (found.length < MAX_FIND_MATCHES && (m = pattern.exec(text))) {
		if (m[0].length > 0) found.push({ start: m.index, end: m.index + m[0].length });
		else pattern.lastIndex++;
	}
	return found;
}

function expand(pattern: RegExp | null, text: string, match: Range, replacement: string): string {
	if (!pattern) return replacement;
	const local = new RegExp(pattern.source, pattern.flags.replace('g', '') + 'y');
	local.lastIndex = match.start;
	const m = local.exec(text);
	if (!m || m.index !== match.start || m.index + m[0].length !== match.end) return replacement;
	// Java's $1 group references; a group the pattern lacks or a lone trailing backslash → used as typed.
	if (/\\$/.test(replacement)) return replacement;
	let bad = false;
	const out = replacement.replace(/\\(.)|\$(\d+)|\$\{(\w+)\}/g, (whole, esc: string | undefined, num: string | undefined, name: string | undefined) => {
		if (esc !== undefined) return esc;
		if (num !== undefined) {
			const n = Number(num);
			if (n >= m.length) bad = true;
			return m[n] ?? '';
		}
		if (name !== undefined) {
			const g = m.groups?.[name];
			if (g === undefined) bad = true;
			return g ?? '';
		}
		return whole;
	});
	return bad ? replacement : out;
}

export function replaceMatch(text: string, match: Range, replacement: string, query: string, options: CodeFindOptions): CodeTextEdit {
	const pattern = options.regex ? findPattern(query, options) : null;
	const withText = expand(pattern, text, match, replacement);
	return { range: match, text: withText, selection: rangeAt(match.start + withText.length) };
}

export function replaceAllMatches(text: string, matches: Range[], replacement: string, query: string, options: CodeFindOptions): CodeTextEdit | null {
	if (!matches.length) return null;
	const pattern = options.regex ? findPattern(query, options) : null;
	const changes: [Range, string][] = matches.map((m) => [m, expand(pattern, text, m, replacement)]);
	const grown = changes.reduce((n, [r, w]) => n + w.length - rangeLength(r), 0);
	const caret = Math.max(...changes.map(([r]) => r.end)) + grown;
	return mergeEdits(text, changes, rangeAt(caret));
}

export function firstMatchFrom(matches: Range[], caret: number): number {
	const i = matches.findIndex((m) => m.start >= caret);
	return i < 0 ? 0 : i;
}

/* ---------- diff (CodeDiff.kt) ---------- */

export type DiffKind = 'SAME' | 'ADDED' | 'REMOVED';
export interface DiffLine {
	kind: DiffKind;
	text: string;
}
export type DiffRow = { row: 'line'; line: DiffLine } | { row: 'unchanged'; count: number };

const MAX_DIFF_CELLS = 4_000_000;

export function lineDiff(oldText: string, newText: string): DiffLine[] {
	const a = oldText.split('\n');
	const b = newText.split('\n');
	let start = 0;
	while (start < a.length && start < b.length && a[start] === b[start]) start++;
	let endA = a.length;
	let endB = b.length;
	while (endA > start && endB > start && a[endA - 1] === b[endB - 1]) {
		endA--;
		endB--;
	}
	const out: DiffLine[] = [];
	for (let i = 0; i < start; i++) out.push({ kind: 'SAME', text: a[i]! });
	const n = endA - start;
	const m = endB - start;
	if (n * m > MAX_DIFF_CELLS) {
		for (let i = start; i < endA; i++) out.push({ kind: 'REMOVED', text: a[i]! });
		for (let i = start; i < endB; i++) out.push({ kind: 'ADDED', text: b[i]! });
	} else {
		const common: Int32Array[] = Array.from({ length: n + 1 }, () => new Int32Array(m + 1));
		for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) common[i]![j] = a[start + i] === b[start + j] ? common[i + 1]![j + 1]! + 1 : Math.max(common[i + 1]![j]!, common[i]![j + 1]!);
		let i = 0;
		let j = 0;
		while (i < n && j < m) {
			if (a[start + i] === b[start + j]) {
				out.push({ kind: 'SAME', text: a[start + i]! });
				i++;
				j++;
			} else if (common[i + 1]![j]! >= common[i]![j + 1]!) out.push({ kind: 'REMOVED', text: a[start + i++]! });
			else out.push({ kind: 'ADDED', text: b[start + j++]! });
		}
		while (i < n) out.push({ kind: 'REMOVED', text: a[start + i++]! });
		while (j < m) out.push({ kind: 'ADDED', text: b[start + j++]! });
	}
	for (let i = endA; i < a.length; i++) out.push({ kind: 'SAME', text: a[i]! });
	return out;
}

export function collapseDiff(diff: DiffLine[], context = 3): DiffRow[] {
	const near = new Array<boolean>(diff.length).fill(false);
	diff.forEach((line, index) => {
		if (line.kind === 'SAME') return;
		for (let k = Math.max(0, index - context); k <= Math.min(diff.length - 1, index + context); k++) near[k] = true;
	});
	const rows: DiffRow[] = [];
	let folded = 0;
	diff.forEach((line, index) => {
		if (line.kind === 'SAME' && !near[index]) {
			folded++;
			return;
		}
		if (folded > 0) rows.push({ row: 'unchanged', count: folded });
		folded = 0;
		rows.push({ row: 'line', line });
	});
	if (folded > 0) rows.push({ row: 'unchanged', count: folded });
	return rows;
}


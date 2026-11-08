/** CodeMirror 6 wrapper: JSON and plain modes, follows the site's dark/light theme. */
import { useEffect, useRef } from 'preact/hooks';
import { EditorState, Compartment } from '@codemirror/state';
import { EditorView, keymap, lineNumbers, highlightActiveLine, drawSelection, highlightActiveLineGutter } from '@codemirror/view';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { syntaxHighlighting, defaultHighlightStyle, bracketMatching, indentOnInput } from '@codemirror/language';
import { json } from '@codemirror/lang-json';

export type Lang = 'json' | 'text';

const theme = EditorView.theme({
	'&': { fontSize: '0.84rem', border: '1px solid var(--st-card-border)', borderRadius: 'var(--st-radius-sm)', background: 'var(--sl-color-gray-7)', color: 'var(--st-text)' },
	'.cm-content': { fontFamily: 'var(--sl-font-mono)', padding: '0.5rem 0', caretColor: 'var(--st-text)' },
	'.cm-gutters': { background: 'transparent', color: 'var(--st-muted)', border: 'none' },
	'.cm-activeLine': { background: 'color-mix(in oklab, var(--st-accent) 8%, transparent)' },
	'.cm-activeLineGutter': { background: 'transparent' },
	'&.cm-focused': { outline: '2px solid var(--st-accent)', outlineOffset: '1px' },
	'.cm-scroller': { maxHeight: 'var(--editor-max, 60vh)', overflow: 'auto' },
	'.cm-selectionBackground, &.cm-focused .cm-selectionBackground': { background: 'var(--st-accent-soft) !important' },
});

export function CodeEditor({ value, onChange, lang, height, readOnly }: { value: string; onChange: (v: string) => void; lang: Lang; height?: string; readOnly?: boolean }) {
	const host = useRef<HTMLDivElement>(null);
	const view = useRef<EditorView | null>(null);
	const langComp = useRef(new Compartment());
	const latest = useRef(onChange);
	latest.current = onChange;

	const langExt = (l: Lang) => (l === 'json' ? json() : []);

	useEffect(() => {
		if (!host.current) return;
		const state = EditorState.create({
			doc: value,
			extensions: [
				lineNumbers(),
				highlightActiveLineGutter(),
				history(),
				drawSelection(),
				indentOnInput(),
				bracketMatching(),
				highlightActiveLine(),
				syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
				keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab]),
				langComp.current.of(langExt(lang)),
				theme,
				EditorState.readOnly.of(!!readOnly),
				EditorView.updateListener.of((u) => {
					if (u.docChanged) latest.current(u.state.doc.toString());
				}),
			],
		});
		view.current = new EditorView({ state, parent: host.current });
		return () => {
			view.current?.destroy();
			view.current = null;
		};
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, []);

	useEffect(() => {
		const v = view.current;
		if (!v) return;
		if (v.state.doc.toString() !== value) v.dispatch({ changes: { from: 0, to: v.state.doc.length, insert: value } });
	}, [value]);

	useEffect(() => {
		view.current?.dispatch({ effects: langComp.current.reconfigure(langExt(lang)) });
	}, [lang]);

	return <div ref={host} style={height ? { '--editor-max': height } : undefined} />;
}

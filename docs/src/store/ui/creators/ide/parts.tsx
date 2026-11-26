/**
 * The plugin editor's small parts, shared by its screens and dialogs: the
 * dialog frame, its buttons, the snackbar and the icons.
 */
import type { ComponentChildren } from 'preact';
import { useCallback, useEffect, useRef, useState } from 'preact/hooks';

export function Dialog({ title, children, actions, onDismiss, wide }: { title: string; children: ComponentChildren; actions: ComponentChildren; onDismiss: () => void; wide?: boolean }) {
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
			<div class={`wm-dialog ${wide ? 'wm-dialog-wide' : ''}`} role="dialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()}>
				<h2>{title}</h2>
				<div class="wm-dialog-body">{children}</div>
				<div class="wm-dialog-actions">{actions}</div>
			</div>
		</div>
	);
}

export function TextButton({ children, onClick, disabled }: { children: ComponentChildren; onClick: () => void; disabled?: boolean }) {
	return <button type="button" class="st-btn st-btn-sm st-btn-ghost" disabled={disabled} onClick={onClick}>{children}</button>;
}

export function IconButton({ label, onClick, disabled, expanded, children }: { label: string; onClick: (e: MouseEvent) => void; disabled?: boolean; expanded?: boolean; children: ComponentChildren }) {
	return <button type="button" class="st-icon-btn" aria-label={label} title={label} disabled={disabled} aria-expanded={expanded} onClick={onClick}>{children}</button>;
}

export function useToast(): [ComponentChildren, (text: string) => void] {
	const [text, setText] = useState<string | null>(null);
	const timer = useRef<number | null>(null);
	const show = useCallback((t: string) => {
		setText(t);
		if (timer.current !== null) window.clearTimeout(timer.current);
		timer.current = window.setTimeout(() => setText(null), 4000);
	}, []);
	return [text ? <div class="wm-snackbar" role="status">{text}</div> : null, show];
}

export const svg = (d: string) => () => <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path d={d} fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" /></svg>;
export const IconFolderSvg = svg('M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z');
export const IconFileSvg = svg('M6 3h8l4 4v14H6z M14 3v4h4');
export const IconPackageSvg = svg('M12 3 4 7v10l8 4 8-4V7z M4 7l8 4 8-4 M12 11v10');
export const IconAddSvg = svg('M12 5v14 M5 12h14');
export const IconUnlinkSvg = svg('M9 15l-2 2a3 3 0 0 1-4-4l3-3a3 3 0 0 1 4 0 M15 9l2-2a3 3 0 0 1 4 4l-3 3a3 3 0 0 1-4 0 M4 4l16 16');
export const IconShareSvg = svg('M12 3v12 M7 8l5-5 5 5 M5 14v6h14v-6');
export const IconRenameSvg = svg('M4 20h4l10-10-4-4L4 16z m13 7 4 4');
export const IconDeleteSvg = svg('M4 7h16 M9 7V4h6v3 M6 7l1 13h10l1-13 M10 11v6 M14 11v6');

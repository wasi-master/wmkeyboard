/**
 * The encrypted-backup converter on /privacy/backup-converter/.
 *
 * A Preact island over src/lib/backup-convert.ts, which does all the work on
 * WebCrypto. Nothing here fetches anything: the file is read with
 * `File.arrayBuffer()` and the result goes back out as a blob download.
 */
import { useEffect, useState } from 'preact/hooks';
import { OLD_IMPORT_LIMIT, convert, inspect, type Inspection } from '../lib/backup-convert';
import './link-builder.css';

type Status =
	| { tone: 'idle' | 'ok' | 'warn' | 'bad'; text: string }
	| null;

function describe(seen: Inspection): Status {
	switch (seen.kind) {
		case 'plain':
			return {
				tone: 'ok',
				text: 'This backup is not encrypted. Every version imports it as it is, so there is nothing to convert.',
			};
		case 'unknown':
			return { tone: 'bad', text: 'This is not a WM Keyboard backup.' };
		case 'unsupported':
			return {
				tone: 'bad',
				text: 'This backup was made by a version newer than this page knows. Restore it on that version or a later one.',
			};
		case 'encrypted':
			if (seen.compatible) {
				return {
					tone: 'ok',
					text: 'No conversion needed: version 0.6.3 already opens this file. If it says the passphrase is wrong, the passphrase is wrong.',
				};
			}
			return {
				tone: 'idle',
				text:
					seen.container === 2
						? 'A large backup, sealed in pieces. Type its passphrase to convert it.'
						: 'Sealed under PBKDF2-HMAC-SHA512, which 0.6.3 cannot read. Type its passphrase to convert it.',
			};
	}
}

export default function BackupConverter() {
	const [file, setFile] = useState<File | null>(null);
	const [bytes, setBytes] = useState<Uint8Array | null>(null);
	const [passphrase, setPassphrase] = useState('');
	const [status, setStatus] = useState<Status>(null);
	const [busy, setBusy] = useState(false);
	const [result, setResult] = useState<{ url: string; name: string; large: boolean } | null>(null);

	useEffect(() => () => {
		if (result) URL.revokeObjectURL(result.url);
	}, [result]);

	const seen = bytes ? inspect(bytes) : null;
	const canConvert = seen?.kind === 'encrypted' && !seen.compatible;

	async function pick(next: File | undefined) {
		setResult(null);
		setStatus(null);
		setFile(next ?? null);
		setBytes(null);
		if (!next) return;
		const read = new Uint8Array(await next.arrayBuffer());
		setBytes(read);
		setStatus(describe(inspect(read)));
	}

	async function run(event: Event) {
		event.preventDefault();
		if (!bytes || !file || !canConvert || busy) return;
		setBusy(true);
		setResult(null);
		setStatus({ tone: 'idle', text: 'Converting. Deriving the keys takes a few seconds.' });
		try {
			const r = await convert(bytes, passphrase);
			if (!r.ok) {
				setStatus(
					r.reason === 'bad'
						? { tone: 'bad', text: 'The passphrase is wrong, or the file is damaged.' }
						: describe(inspect(bytes)),
				);
				return;
			}
			const url = URL.createObjectURL(new Blob([r.file], { type: 'application/octet-stream' }));
			const large = r.plainSize > OLD_IMPORT_LIMIT;
			setResult({ url, name: file.name, large });
			setStatus({ tone: 'ok', text: 'Converted. Version 0.6.3 opens the new file with the same passphrase.' });
			const a = document.createElement('a');
			a.href = url;
			a.download = file.name;
			a.click();
		} catch {
			setStatus({ tone: 'bad', text: 'This browser could not run the conversion. Try a current Chrome, Firefox or Safari.' });
		} finally {
			setBusy(false);
		}
	}

	return (
		<form class="lb not-content bc" onSubmit={run}>
			<div class="lb-panel" style={{ display: 'grid', gap: '0.85rem' }}>
				<label class="lb-field">
					<span class="lb-label">Backup file</span>
					<input
						class="lb-input"
						type="file"
						accept=".enc,.wmconfig,.json,application/octet-stream"
						onChange={(e) => pick((e.currentTarget as HTMLInputElement).files?.[0])}
					/>
				</label>
				{canConvert && (
					<label class="lb-field">
						<span class="lb-label">Passphrase</span>
						<input
							class="lb-input"
							type="password"
							autocomplete="off"
							spellcheck={false}
							value={passphrase}
							onInput={(e) => setPassphrase((e.currentTarget as HTMLInputElement).value)}
						/>
					</label>
				)}
				{canConvert && (
					<div>
						<button class="lb-btn lb-btn-primary" type="submit" disabled={busy || passphrase.length === 0}>
							{busy ? 'Converting…' : 'Convert and download'}
						</button>
					</div>
				)}
				{status && (
					<p role="status" style={{ margin: 0, color: toneColor(status.tone) }}>
						{status.text}
					</p>
				)}
				{result?.large && (
					<p style={{ margin: 0, color: 'var(--lb-warn)' }}>
						This backup holds more than 32 MiB, and version 0.6.3 refuses to import a file that large. Turn off
						sticker packs or other large items under What goes in a backup, make a new backup, and convert that
						one.
					</p>
				)}
				{result && (
					<p style={{ margin: 0 }}>
						<a class="lb-btn" href={result.url} download={result.name}>
							Download {result.name} again
						</a>
					</p>
				)}
			</div>
		</form>
	);
}

function toneColor(tone: 'idle' | 'ok' | 'warn' | 'bad'): string {
	if (tone === 'ok') return 'var(--lb-ok)';
	if (tone === 'warn') return 'var(--lb-warn)';
	if (tone === 'bad') return 'var(--lb-bad)';
	return 'inherit';
}

/** Zip reading/writing over fflate, for the packed addon types and the creator. */
import { unzipSync, zipSync, type Unzipped } from 'fflate';

export interface ZipEntry {
	name: string;
	data: Uint8Array;
}

export function readZip(bytes: Uint8Array, maxEntries = 512): ZipEntry[] {
	let files: Unzipped;
	try {
		files = unzipSync(bytes);
	} catch (e) {
		throw new Error(`Not a zip archive: ${(e as Error).message}`);
	}
	const out: ZipEntry[] = [];
	for (const [name, data] of Object.entries(files)) {
		if (name.endsWith('/')) continue; // directory marker
		out.push({ name, data });
		if (out.length > maxEntries) throw new Error(`Over ${maxEntries} entries.`);
	}
	return out;
}

export function zipFind(entries: ZipEntry[], name: string): ZipEntry | undefined {
	return entries.find((e) => e.name === name) ?? entries.find((e) => e.name.split('/').pop() === name);
}

export function textOf(entry: ZipEntry): string {
	return new TextDecoder('utf-8').decode(entry.data);
}

/** Writes a zip with stable (1980-01-01) timestamps, the way the sample repo's tools do. */
export function writeZip(entries: { name: string; data: Uint8Array | string }[]): Uint8Array {
	const enc = new TextEncoder();
	const files: Record<string, [Uint8Array, { level: 0 | 6 | 9; mtime: Date }]> = {};
	for (const e of entries) {
		const data = typeof e.data === 'string' ? enc.encode(e.data) : e.data;
		const stored = /\.(png|jpe?g|gif|webp|mp3|ogg|ttf|otf|woff2?)$/i.test(e.name);
		files[e.name] = [data, { level: stored ? 0 : 6, mtime: new Date(Date.UTC(1980, 0, 1)) }];
	}
	return zipSync(files);
}

export function mimeFor(name: string): string {
	const ext = name.toLowerCase().split('.').pop() ?? '';
	return (
		{
			png: 'image/png',
			jpg: 'image/jpeg',
			jpeg: 'image/jpeg',
			gif: 'image/gif',
			webp: 'image/webp',
			svg: 'image/svg+xml',
			mp3: 'audio/mpeg',
			ogg: 'audio/ogg',
			wav: 'audio/wav',
			m4a: 'audio/mp4',
			ttf: 'font/ttf',
			otf: 'font/otf',
			json: 'application/json',
			txt: 'text/plain',
			py: 'text/x-python',
		} as Record<string, string>
	)[ext] ?? 'application/octet-stream';
}

export function blobUrl(data: Uint8Array, mime: string): string {
	return URL.createObjectURL(new Blob([data as BlobPart], { type: mime }));
}

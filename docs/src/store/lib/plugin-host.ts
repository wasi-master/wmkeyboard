/**
 * The web preview's answers to API 5 host calls (PluginHost.kt and the
 * keyboard's PluginKeyboardServices, as far as a browser can stand in).
 *
 * The interpreter has already refused anything the manifest does not
 * declare. Like the editor on the phone, the preview treats the risky
 * permissions a draft declares as granted. What a browser can do, it does:
 * https requests to the manifest's hosts (if the site allows other pages to
 * read it), a pretend text field, toasts, dialogs, speech and sharing.
 * What only a phone has (the AI model, location, files, sensors, the
 * scanners, the calendar, the To-do list, the clipboard's contents) gets a pretend answer of the same
 * shape, marked in the console, so a plugin's code paths can run here.
 */

export interface HostContext {
	/** The hosts `http` may reach, as the manifest lists them. */
	hosts: string[];
	/** Hands an event back to the session: `{"type": "result", "token", "value"}`. */
	deliver: (event: Record<string, unknown>) => void;
	/** A line in the editor's console. */
	log: (text: string) => void;
	/** The manifest's settings rows, for `plugin.settings.set`. */
	settings?: SettingSpec[];
}

/** One row of the manifest's `settings`, as `plugin.settings.set` checks a value against it. */
export interface SettingSpec { key: string; type: string; default?: string; options?: string[] }

/** The value a session reads for [spec] holding [raw] text: PluginStore.settingsJson. */
export function settingValue(spec: SettingSpec, raw: string): unknown {
	if (spec.type === 'secret') return { $secret: true };
	if (spec.type === 'switch') return raw.trim() === 'true';
	if (spec.type === 'number') return raw.trim() !== '' && Number.isFinite(Number(raw)) ? Number(raw) : null;
	return raw;
}

/** PluginSettingSpec.storedText: the text kept for [value], or null when it does not fit the row. */
function storedText(spec: SettingSpec, value: unknown): string | null {
	switch (spec.type) {
		case 'switch': return typeof value === 'boolean' ? String(value) : null;
		case 'number': return typeof value === 'number' && Number.isFinite(value) ? String(value) : null;
		case 'choice': return typeof value === 'string' && (spec.options ?? []).includes(value) ? value : null;
		case 'text': case 'secret': return typeof value === 'string' ? value : null;
		default: return null;
	}
}

type Args = Record<string, unknown>;

/** The bridge, and how to stop what it left running (sensor readings). */
export type Bridge = ((op: string, argsJson: string) => string) & { dispose: () => void };

const reply = (value: unknown) => JSON.stringify({ value: value ?? null });
const fail = (kind: string, message: string) => JSON.stringify({ error: { kind, message } });
const phoneOnly = (what: string) => fail('NotImplementedError', `${what} is not available in the web preview. Install the plugin to try it on the phone.`);

/** Headers a page may not set, or that would let a plugin pass itself off as someone. */
const FORBIDDEN_HEADERS = /^(cookie|cookie2|host|origin|referer|user-agent|authorization-proxy|connection|content-length|proxy-.*|sec-.*)$/i;
const MAX_REPLY = 1024 * 1024;

/** PluginManifestCodec.hostAllowed: exactly, or under a `*.` entry. */
export function hostAllowed(host: string, hosts: string[]): boolean {
	const h = host.toLowerCase();
	return hosts.some((entry) => {
		const e = entry.toLowerCase();
		if (e.startsWith('*.')) {
			const base = e.slice(2);
			return h === base || h.endsWith('.' + base);
		}
		return h === e;
	});
}

/**
 * PluginManifestCodec.isLocalHost: a server on the local network, which only
 * `network.local` reaches, and only when the user typed it into a host setting.
 */
export function isLocalHost(host: string): boolean {
	const h = host.toLowerCase().replace(/\.$/, '');
	if (!h || h.startsWith('[') || h.includes(':') || h === 'localhost' || h.endsWith('.localhost')) return false;
	const parts = h.split('.');
	if (parts.length === 4 && parts.every((p) => /^\d{1,3}$/.test(p))) {
		const n = parts.map(Number);
		if (n.some((x) => x > 255)) return false;
		return n[0] === 10 || (n[0] === 172 && n[1] >= 16 && n[1] <= 31) || (n[0] === 192 && n[1] === 168) || (n[0] === 100 && n[1] >= 64 && n[1] <= 127) || (n[0] === 169 && n[1] === 254);
	}
	if (!parts.every((p) => /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(p))) return false;
	if (parts.length === 1) return !/^\d+$/.test(h);
	return ['local', 'lan', 'home', 'home.arpa', 'internal', 'localdomain'].some((s) => h.endsWith('.' + s));
}

const LOCAL_IN_PREVIEW = 'is on a local network, which the web preview cannot reach (browsers block it). Install the plugin to try it on the phone, with "network.local" turned on.';

/** The address to fetch, or why not as [error kind, message]. */
function requestUrl(args: Args, hosts: string[]): URL | [string, string] {
	let url: URL;
	try {
		url = new URL(String(args.url ?? ''));
	} catch {
		return ['ValueError', 'not a web address'];
	}
	if ((url.protocol === 'http:' || url.protocol === 'https:') && isLocalHost(url.hostname)) return ['ConnectionError', `${url.hostname} ${LOCAL_IN_PREVIEW}`];
	if (url.protocol !== 'https:') return ['ValueError', 'only https addresses (plain http only on a local network)'];
	if (url.username || url.password) return ['ValueError', 'no user name in the address'];
	if (/^[\d.]+$/.test(url.hostname) || url.hostname.includes(':') || url.hostname === 'localhost') return ['ValueError', 'no IP addresses or localhost'];
	if (!hostAllowed(url.hostname, hosts)) return ['PermissionError', `${url.hostname} is not in the manifest's "hosts"`];
	const params = args.params as Record<string, string> | undefined;
	if (params) for (const [k, v] of Object.entries(params)) url.searchParams.append(k, String(v));
	return url;
}

function bodyOf(args: Args): { body: string | Uint8Array<ArrayBuffer> | null; type: string | null } {
	let found: { body: string | Uint8Array<ArrayBuffer> | null; type: string | null } = { body: null, type: null };
	if (typeof args.body === 'string') found = { body: args.body, type: null };
	else if (args.form && typeof args.form === 'object') {
		found = { body: new URLSearchParams(args.form as Record<string, string>).toString(), type: 'application/x-www-form-urlencoded' };
	} else if (typeof args.bodyBytes === 'string') {
		// Bytes, such as a multipart form made by files=, arrive as base64.
		const raw = atob(args.bodyBytes);
		const bytes = new Uint8Array(raw.length);
		for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
		found = { body: bytes, type: 'application/octet-stream' };
	}
	// The plugin's own Content-Type wins, as on the phone.
	const own = Object.keys((args.headers as Record<string, string>) ?? {}).some((k) => k.toLowerCase() === 'content-type');
	return own ? { body: found.body, type: null } : found;
}

function headersOf(xhr: XMLHttpRequest): Record<string, string> {
	const out: Record<string, string> = {};
	for (const line of xhr.getAllResponseHeaders().trim().split(/[\r\n]+/)) {
		const at = line.indexOf(':');
		if (at > 0) out[line.slice(0, at).trim().toLowerCase()] = line.slice(at + 1).trim();
	}
	return out;
}

const BLOCKED = 'The browser could not read the reply: the site may not allow other pages to read it (CORS), or it could not be reached. The keyboard on the phone has no such limit.';

function httpSync(args: Args, url: URL): string {
	const xhr = new XMLHttpRequest();
	try {
		// Synchronous on purpose: the interpreter waits for the answer the way
		// the keyboard's plugin thread does. Use then= in the plugin for a
		// call that should not hold the page.
		xhr.open(String(args.method ?? 'GET'), url.toString(), false);
		const { body, type } = bodyOf(args);
		for (const [k, v] of Object.entries((args.headers as Record<string, string>) ?? {})) {
			if (!FORBIDDEN_HEADERS.test(k)) xhr.setRequestHeader(k, String(v));
		}
		if (type) xhr.setRequestHeader('Content-Type', type);
		xhr.send(body);
	} catch {
		return fail('ConnectionError', BLOCKED);
	}
	if (xhr.status === 0) return fail('ConnectionError', BLOCKED);
	const text = xhr.responseText ?? '';
	if (text.length > MAX_REPLY) return fail('ConnectionError', 'The reply is over 1 MB.');
	return reply({ status: xhr.status, reason: xhr.statusText, url: xhr.responseURL || url.toString(), headers: headersOf(xhr), text });
}

function httpAsync(args: Args, url: URL, token: string, ctx: HostContext): string {
	const { body, type } = bodyOf(args);
	const headers: Record<string, string> = {};
	for (const [k, v] of Object.entries((args.headers as Record<string, string>) ?? {})) if (!FORBIDDEN_HEADERS.test(k)) headers[k] = String(v);
	if (type) headers['Content-Type'] = type;
	const controller = new AbortController();
	const timer = setTimeout(() => controller.abort(), Math.min(Number(args.timeout) || 15, 15) * 1000);
	fetch(url, { method: String(args.method ?? 'GET'), headers, body, signal: controller.signal, credentials: 'omit' })
		.then(async (r) => {
			const text = await r.text();
			const h: Record<string, string> = {};
			r.headers.forEach((v, k) => (h[k] = v));
			ctx.deliver({ type: 'result', token, value: text.length > MAX_REPLY ? { error: { kind: 'ConnectionError', message: 'The reply is over 1 MB.' } } : { status: r.status, reason: r.statusText, url: r.url, headers: h, text } });
		})
		.catch((e: Error) => {
			const timedOut = e?.name === 'AbortError';
			ctx.deliver({ type: 'result', token, value: { error: { kind: timedOut ? 'TimeoutError' : 'ConnectionError', message: timedOut ? 'No answer within 15 seconds.' : BLOCKED } } });
		})
		.finally(() => clearTimeout(timer));
	return reply(null);
}

/** A pretend text field, so field calls have somewhere to land and the console says what they did. */
class PreviewField {
	text = '';
	cursor = 0;
	describe(ctx: HostContext, what: string) {
		ctx.log(`[field] ${what}: “${this.text.slice(0, this.cursor)}|${this.text.slice(this.cursor)}”`);
	}
}

// ---- pretend answers for what only a phone has ----------------------------

/** Where `location.get` says the phone is: Dhaka, close enough for a weather plugin. */
const PRETEND_PLACE = { lat: 23.8103, lon: 90.4125 };

/** What the pretend AI says, so a plugin's text paths have something to show. */
function pretendReply(prompt: string): string {
	const asked = prompt.replace(/\s+/g, ' ').trim();
	const quoted = asked.length > 80 ? `${asked.slice(0, 80)}…` : asked;
	return `This is a pretend reply from the web preview, which has no AI model. You asked: “${quoted}”. On the phone, the model the user chose in the keyboard's settings answers instead.`;
}

function base64OfBytes(bytes: Uint8Array): string {
	let s = '';
	for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
	return btoa(s);
}

/** A small picture to hand `files.pick(kind="image")`: the editor's colours and a label. */
function pretendImage(): string {
	const canvas = document.createElement('canvas');
	canvas.width = 160;
	canvas.height = 120;
	const g = canvas.getContext('2d');
	if (!g) return '';
	const fill = g.createLinearGradient(0, 0, 160, 120);
	fill.addColorStop(0, '#6750a4');
	fill.addColorStop(1, '#7d5260');
	g.fillStyle = fill;
	g.fillRect(0, 0, 160, 120);
	g.fillStyle = '#ffffff';
	g.font = 'bold 18px sans-serif';
	g.textAlign = 'center';
	g.fillText('sample.png', 80, 66);
	return canvas.toDataURL('image/png').split(',')[1] ?? '';
}

/** One second of silence, as a WAV file, for `files.pick(kind="audio")`. */
function pretendWav(): string {
	const rate = 8000;
	const samples = rate;
	const bytes = new Uint8Array(44 + samples);
	const view = new DataView(bytes.buffer);
	const text = (at: number, t: string) => [...t].forEach((c, i) => view.setUint8(at + i, c.charCodeAt(0)));
	text(0, 'RIFF');
	view.setUint32(4, 36 + samples, true);
	text(8, 'WAVEfmt ');
	view.setUint32(16, 16, true);
	view.setUint16(20, 1, true);
	view.setUint16(22, 1, true);
	view.setUint32(24, rate, true);
	view.setUint32(28, rate, true);
	view.setUint16(32, 1, true);
	view.setUint16(34, 8, true);
	text(36, 'data');
	view.setUint32(40, samples, true);
	bytes.fill(128, 44);
	return base64OfBytes(bytes);
}

const PRETEND_TEXT_FILE = 'Shopping\n- rice\n- lentils\n- green chillies\n';

function pretendFile(kind: string): { name: string; mime: string; data: string } | null {
	const k = kind.toLowerCase();
	if (k === 'image' || k.startsWith('image/')) return { name: 'sample.png', mime: 'image/png', data: pretendImage() };
	if (k === 'audio' || k.startsWith('audio/')) return { name: 'sample.wav', mime: 'audio/wav', data: pretendWav() };
	if (k === 'video' || k.startsWith('video/')) return null;
	const json = k === 'application/json';
	const body = json ? '{"items": ["rice", "lentils", "green chillies"]}\n' : PRETEND_TEXT_FILE;
	return { name: json ? 'sample.json' : 'sample.txt', mime: json ? 'application/json' : 'text/plain', data: base64OfBytes(new TextEncoder().encode(body)) };
}

/** The bytes in a `files.save` call, however they were sent. */
function sizeOf(data: unknown): number {
	if (typeof data === 'string') return Math.floor((data.length * 3) / 4);
	if (Array.isArray(data)) return data.length;
	return 0;
}

export const PRETEND_SENSORS = ['accelerometer', 'gyroscope', 'magnetometer', 'light', 'steps', 'proximity', 'pressure'];

/** A reading for [kind] at [t] seconds: the phone lying on a table, gently nudged. */
function pretendReading(kind: string, t: number, n: number): Record<string, number> {
	const wobble = (f: number, a: number) => Math.round(Math.sin(t * f) * a * 1000) / 1000;
	switch (kind) {
		case 'accelerometer':
			return { x: wobble(1.3, 0.4), y: wobble(0.9, 0.3), z: Math.round((9.81 + wobble(2.1, 0.1)) * 1000) / 1000 };
		case 'gyroscope':
			return { x: wobble(1.1, 0.05), y: wobble(0.7, 0.05), z: wobble(0.5, 0.02) };
		case 'magnetometer':
			return { x: 21.5 + wobble(0.3, 1), y: -4.2 + wobble(0.4, 1), z: -39.8 + wobble(0.2, 1) };
		case 'light':
			return { value: Math.round(320 + Math.sin(t * 0.5) * 60) };
		case 'steps':
			return { value: Math.floor(n / 2) };
		case 'proximity':
			return { value: 5 };
		default:
			return { value: Math.round((1013.25 + wobble(0.1, 0.4)) * 100) / 100 };
	}
}

/** Pretend calendar: a stand-up each weekday and lunch on Wednesdays, in the browser's time zone. */
function pretendEvents(start: number, end: number): Record<string, unknown>[] {
	const out: Record<string, unknown>[] = [];
	const day = new Date(start * 1000);
	day.setHours(0, 0, 0, 0);
	for (let n = 0; n < 62 && day.getTime() / 1000 < end; n++) {
		const at = (h: number, m: number) => new Date(day.getFullYear(), day.getMonth(), day.getDate(), h, m).getTime() / 1000;
		const wd = day.getDay();
		if (wd >= 1 && wd <= 5) out.push({ id: 1000 + n * 2, title: 'Stand-up', start: at(9, 30), end: at(9, 45), all_day: false, location: null, calendar: 1, recurring: true });
		if (wd === 3) out.push({ id: 1001 + n * 2, title: 'Lunch with Sam', start: at(13, 0), end: at(14, 0), all_day: false, location: 'Cafe', calendar: 1, recurring: false });
		day.setDate(day.getDate() + 1);
	}
	return out.filter((e) => (e.end as number) > start && (e.start as number) < end);
}

interface PretendTask { id: number; text: string; done: boolean; due: number | null; all_day: boolean; parent: number | null }

/** The pretend To-do list's tasks, top-level ones with their steps, as `todo.list` answers. */
function todoTree(tasks: PretendTask[], withDone: boolean): Record<string, unknown>[] {
	const shape = (t: PretendTask) => ({ id: t.id, text: t.text, done: t.done, due: t.due, all_day: t.all_day });
	return tasks
		.filter((t) => t.parent === null && (withDone || !t.done))
		.map((t) => ({ ...shape(t), steps: tasks.filter((c) => c.parent === t.id).map(shape) }));
}

/** A zone's UTC offset in seconds at [t] (seconds since 1970), read from the browser's own zone data. */
function zoneOffset(format: Intl.DateTimeFormat, t: number): number {
	const parts: Record<string, number> = {};
	for (const p of format.formatToParts(new Date(t * 1000))) if (p.type !== 'literal') parts[p.type] = Number(p.value);
	const wall = Date.UTC(parts.year, parts.month - 1, parts.day, parts.hour % 24, parts.minute, parts.second) / 1000;
	return Math.round((wall - Math.floor(t)) / 60) * 60;
}

/**
 * `[[from, offset], ...]` for a time zone, as the phone answers `time.zone`:
 * two years back to five ahead, each change found to the minute. The
 * phone's own zone when [name] is empty; null for a name the browser does not know.
 */
function zoneRows(name: string): [number, number][] | null {
	let format: Intl.DateTimeFormat;
	try {
		format = new Intl.DateTimeFormat('en-US', {
			timeZone: name || undefined, hourCycle: 'h23',
			year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric',
		});
	} catch {
		return null;
	}
	const day = 86400;
	const now = Math.floor(Date.now() / 1000);
	let at = now - 2 * 366 * day;
	let offset = zoneOffset(format, at);
	const rows: [number, number][] = [[-62135596800, offset]];
	const until = now + 5 * 366 * day;
	while (at < until) {
		const next = at + day;
		const nextOffset = zoneOffset(format, next);
		if (nextOffset !== offset) {
			let low = at;
			let high = next;
			while (high - low > 60) {
				const mid = Math.floor((low + high) / 2);
				if (zoneOffset(format, mid) === offset) low = mid;
				else high = mid;
			}
			rows.push([high, nextOffset]);
			offset = nextOffset;
		}
		at = next;
	}
	return rows;
}

export function makeBridge(ctx: HostContext): Bridge {
	const field = new PreviewField();
	const secrets = new Map<string, string>();
	const sensors = new Map<string, ReturnType<typeof setInterval>>();
	const tasks: PretendTask[] = [
		{ id: 1, text: 'Reply to the landlord', done: false, due: null, all_day: false, parent: null },
		{ id: 2, text: 'Plan the trip', done: false, due: null, all_day: false, parent: null },
		{ id: 3, text: 'Book the train', done: true, due: null, all_day: false, parent: 2 },
		{ id: 4, text: 'Find a hotel', done: false, due: null, all_day: false, parent: 2 },
	];
	let nextTask = 5;
	let nextEvent = 5000;
	const later = (token: string | null, value: unknown, ms: number) => {
		if (token) setTimeout(() => ctx.deliver({ type: 'result', token, value }), ms);
	};
	const bridge = (op: string, argsJson: string): string => {
		let args: Args = {};
		try {
			args = JSON.parse(argsJson) as Args;
		} catch {
			/* no arguments */
		}
		const token = typeof args.async === 'string' ? args.async : null;
		switch (op) {
			case 'http.request': {
				const url = requestUrl(args, ctx.hosts);
				if (Array.isArray(url)) return fail(url[0], url[1]);
				return token ? httpAsync(args, url, token, ctx) : httpSync(args, url);
			}
			case 'ui.toast':
				ctx.log(`[toast] ${String(args.text ?? '')}`);
				return reply(null);
			case 'ui.dialog': {
				const title = String(args.title ?? '');
				const text = args.text ? `\n\n${String(args.text)}` : '';
				const buttons = (args.buttons as string[]) ?? ['OK'];
				// A browser dialog has OK and Cancel: OK is the first button, Cancel none.
				const ok = window.confirm(title + text + (buttons.length > 1 ? `\n\n(OK = ${buttons[0]}, Cancel = dismiss)` : ''));
				if (token) setTimeout(() => ctx.deliver({ type: 'result', token, value: ok ? buttons[0] ?? null : null }), 0);
				return reply(null);
			}
			case 'field.insert':
			case 'field.replace': {
				const t = String(args.text ?? '');
				field.text = field.text.slice(0, field.cursor) + t + field.text.slice(field.cursor);
				field.cursor += t.length;
				field.describe(ctx, op === 'field.insert' ? 'insert' : 'replace');
				return reply(null);
			}
			case 'field.delete': {
				const before = Math.max(0, Number(args.before) || 0);
				const after = Math.max(0, Number(args.after) || 0);
				const start = Math.max(0, field.cursor - before);
				field.text = field.text.slice(0, start) + field.text.slice(field.cursor + after);
				field.cursor = start;
				field.describe(ctx, 'delete');
				return reply(null);
			}
			case 'field.move':
				field.cursor = Math.min(field.text.length, Math.max(0, field.cursor + (Number(args.by) || 0)));
				field.describe(ctx, 'move');
				return reply(null);
			case 'field.action':
				field.describe(ctx, 'send');
				return reply(null);
			case 'field.selection':
				return reply('');
			case 'field.before':
				return reply(field.text.slice(Math.max(0, field.cursor - (Number(args.n) || 1000)), field.cursor));
			case 'field.after':
				return reply(field.text.slice(field.cursor, field.cursor + (Number(args.n) || 1000)));
			case 'field.info':
				return reply({ kind: 'text', app: 'web preview', multiline: true, action: 'none' });
			case 'field.image':
				ctx.log('[field] an image');
				return reply(null);
			case 'clipboard.set':
				void navigator.clipboard?.writeText(String(args.text ?? '')).catch(() => undefined);
				return reply(null);
			case 'clipboard.get':
				ctx.log('[clipboard] pretend clip (the preview cannot read your clipboard)');
				return reply('Text the user copied');
			case 'device.vibrate':
				navigator.vibrate?.(Number(args.ms) || 30);
				return reply(null);
			case 'device.speak':
				if ('speechSynthesis' in window) {
					const utterance = new SpeechSynthesisUtterance(String(args.text ?? ''));
					if (typeof args.language === 'string' && args.language) utterance.lang = args.language;
					window.speechSynthesis.speak(utterance);
				}
				return reply(null);
			case 'device.battery':
				return reply({ level: 76, charging: false });
			case 'device.flashlight':
				ctx.log(`[flashlight] ${args.on ? 'on' : 'off'}`);
				return reply(null);
			case 'share.text':
				if (navigator.share) void navigator.share({ text: String(args.text ?? '') }).catch(() => undefined);
				else ctx.log(`[share] ${String(args.text ?? '')}`);
				return reply(null);
			case 'share.open': {
				const url = String(args.url ?? '');
				if (!url.startsWith('https://')) return fail('ValueError', 'only https addresses');
				window.open(url, '_blank', 'noopener');
				return reply(null);
			}
			case 'share.visit': {
				const url = String(args.url ?? '');
				if (!/^https?:\/\/[^/@\s]+/i.test(url)) return fail('ValueError', 'only http and https links can be opened');
				window.open(url, '_blank', 'noopener');
				return reply(null);
			}
			case 'clock.alarm': {
				const at = Number(args.at);
				if (!Number.isFinite(at)) return fail('ValueError', 'at must be a number of seconds');
				const now = Date.now() / 1000;
				if (at < now + 60) return fail('ValueError', 'an alarm must be at least a minute away');
				if (at > now + 86400) return fail('ValueError', 'an alarm rings within the next 24 hours; use reminders for later');
				const when = new Date(at * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
				ctx.log(`[clock] alarm at ${when}${args.label ? `: ${String(args.label)}` : ''} (the phone adds it to its Clock)`);
				return reply(null);
			}
			case 'clock.timer': {
				const seconds = Number(args.seconds);
				if (!Number.isFinite(seconds) || seconds < 1 || seconds > 86400) return fail('ValueError', 'a timer runs from 1 second to 24 hours');
				ctx.log(`[clock] ${Math.round(seconds)} s timer${args.label ? `: ${String(args.label)}` : ''} (the phone starts it in its Clock)`);
				return reply(null);
			}
			case 'time.zone': {
				const rows = zoneRows(String(args.zone ?? ''));
				return rows ? reply({ zone: String(args.zone ?? ''), rows }) : fail('ValueError', `unknown time zone: ${String(args.zone).slice(0, 64)}`);
			}
			case 'notify.show':
				ctx.log(`[notification] ${args.title ? `${String(args.title)}: ` : ''}${String(args.text ?? '')}`);
				return reply(null);
			case 'sensors.unsubscribe': {
				const t = sensors.get(String(args.token));
				if (t) clearInterval(t);
				sensors.delete(String(args.token));
				return reply(null);
			}
			case 'notify.cancel':
			case 'plugin.refresh':
				return reply(null);
			case 'plugin.close':
				ctx.log('[closed the panel]');
				return reply(null);
			case 'plugin.open_settings':
				// No settings page here: the preview runs on each setting's default.
				ctx.log('[opened the plugin\'s settings page; the preview uses the defaults in plugin.json]');
				return reply(null);
			case 'keyboard.use': {
				// The preview has no keys to change: it names the layout the
				// keyboard would show, as the phone answers.
				const tag = String(args.language ?? '').trim();
				if (!tag) return fail('ValueError', 'keyboard.use_language needs a language tag, such as "ja"');
				let name = tag;
				try {
					name = new Intl.DisplayNames(['en'], { type: 'language' }).of(tag) ?? tag;
				} catch {
					// Not a tag the browser knows: keep it as typed.
				}
				ctx.log(`[keyboard] ${name} keys while the panel is open`);
				return reply(name);
			}
			case 'keyboard.restore':
				ctx.log('[keyboard] back to your own layout');
				return reply(null);
			case 'sensors.list':
				return reply(PRETEND_SENSORS);
			case 'sensors.subscribe': {
				const kind = String(args.kind ?? '');
				if (!token) return fail('ValueError', 'sensors.subscribe needs a callback');
				if (!PRETEND_SENSORS.includes(kind)) {
					later(token, { error: { kind: 'ValueError', message: `no "${kind}" sensor; try ${PRETEND_SENSORS.join(', ')}` } }, 0);
					return reply(null);
				}
				const hz = Math.max(1, Math.min(Number(args.hz) || 5, 30));
				const started = performance.now();
				let n = 0;
				ctx.log(`[sensors] pretend ${kind} readings, ${hz} a second`);
				sensors.set(token, setInterval(() => {
					n++;
					ctx.deliver({ type: 'stream', token, value: pretendReading(kind, (performance.now() - started) / 1000, n) });
				}, 1000 / hz));
				return reply(null);
			}
			case 'ai.ask':
			case 'ai.stream': {
				const text = pretendReply(String(args.prompt ?? ''));
				ctx.log('[ai] pretend reply (no model in the web preview)');
				if (token && op === 'ai.stream') {
					// ai.stream: the reply a few words at a time, then the whole of it.
					const words = text.split(/(?<= )/);
					words.forEach((w, i) => setTimeout(() => ctx.deliver({ type: 'stream', token, value: w }), 120 + i * 45));
					later(token, { text }, 160 + words.length * 45);
					return reply(null);
				}
				if (token) {
					later(token, { text }, 600);
					return reply(null);
				}
				return reply({ text });
			}
			case 'location.get': {
				const fine = args.accuracy === 'fine';
				const here = { ...PRETEND_PLACE, accuracy: fine ? 15 : 1500, time: Date.now() / 1000 };
				ctx.log(`[location] pretend place: ${here.lat}, ${here.lon}`);
				if (token) {
					later(token, here, 400);
					return reply(null);
				}
				return reply(here);
			}
			case 'calendar.events': {
				const start = Number(args.start) || 0;
				const end = Number(args.end) || 0;
				if (end - start > 62 * 86400) return fail('ValueError', 'ask for at most 62 days of events at a time');
				const events = pretendEvents(start, end);
				ctx.log(`[calendar] ${events.length} pretend events`);
				if (token) {
					later(token, events, 200);
					return reply(null);
				}
				return reply(events);
			}
			case 'calendar.calendars':
				return reply([{ id: 1, name: 'Personal', account: 'you@example.com', primary: true }]);
			case 'calendar.add':
				ctx.log(`[calendar] would add “${String(args.title ?? '')}” to the phone's calendar`);
				return reply(nextEvent++);
			case 'todo.list':
				return reply(todoTree(tasks, args.done !== false));
			case 'todo.add': {
				const text = String(args.text ?? '').trim();
				if (!text) return fail('ValueError', 'a task needs text');
				const parentId = typeof args.parent === 'number' ? args.parent : null;
				const parent = parentId === null ? null : tasks.find((t) => t.id === parentId);
				if (parentId !== null && !parent) return fail('ValueError', `no task ${parentId}`);
				const task: PretendTask = { id: nextTask++, text, done: false, due: typeof args.due === 'number' ? args.due : null, all_day: args.all_day === true, parent: parent ? parent.parent ?? parent.id : null };
				tasks.push(task);
				ctx.log(`[todo] added “${text}” to the pretend list`);
				return reply({ id: task.id, text: task.text, done: task.done, due: task.due, all_day: task.all_day, steps: [] });
			}
			case 'todo.update': {
				const task = tasks.find((t) => t.id === args.id);
				if (!task) return reply(null);
				if (typeof args.text === 'string' && args.text.trim()) task.text = args.text.trim();
				if (typeof args.done === 'boolean') {
					task.done = args.done;
					if (task.parent === null) for (const c of tasks) if (c.parent === task.id && args.done) c.done = true;
				}
				if ('due' in args) {
					task.due = typeof args.due === 'number' ? args.due : null;
					task.all_day = args.all_day === true;
				}
				return reply(todoTree(tasks, true).find((t) => t.id === task.id) ?? { id: task.id, text: task.text, done: task.done, due: task.due, all_day: task.all_day, steps: [] });
			}
			case 'todo.remove': {
				const i = tasks.findIndex((t) => t.id === args.id);
				if (i < 0) return reply(false);
				const id = tasks[i]!.id;
				for (let j = tasks.length - 1; j >= 0; j--) if (tasks[j]!.id === id || tasks[j]!.parent === id) tasks.splice(j, 1);
				return reply(true);
			}
			case 'files.pick': {
				const file = pretendFile(String(args.kind ?? 'any'));
				if (!file) {
					later(token, { error: { kind: 'NotImplementedError', message: 'The web preview has no pretend video. Install the plugin to pick one on the phone.' } }, 0);
					return reply(null);
				}
				ctx.log(`[files] the user "picked" ${file.name}`);
				later(token, file, 500);
				return reply(null);
			}
			case 'files.photo': {
				ctx.log('[files] pretend photo from the camera');
				later(token, { name: 'photo.png', mime: 'image/png', data: pretendImage() }, 700);
				return reply(null);
			}
			case 'files.save':
				ctx.log(`[files] would save ${String(args.name ?? '')} (${sizeOf(args.data)} bytes); the user picks where on the phone`);
				later(token, null, 300);
				return reply(null);
			case 'scan.qr':
				ctx.log('[scan] pretend QR code');
				later(token, 'https://wmkeyboard.app', 700);
				return reply(null);
			case 'scan.text':
				ctx.log('[scan] pretend text from the camera');
				later(token, 'Open 9 am to 6 pm, closed on Fridays', 900);
				return reply(null);
			case 'secrets.get':
				return reply(secrets.get(String(args.name)) ?? null);
			case 'secrets.set':
				secrets.set(String(args.name), String(args.value ?? ''));
				return reply(null);
			case 'secrets.remove':
				secrets.delete(String(args.name));
				return reply(null);
			case 'settings.set': {
				const key = String(args.key ?? '');
				const spec = (ctx.settings ?? []).find((s) => s.key === key);
				if (!spec) return fail('ValueError', `the manifest declares no setting '${key}'`);
				if (spec.type === 'host') return fail('PermissionError', "a plugin can't change its own host settings: only the user types a server address");
				const value = args.value ?? null;
				const text = value === null ? null : storedText(spec, value);
				if (value !== null && text === null) return fail('ValueError', `setting '${key}' doesn't take ${JSON.stringify(value)}`);
				if (spec.type === 'secret') {
					if (text) secrets.set(key, text); else secrets.delete(key);
				}
				ctx.log(`[settings] ${key} = ${spec.type === 'secret' ? (text ? '(saved)' : '(removed)') : text ?? `${spec.default ?? ''} (default)`}`);
				return reply(settingValue(spec, text ?? spec.default ?? ''));
			}
			case 'ai.available':
				return reply(true);
			default: {
				const message = `“${op}”`;
				if (token) {
					setTimeout(() => ctx.deliver({ type: 'result', token, value: { error: { kind: 'NotImplementedError', message: `${message} is not available in the web preview. Install the plugin to try it on the phone.` } } }), 0);
					return reply(null);
				}
				return phoneOnly(message);
			}
		}
	};
	return Object.assign(bridge, {
		dispose: () => {
			for (const t of sensors.values()) clearInterval(t);
			sensors.clear();
		},
	});
}

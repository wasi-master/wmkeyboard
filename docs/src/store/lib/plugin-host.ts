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
 * scanners, the clipboard's contents) gets a pretend answer of the same
 * shape, marked in the console, so a plugin's code paths can run here.
 */

export interface HostContext {
	/** The hosts `http` may reach, as the manifest lists them. */
	hosts: string[];
	/** Hands an event back to the session: `{"type": "result", "token", "value"}`. */
	deliver: (event: Record<string, unknown>) => void;
	/** A line in the editor's console. */
	log: (text: string) => void;
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

function requestUrl(args: Args, hosts: string[]): URL | string {
	let url: URL;
	try {
		url = new URL(String(args.url ?? ''));
	} catch {
		return 'not a web address';
	}
	if (url.protocol !== 'https:') return 'only https addresses';
	if (url.username || url.password) return 'no user name in the address';
	if (/^[\d.]+$/.test(url.hostname) || url.hostname.includes(':') || url.hostname === 'localhost') return 'no IP addresses or localhost';
	if (!hostAllowed(url.hostname, hosts)) return `${url.hostname} is not in the manifest's "hosts"`;
	const params = args.params as Record<string, string> | undefined;
	if (params) for (const [k, v] of Object.entries(params)) url.searchParams.append(k, String(v));
	return url;
}

function bodyOf(args: Args): { body: string | null; type: string | null } {
	if (typeof args.body === 'string') return { body: args.body, type: null };
	if (args.form && typeof args.form === 'object') {
		return { body: new URLSearchParams(args.form as Record<string, string>).toString(), type: 'application/x-www-form-urlencoded' };
	}
	return { body: null, type: null };
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

export function makeBridge(ctx: HostContext): Bridge {
	const field = new PreviewField();
	const secrets = new Map<string, string>();
	const sensors = new Map<string, ReturnType<typeof setInterval>>();
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
				if (typeof url === 'string') return fail('PermissionError', url);
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
				if ('speechSynthesis' in window) window.speechSynthesis.speak(new SpeechSynthesisUtterance(String(args.text ?? '')));
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

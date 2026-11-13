/**
 * The web preview's answers to API 5 host calls (PluginHost.kt and the
 * keyboard's PluginKeyboardServices, as far as a browser can stand in).
 *
 * The interpreter has already refused anything the manifest does not
 * declare. Like the editor on the phone, the preview treats the risky
 * permissions a draft declares as granted. What a browser can do, it does:
 * https requests to the manifest's hosts (if the site allows other pages to
 * read it), a pretend text field, toasts, dialogs, speech and sharing.
 * The rest (AI, location, files, sensors, scanners, the clipboard's
 * contents) answers that it needs the phone.
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

export function makeBridge(ctx: HostContext): (op: string, argsJson: string) => string {
	const field = new PreviewField();
	const secrets = new Map<string, string>();
	return (op, argsJson) => {
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
				return phoneOnly('Reading the clipboard');
			case 'device.vibrate':
				navigator.vibrate?.(Number(args.ms) || 30);
				return reply(null);
			case 'device.speak':
				if ('speechSynthesis' in window) window.speechSynthesis.speak(new SpeechSynthesisUtterance(String(args.text ?? '')));
				return reply(null);
			case 'device.battery':
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
			case 'notify.cancel':
			case 'sensors.unsubscribe':
			case 'plugin.refresh':
				return reply(null);
			case 'plugin.close':
				ctx.log('[closed the panel]');
				return reply(null);
			case 'sensors.list':
				return reply([]);
			case 'secrets.get':
				return reply(secrets.get(String(args.name)) ?? null);
			case 'secrets.set':
				secrets.set(String(args.name), String(args.value ?? ''));
				return reply(null);
			case 'secrets.remove':
				secrets.delete(String(args.name));
				return reply(null);
			case 'ai.available':
				return reply(false);
			default: {
				const what: Record<string, string> = {
					ai: 'The AI model', location: 'Location', files: 'Picking and saving files', sensors: 'Sensors', scan: 'The scanners', device: 'This device call',
				};
				const area = op.split('.')[0];
				const message = what[area] ?? `“${op}”`;
				if (token) {
					setTimeout(() => ctx.deliver({ type: 'result', token, value: { error: { kind: 'NotImplementedError', message: `${message} is not available in the web preview. Install the plugin to try it on the phone.` } } }), 0);
					return reply(null);
				}
				return phoneOnly(message);
			}
		}
	};
}

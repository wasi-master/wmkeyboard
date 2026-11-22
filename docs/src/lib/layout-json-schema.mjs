/**
 * The layout JSON format, read at build time from the app's own Kotlin model,
 * for the reference on the custom-layouts page.
 *
 * Parsed from source rather than written out in the page, for the reason the
 * app builds its JSON editor from the serial descriptors: every property, enum
 * value and action tag comes from the code the decoder runs, so a field added
 * to `Key` shows up here on the next docs build and a renamed one cannot linger.
 * The paragraphs are `LayoutJsonDocs`, the same ones the editor's doc strip
 * shows, and the hidden fields, bounds and examples are `LayoutJsonHints`'.
 *
 * Throws when a file or a declaration is missing, so a moved model fails the
 * docs build instead of quietly publishing an empty reference.
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

/**
 * The repository root, found from the working directory: at render time this
 * module is a chunk inside `dist/.prerender/`, so a path built from
 * `import.meta.url` lands in `docs/` and every read misses.
 */
function findRepoRoot() {
	let dir = process.cwd();
	for (let i = 0; i < 5; i += 1) {
		if (existsSync(join(dir, 'settings.gradle.kts'))) return dir;
		const parent = dirname(dir);
		if (parent === dir) break;
		dir = parent;
	}
	return join(process.cwd(), '..');
}

const ROOT = findRepoRoot();
const read = (path) => readFileSync(join(ROOT, path), 'utf8');

const CORE = 'core/language/src/main/java/com/wasimaster/wmkeyboard/core/';
const SETTINGS = 'core/common/src/main/java/com/wasimaster/wmkeyboard/core/settings/';
const FILES = {
	spec: `${CORE}layout/LayoutSpec.kt`,
	sequence: `${CORE}layout/SequenceRules.kt`,
	keyboard: `${CORE}layout/KeyboardLayout.kt`,
	actions: `${CORE}layout/KeyActions.kt`,
	panel: `${CORE}layout/PanelLayoutSpec.kt`,
	inputMethod: `${CORE}layout/InputMethodLayout.kt`,
	repair: `${CORE}layout/LayoutRepair.kt`,
	script: `${CORE}script/Script.kt`,
	docs: `${CORE}layout/json/LayoutJsonDocs.kt`,
	schema: `${CORE}layout/json/LayoutJsonSchema.kt`,
	tool: `${SETTINGS}ToolbarTool.kt`,
	textEdit: `${SETTINGS}TextEditLayout.kt`,
	icons: 'feature/ime/src/main/java/com/wasimaster/wmkeyboard/ime/ui/KeyIcons.kt',
};

function must(value, what) {
	if (value == null) throw new Error(`layout-json-schema: could not find ${what}`);
	return value;
}

/** The text between the bracket at [open] and its partner, strings skipped. */
function balanced(src, open) {
	const pairs = { '(': ')', '{': '}', '[': ']' };
	const stack = [];
	for (let i = open; i < src.length; i++) {
		const c = src[i];
		if (c === '"') {
			for (i++; i < src.length && src[i] !== '"'; i++) if (src[i] === '\\') i++;
			continue;
		}
		if (c === '/' && src[i + 1] === '/') {
			i = src.indexOf('\n', i);
			if (i < 0) break;
			continue;
		}
		if (c === '/' && src[i + 1] === '*') {
			i = src.indexOf('*/', i) + 1;
			continue;
		}
		if (pairs[c]) stack.push(pairs[c]);
		else if (c === stack[stack.length - 1]) {
			stack.pop();
			if (stack.length === 0) return src.slice(open + 1, i);
		}
	}
	throw new Error('layout-json-schema: unbalanced brackets');
}

/** Splits on commas outside brackets and strings. */
function splitTop(text) {
	const parts = [];
	let depth = 0;
	let start = 0;
	for (let i = 0; i < text.length; i++) {
		const c = text[i];
		if (c === '"') {
			for (i++; i < text.length && text[i] !== '"'; i++) if (text[i] === '\\') i++;
		} else if ('([{<'.includes(c)) depth++;
		else if (')]}>'.includes(c)) depth--;
		else if (c === ',' && depth === 0) {
			parts.push(text.slice(start, i));
			start = i + 1;
		}
	}
	parts.push(text.slice(start));
	return parts.map((p) => p.trim()).filter(Boolean);
}

const stripLineComments = (text) => text.replace(/\/\/[^\n]*/g, '');

/** A KDoc block as one plain sentence run: no stars, no [links], no issue numbers. */
function kdoc(block) {
	return block
		.replace(/^\/\*\*|\*\/$/g, '')
		.split('\n')
		.map((line) => line.replace(/^\s*\*\s?/, '').trim())
		.join(' ')
		.replace(/\[([^\]]+)\]/g, '$1')
		.replace(/\s*\((?:issue )?#\d+(?:, [^)]*)?\)/g, '')
		.replace(/\s+/g, ' ')
		.trim();
}

/**
 * The entries of `enum class [name]`, each with its JSON spelling (its
 * `@SerialName`, else its name), its constructor arguments and its KDoc.
 */
function parseEnum(src, name) {
	const m = must(src.match(new RegExp(`enum class ${name}\\b\\s*`)), `enum ${name}`);
	let open = m.index + m[0].length;
	// Skip the constructor: `enum class FlickDirection(val compassDegrees: Float) {`.
	if (src[open] === '(') open += balanced(src, open).length + 2;
	const body = balanced(src, src.indexOf('{', open));
	const entries = [];
	let doc = null;
	let i = 0;
	while (i < body.length) {
		const rest = body.slice(i);
		const ws = rest.match(/^[\s,]+/);
		if (ws) {
			i += ws[0].length;
			continue;
		}
		if (rest.startsWith(';')) break;
		if (rest.startsWith('/**')) {
			const end = rest.indexOf('*/') + 2;
			doc = kdoc(rest.slice(0, end));
			i += end;
			continue;
		}
		if (rest.startsWith('/*')) {
			i += rest.indexOf('*/') + 2;
			continue;
		}
		if (rest.startsWith('//')) {
			const nl = rest.indexOf('\n');
			i += nl < 0 ? rest.length : nl;
			continue;
		}
		const entry = rest.match(/^(?:@SerialName\("([^"]+)"\)\s*)?([A-Z][A-Za-z0-9_]*)/);
		if (!entry) break;
		i += entry[0].length;
		let args = [];
		if (body[i] === '(') {
			const inner = balanced(body, i);
			args = splitTop(inner);
			i += inner.length + 2;
		}
		entries.push({ name: entry[2], value: entry[1] ?? entry[2], args, doc });
		doc = null;
	}
	return entries;
}

/** A Kotlin type as a JSON author reads it. */
function jsonType(type) {
	const t = type.replace(/\?$/, '').trim();
	const list = t.match(/^List<(.+)>$/);
	if (list) return { text: `list of ${jsonType(list[1]).text}`, ref: jsonType(list[1]).ref };
	const map = t.match(/^Map<([^,]+),\s*(.+)>$/);
	if (map) {
		const key = map[1].trim() === 'String' ? 'name' : map[1].trim();
		return { text: `map of ${key} → ${jsonType(map[2]).text}`, ref: jsonType(map[2]).ref ?? (key === 'name' ? null : key) };
	}
	const prim = { String: 'string', Float: 'number', Double: 'number', Int: 'integer', Long: 'integer', Boolean: 'boolean' }[t];
	if (prim) return { text: prim, ref: null };
	return { text: t, ref: t };
}

/** A default value as the JSON a file would hold. */
function jsonDefault(value, constants) {
	if (value == null) return null;
	const v = value.trim();
	if (v === 'emptyList()') return '[]';
	if (v === 'emptyMap()') return '{}';
	if (v === 'null' || v === 'true' || v === 'false') return v;
	if (/^-?\d+(\.\d+)?f?$/.test(v)) return v.replace(/f$/, '');
	if (v === '""') return '""';
	if (v === 'KeyAction.Text') return '{"type": "text"}';
	if (constants[v] != null) return constants[v];
	const enumRef = v.match(/^\w+\.([A-Z_]+)$/);
	if (enumRef) return `"${enumRef[1]}"`;
	return v;
}

/** The constructor parameters of `data class [name]`. */
function parseDataClass(src, name, constants) {
	const at = must(src.match(new RegExp(`data class ${name}\\(`)), `data class ${name}`);
	const params = splitTop(stripLineComments(balanced(src, at.index + at[0].length - 1)).replace(/\/\*[\s\S]*?\*\//g, ''));
	return params.map((param) => {
		const m = must(
			param.match(/^(?:@SerialName\("([^"]+)"\)\s*)?val\s+(\w+):\s*([^=]+?)(?:\s*=\s*([\s\S]+))?$/),
			`a parameter of ${name}: ${param}`,
		);
		const type = m[3].trim();
		return {
			name: m[1] ?? m[2],
			type,
			nullable: type.endsWith('?'),
			required: m[4] == null,
			default: jsonDefault(m[4], constants),
		};
	});
}

/** Every `"key" to "text" + "text"` of LayoutJsonDocs, concatenated and unescaped. */
function parseDocs(src) {
	const docs = {};
	const re = /(?:"([^"]+)"|(ACTION_TYPE)) to\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)/g;
	for (const m of src.matchAll(re)) {
		const key = m[1] ?? 'action.type';
		docs[key] = [...m[3].matchAll(/"((?:[^"\\]|\\.)*)"/g)]
			.map((s) => s[1].replace(/\\(.)/g, '$1'))
			.join('');
	}
	return docs;
}

/** The doc keys `LayoutJsonHints` hides, and the examples it offers per key. */
function parseHints(src) {
	const block = src.slice(must(src.indexOf('object LayoutJsonHints'), 'LayoutJsonHints'));
	const hidden = new Set();
	const examples = {};
	const re = /"([\w.:]+)" to PropertyHint\(/g;
	for (const m of block.matchAll(re)) {
		const args = balanced(block, m.index + m[0].length - 1);
		if (/hidden = true/.test(args)) hidden.add(m[1]);
		const found = [...args.matchAll(/JsonValueExample\("((?:[^"\\]|\\.)*)"(?:, "([^"]*)")?\)/g)].map((e) => ({
			value: e[1].replace(/\\(.)/g, '$1'),
			note: e[2] ?? null,
		}));
		if (found.length && found.every((e) => e.note)) examples[m[1]] = found;
	}
	const tags = must(block.match(/hiddenTags[^=]*= setOf\(([^)]*)\)/), 'hiddenTags')[1];
	return { hidden, examples, hiddenTags: new Set([...tags.matchAll(/"(\w+)"/g)].map((m) => m[1])) };
}

/** A `const val` or `val X = a..b` number from source. */
function constant(src, name) {
	const m = must(src.match(new RegExp(`val ${name}(?::[^=]+)? = ([^\\n]+)`)), `constant ${name}`)[1].trim();
	return m.replace(/f/g, '');
}

/** Every `data object`/`data class` subtype of KeyAction, with its fields. */
function parseActions(src, constants) {
	const actions = [];
	const re = /@SerialName\("(\w+)"\)\s*data (object|class) (\w+)/g;
	for (const m of src.matchAll(re)) {
		let fields = [];
		if (m[2] === 'class') {
			const open = m.index + m[0].length;
			const inner = balanced(src, src.indexOf('(', open));
			// Block comments first: a KDoc on a field (Tool.custom) holds commas
			// splitTop would cut at, and may hold a `//` of a URL.
			fields = splitTop(stripLineComments(inner.replace(/\/\*[\s\S]*?\*\//g, ''))).map((param) => {
				const p = must(param.match(/^val\s+(\w+):\s*([^=]+?)(?:\s*=\s*([\s\S]+))?$/), `field of ${m[3]}`);
				return { name: p[1], type: p[2].trim(), required: p[3] == null, default: jsonDefault(p[3], constants) };
			});
		}
		actions.push({ tag: m[1], className: m[3], fields });
	}
	return actions;
}

/** The short icon names of `KeyIcons.catalog`, and the aliases that point at them. */
function parseIcons(src) {
	const catalogAt = must(src.indexOf('val catalog'), 'KeyIcons.catalog');
	const catalog = balanced(src, src.indexOf('linkedMapOf(', catalogAt) + 'linkedMapOf'.length);
	const names = [...stripLineComments(catalog).matchAll(/"([\w-]+)" to/g)].map((m) => m[1]);
	const aliasAt = src.indexOf('val aliases');
	const aliases = {};
	if (aliasAt >= 0) {
		const body = balanced(src, src.indexOf('(', src.indexOf('mapOf', aliasAt)));
		for (const m of body.matchAll(/"([\w-]+)" to "([\w-]+)"/g)) aliases[m[1]] = m[2];
	}
	return { names, aliases };
}

let cached;

export function layoutJsonSchema() {
	if (cached) return cached;
	const src = Object.fromEntries(Object.entries(FILES).map(([k, path]) => [k, read(path)]));

	const constants = {
		CurrentLayoutSpecVersion: constant(src.spec, 'CurrentLayoutSpecVersion'),
		CurrentPanelLayoutVersion: constant(src.panel, 'CurrentPanelLayoutVersion'),
	};
	const docs = parseDocs(src.docs);
	const hints = parseHints(src.schema);

	const range = (text) => text.split('..').map((n) => n.trim());
	const ranges = {
		'Key.width': ['0.1', constant(src.repair, 'MaxKeyWidth')],
		'Key.rowSpan': ['1', constant(src.repair, 'MaxRowsPerLayer')],
		'Key.alternateColumns': ['0', range(constant(src.keyboard, 'AlternateColumnsRange'))[1]],
		'Key.labelScale': range(constant(src.keyboard, 'KeyLabelScaleRange')),
		'LayerSpec.fontScale': range(constant(src.spec, 'LayoutFontScaleRange')),
		'LayoutAppearance.fontScale': range(constant(src.spec, 'LayoutFontScaleRange')),
		'LayerSpec.rowHeights': [constant(src.keyboard, 'MinRowHeightScale'), constant(src.keyboard, 'MaxRowHeightScale')],
		'action:braille_dot.dot': ['1', '6'],
	};

	const objectOf = (owner, file) =>
		parseDataClass(file, owner, constants)
			.map((p) => ({
				...p,
				docKey: `${owner}.${p.name}`,
				json: jsonType(p.type),
				doc: docs[`${owner}.${p.name}`] ?? null,
				range: ranges[`${owner}.${p.name}`] ?? null,
			}))
			.filter((p) => !hints.hidden.has(p.docKey));

	const objects = {
		LayoutSpec: objectOf('LayoutSpec', src.spec),
		LayerSpec: objectOf('LayerSpec', src.spec).filter((p) => p.name !== 'keymanFrames'),
		Key: objectOf('Key', src.keyboard),
		KeyAlternate: objectOf('KeyAlternate', src.actions),
		LayoutAppearance: objectOf('LayoutAppearance', src.spec),
		PanelLayoutSpec: objectOf('PanelLayoutSpec', src.panel),
		InputMethodBinding: objectOf('InputMethodBinding', src.inputMethod),
		KeymanBinding: objectOf('KeymanBinding', src.spec),
		SequenceRule: objectOf('SequenceRule', src.sequence),
	};

	const actions = parseActions(src.actions, constants)
		.filter((a) => !hints.hiddenTags.has(a.tag))
		.map((a) => ({
			...a,
			doc: docs[`action:${a.tag}`] ?? null,
			fields: a.fields
				.filter((f) => !hints.hidden.has(`action:${a.tag}.${f.name}`))
				.map((f) => ({
					...f,
					json: jsonType(f.type),
					doc: docs[`action:${a.tag}.${f.name}`] ?? null,
					range: ranges[`action:${a.tag}.${f.name}`] ?? null,
				})),
		}));

	const layers = parseEnum(src.spec, 'LayoutLayer').map((e) => ({ value: JSON.parse(e.args[0]), name: e.name }));
	const panels = parseEnum(src.panel, 'PanelKind').map((e) => ({
		value: e.value,
		layerKey: `panel_${e.name.toLowerCase()}`,
		doc: e.doc,
		fullOnly: e.args.some((a) => a.includes('BuildConfig')),
	}));
	const fieldKinds = parseEnum(src.actions, 'PanelFieldKind')
		.filter((e) => e.value !== 'unknown')
		.map((e) => ({ value: e.value, panel: panels.find((p) => p.value && e.args[0]?.endsWith(`.${p.value.toUpperCase()}`))?.value ?? null, doc: e.doc }));

	const enums = {
		ToolbarTool: parseEnum(src.tool, 'ToolbarTool'),
		TextEditAction: parseEnum(src.textEdit, 'TextEditAction'),
		ModifierKey: parseEnum(src.actions, 'ModifierKey'),
		ClipboardKeyAction: parseEnum(src.actions, 'ClipboardKeyAction'),
		KeyRole: parseEnum(src.actions, 'KeyRole'),
		FlickDirection: parseEnum(src.keyboard, 'FlickDirection'),
		InputMethodKind: parseEnum(src.inputMethod, 'InputMethodKind'),
		ComposerType: parseEnum(src.script, 'ComposerType'),
		ScriptId: parseEnum(src.script, 'ScriptId'),
	};

	cached = {
		objects,
		actions,
		actionTypeDoc: docs['action.type'] ?? null,
		layers,
		panels,
		fieldKinds,
		enums,
		keyCodes: hints.examples['action:send_key.keyCode'] ?? [],
		metaFlags: hints.examples['action:send_key.meta'] ?? [],
		icons: parseIcons(src.icons),
	};
	return cached;
}

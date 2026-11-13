/**
 * The plugins to start from (PluginTemplates.kt): the blank one, and the demo
 * plugins the documentation teaches from, read straight from the app's assets
 * so the web editor never carries a stale copy.
 */
import cipher from '../../../../../../app/src/main/assets/plugin-templates/cipher-tool.py?raw';
import textTools from '../../../../../../app/src/main/assets/plugin-templates/text-tools.py?raw';
import todo from '../../../../../../app/src/main/assets/plugin-templates/todo-list.py?raw';
import kitchenSink from '../../../../../../app/src/main/assets/plugin-templates/ui-kitchen-sink.py?raw';
import textGenerator from '../../../../../../app/src/main/assets/plugin-templates/text-generator.py?raw';
import weather from '../../../../../../app/src/main/assets/plugin-templates/weather.py?raw';
import { BLANK_PLUGIN_SCRIPT } from './ide-store';
import { S } from './strings';

export type PluginTemplateId = 'BLANK' | 'TEXT_TOOLS' | 'CIPHER' | 'TODO' | 'KITCHEN_SINK' | 'TEXT_GENERATOR' | 'WEATHER';

export interface PluginTemplate {
	id: PluginTemplateId;
	name: string;
	description: string;
	script: string;
	/** What the script uses beyond its panel, so the new manifest declares it. */
	permissions: string[];
	/** 5 for a template written against the wm package; the others use the API 4 globals. */
	apiVersion?: number;
	hosts?: string[];
	settings?: { key: string; type: string; label: string; default?: string; options?: string[] }[];
}

export const TEMPLATES: PluginTemplate[] = [
	{ id: 'BLANK', name: S.templateBlankName, description: S.templateBlankDescription, script: BLANK_PLUGIN_SCRIPT, permissions: [] },
	{ id: 'TEXT_TOOLS', name: S.templateTextToolsName, description: S.templateTextToolsDescription, script: textTools, permissions: [] },
	{ id: 'CIPHER', name: S.templateCipherName, description: S.templateCipherDescription, script: cipher, permissions: [] },
	{ id: 'TODO', name: S.templateTodoName, description: S.templateTodoDescription, script: todo, permissions: ['storage', 'reminders'] },
	{ id: 'KITCHEN_SINK', name: S.templateKitchenSinkName, description: S.templateKitchenSinkDescription, script: kitchenSink, permissions: [] },
	{ id: 'TEXT_GENERATOR', name: S.templateTextGeneratorName, description: S.templateTextGeneratorDescription, script: textGenerator, permissions: [], apiVersion: 5 },
	{
		id: 'WEATHER', name: S.templateWeatherName, description: S.templateWeatherDescription, script: weather, permissions: ['network', 'storage'], apiVersion: 5,
		hosts: ['api.open-meteo.com', 'geocoding-api.open-meteo.com'],
		settings: [{ key: 'units', type: 'choice', label: 'Units', default: 'metric', options: ['metric', 'imperial'] }],
	},
];

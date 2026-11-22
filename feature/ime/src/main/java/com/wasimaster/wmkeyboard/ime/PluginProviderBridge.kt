package com.wasimaster.wmkeyboard.ime

import android.content.Context
import com.wasimaster.wmkeyboard.core.net.NetworkWatcher
import com.wasimaster.wmkeyboard.core.plugins.PluginProviderRunner
import com.wasimaster.wmkeyboard.core.plugins.PluginReminderAlarms
import com.wasimaster.wmkeyboard.core.plugins.PluginServices
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.plugins.displayName
import com.wasimaster.wmkeyboard.core.plugins.resolve
import com.wasimaster.wmkeyboard.core.providers.PluginProviders
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Puts the installed plugins' providers in the provider lists
 * ([PluginProviders]): their rows by name, and a call that runs the plugin.
 *
 * Here because this module is the one that sees both the plugins and the
 * lists. [start] runs once a process, from the application, so the settings
 * screens list plugin rows with no keyboard on screen; their calls (a
 * provider's Test, a look-up from another app) get the network and the AI
 * and nothing that needs a field.
 */
object PluginProviderBridge {

    @Volatile
    private var settings: KeyboardSettings? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Once a process. Keeps a copy of the settings, which the calls' Data saver and AI read. */
    fun start(context: Context, repository: SettingsRepository) {
        val app = context.applicationContext
        scope.launch { repository.settings.collect { settings = it } }
        install(app, background(app))
    }

    /** The directory, asking through [services]. */
    private fun install(context: Context, services: PluginServices) {
        val store = PluginStore.get(context)
        val runner = PluginProviderRunner(
            store = store,
            services = services,
            onRemindersChanged = { PluginReminderAlarms.requestRearm(context) },
            words = { it.resolve(context) },
        )
        PluginProviders.install(
            object : PluginProviders.Directory {
                override fun rows(): List<PluginProviders.Row> {
                    val on = store.subsystemEnabled()
                    return store.providers().mapNotNull { ref ->
                        val tool = ProviderTool.entries.firstOrNull { it.id == ref.provider.tool } ?: return@mapNotNull null
                        PluginProviders.Row(
                            key = ref.key,
                            tool = tool,
                            name = ref.provider.name.ifBlank { ref.plugin.displayName },
                            summary = ref.provider.summary,
                            pluginName = ref.plugin.displayName,
                            runnable = on && ref.plugin.enabled,
                            images = ref.provider.images,
                        )
                    }
                }

                override fun call(key: String, request: JsonObject, isActive: () -> Boolean): JsonElement =
                    runner.call(key, request, isActive)
            },
        )
    }

    /**
     * The same services for the plugin editor, whose preview and Try pane run a
     * draft with no keyboard on screen: the network and the AI, under the
     * draft's own permissions, hosts and Data saver.
     */
    fun editorServices(context: Context): PluginServices = background(context.applicationContext)

    /**
     * The services a provider call gets away from a field: the network, the
     * AI, the plugin's storage and secrets. Everything that needs the field
     * or the keyboard on screen answers that it cannot here.
     */
    private fun background(context: Context): PluginServices {
        val network = NetworkWatcher(context)
        return PluginKeyboardServices(
            context = context,
            connection = { null },
            // Nothing is read from a field, so nothing in one is at risk.
            protected = { false },
            dataSaver = {
                val current = settings
                current != null && current.dataSaver.appliesTo(network.apply { refresh() }.state.value)
            },
            aiSettings = { settings?.ai ?: KeyboardSettings().ai },
            clipboardText = { null },
            copy = {},
            toast = {},
            vibrate = {},
            closePanel = {},
            startHandoff = { false },
            handoffDone = {},
            useLanguage = { _, _ -> null },
            restoreLayout = {},
            openSettings = {},
            commitImage = { _, _ -> },
            localModel = { null },
        )
    }
}

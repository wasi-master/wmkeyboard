package com.wasimaster.wmkeyboard.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.KeyboardAutomation

/**
 * A launcher's own "add a shortcut" list (#970): the only way a secondary
 * launcher can add one, since it never sees a pin request. Lists the tools with
 * a panel and hands back what [ToolHomeScreenShortcutRow] would have pinned.
 */
class ToolShortcutPickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = SettingsRepository(applicationContext)
        val tools = ToolbarTool.entries.filter { KeyboardAutomation.panelTool(it.name) != null }
        setContent {
            val stored = repository.settings
                .collectAsStateWithLifecycle(null as KeyboardSettings?)
            val deviceForm = DeviceForm.of(LocalConfiguration.current.smallestScreenWidthDp)
            val settings = rememberLiveSettings(stored, deviceForm) ?: return@setContent
            AppTheme(settings) {
                Surface(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.safeDrawingPadding()) {
                        items(tools, key = { it.name }) { tool ->
                            val name = stringResource(toolTitle(tool))
                            val icon = rememberToolIcon(tool)
                            WmRow(title = name, onClick = {
                                val shortcut = ShortcutInfoCompat.Builder(this@ToolShortcutPickerActivity, "panel_${tool.name}")
                                    .setShortLabel(name)
                                    .setIcon(icon())
                                    .setIntent(ToolShortcutActivity.intent(this@ToolShortcutPickerActivity, tool))
                                    .build()
                                setResult(
                                    RESULT_OK,
                                    ShortcutManagerCompat.createShortcutResultIntent(this@ToolShortcutPickerActivity, shortcut),
                                )
                                finish()
                            })
                        }
                    }
                }
            }
        }
    }
}

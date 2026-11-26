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
import androidx.core.graphics.drawable.IconCompat
import com.wasimaster.wmkeyboard.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository

/**
 * A launcher's own "add a shortcut" list (#970): the only way a secondary
 * launcher can add one, since it never sees a pin request. Lists the tools with
 * a panel and hands back what [ToolHomeScreenShortcutRow] would have pinned.
 *
 * The settings pages the app icon's menu can hold come first (#1288). A
 * launcher that is not the default home app may not start an app's shortcuts,
 * so one it copied from that menu opened the app on its home page instead.
 * Handed back from here, the shortcut is a plain intent the launcher starts
 * itself, and lands on the page it names.
 */
class ToolShortcutPickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = SettingsRepository(applicationContext)
        val tools = ToolLaunch.tools
        setContent {
            val stored = repository.settings
                .collectAsStateWithLifecycle(null as KeyboardSettings?)
            val deviceForm = DeviceForm.of(LocalConfiguration.current.smallestScreenWidthDp)
            val settings = rememberLiveSettings(stored, deviceForm) ?: return@setContent
            AppTheme(settings) {
                Surface(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.safeDrawingPadding()) {
                        item(key = "pages_header") { SectionHeader(stringResource(R.string.home_shortcut_picker_pages)) }
                        items(AppIconMenu.Page.entries, key = { "page_${it.route}" }) { page ->
                            val name = stringResource(page.shortLabel)
                            WmRow(title = name, onClick = { handBack(page, name) })
                        }
                        item(key = "tools_header") { SectionHeader(stringResource(R.string.shortcut_tools_long)) }
                        items(tools, key = { it.name }) { tool ->
                            val name = stringResource(toolTitle(tool))
                            WmRow(title = name, onClick = {
                                val context = this@ToolShortcutPickerActivity
                                val shortcut = ShortcutInfoCompat.Builder(context, "panel_${tool.name}")
                                    .setShortLabel(name)
                                    .setIcon(ToolLaunch.icon(context, tool))
                                    .setIntent(ToolLaunch.intent(context, tool))
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

    /** Hands back a shortcut to [page], the same address its line in the app icon's menu opens. */
    private fun handBack(page: AppIconMenu.Page, name: String) {
        val shortcut = ShortcutInfoCompat.Builder(this, "page_${page.route}")
            .setShortLabel(name)
            .setIcon(IconCompat.createWithResource(this, page.icon))
            .setIntent(ToolLaunch.pageIntent(this, page.route))
            .build()
        setResult(RESULT_OK, ShortcutManagerCompat.createShortcutResultIntent(this, shortcut))
        finish()
    }
}

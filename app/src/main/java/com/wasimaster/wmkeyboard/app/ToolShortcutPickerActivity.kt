package com.wasimaster.wmkeyboard.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
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
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.KeyboardAutomation

/**
 * A launcher's own "add a shortcut" list (#970): the only way a secondary
 * launcher can add one, since it never sees a pin request. Lists the tools with
 * a panel and hands back what [ToolHomeScreenShortcutRow] would have pinned.
 *
 * The settings pages of the app icon's long-press menu come first (#1288). A
 * launcher that is not the default home app may not start an app's shortcuts,
 * so one it copied from that menu opened the app on its home page instead.
 * Handed back from here, the shortcut is a plain intent the launcher starts
 * itself, and lands on the page it names.
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
                        item(key = "pages_header") { SectionHeader(stringResource(R.string.home_shortcut_picker_pages)) }
                        items(Pages, key = { "page_${it.route}" }) { page ->
                            val name = stringResource(page.label)
                            WmRow(title = name, onClick = { handBack(page, name) })
                        }
                        item(key = "tools_header") { SectionHeader(stringResource(R.string.shortcut_tools_long)) }
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

    /** Hands back a shortcut to [page], the same address its long-press entry opens. */
    private fun handBack(page: Page, name: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("wmkeyboard://settings/${page.route}"))
            .setClassName(packageName, MainActivity::class.java.name)
        val shortcut = ShortcutInfoCompat.Builder(this, "page_${page.route}")
            .setShortLabel(name)
            .setIcon(IconCompat.createWithResource(this, page.icon))
            .setIntent(intent)
            .build()
        setResult(RESULT_OK, ShortcutManagerCompat.createShortcutResultIntent(this, shortcut))
        finish()
    }

    /** A settings page of the long-press menu (res/xml/shortcuts.xml). */
    private class Page(val route: String, @StringRes val label: Int, @DrawableRes val icon: Int)

    private companion object {
        /** The long-press menu's pages, in its order (res/xml/shortcuts.xml lists them bottom-up). */
        val Pages = listOf(
            Page("themes", R.string.shortcut_themes_short, R.drawable.ic_shortcut_themes),
            Page("ai_chat", R.string.shortcut_ai_chat_short, R.drawable.ic_shortcut_ai_chat),
            Page("languages", R.string.shortcut_languages_short, R.drawable.ic_shortcut_languages),
            Page("tools", R.string.shortcut_tools_short, R.drawable.ic_shortcut_tools),
        )
    }
}

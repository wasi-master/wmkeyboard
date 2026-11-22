package com.wasimaster.wmkeyboard.app

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The privacy shade, up and down, as a Quick Settings tile (#1428): the same
 * switch as the Privacy shade tool and Tools › Privacy shade › Show the shade.
 *
 * The shade is drawn by the keyboard, so the tile only puts it up for the
 * keyboard's next appearance, or at once when the keyboard is already open.
 * It does not cover anything while the keyboard is closed.
 */
class PrivacyShadeTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        val repository = SettingsRepository(applicationContext)
        scope.launch { draw(repository.settings.first().privacyShade.on) }
    }

    override fun onClick() {
        super.onClick()
        val repository = SettingsRepository(applicationContext)
        scope.launch {
            val on = !repository.settings.first().privacyShade.on
            repository.setPrivacyShadeOn(on)
            draw(on)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun draw(on: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_privacy_shade)
        tile.label = getString(R.string.qs_privacy_shade_label)
        tile.updateTile()
    }
}

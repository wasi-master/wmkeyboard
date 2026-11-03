package com.wasimaster.wmkeyboard.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.lock.AppLockSecretPrompt
import com.wasimaster.wmkeyboard.app.lock.BiometricAppLock
import com.wasimaster.wmkeyboard.app.lock.LocalAppLock
import com.wasimaster.wmkeyboard.app.lock.LockedRoute
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.PlaceWire
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.ui.ScrollRail
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState
import com.wasimaster.wmkeyboard.ime.wmpass.WmPassBridge

/**
 * WM Pass's "Use a place from WM Keyboard": the person picks one of this
 * app's places for copies, and WM Pass gets it back as a [PlaceWire] result.
 *
 * Exported, because WM Pass has to start it, and so it answers only the real
 * WM Pass, by its signing certificate. Anything else that starts it, or
 * starts it without waiting for a result, gets nothing and is told nothing.
 * Nothing goes back until a tap here, the place's logins included, so no app
 * can read the places without the person picking one. It sits behind the
 * app lock's Backup & restore screen, the same as the places themselves.
 */
class PlaceShareActivity : FragmentActivity() {

    private lateinit var repository: SettingsRepository
    private lateinit var appLock: BiometricAppLock

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // [getCallingPackage] is set only for a screen started for a result,
        // which is the one way a place can go back.
        if (!WmPassBridge.isWmPass(this, callingPackage)) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        // A tap is the whole consent here, so one through an overlay is refused.
        window.decorView.filterTouchesWhenObscured = true
        repository = SettingsRepository(applicationContext)
        appLock = BiometricAppLock(this, repository)
        setContent {
            val settings = repository.settings
                .collectAsStateWithLifecycle(null as KeyboardSettings?).value ?: return@setContent
            AppTheme(settings) {
                CompositionLocalProvider(LocalAppLock provides appLock) {
                    PlaceShareSheet(
                        places = settings.autoBackup.locations.filter { PlaceWire.encode(it) != null },
                        onPick = ::give,
                        onDismiss = ::finish,
                    )
                    AppLockSecretPrompt(appLock)
                }
            }
        }
    }

    private fun give(place: BackupLocation) {
        val wire = PlaceWire.encode(place) ?: return
        setResult(RESULT_OK, Intent().putExtra(PlaceWire.EXTRA, wire))
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceShareSheet(places: List<BackupLocation>, onPick: (BackupLocation) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LockedRoute("backup", onCancel = onDismiss) {
            ScrollRail(state = rememberScrollRailState(), fadeColor = BottomSheetDefaults.ContainerColor) {
                Text(
                    stringResource(R.string.wmpass_place_share_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Text(
                    stringResource(if (places.isEmpty()) R.string.wmpass_place_share_empty else R.string.wmpass_place_share_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                for (place in places) {
                    WmRow(
                        title = place.title(context),
                        subtitle = place.detail(context),
                        leading = { WmIconTile(icon = destinationIcon(place.type), accent = place.type.accent) },
                        onClick = { onPick(place) },
                    )
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

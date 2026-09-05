package com.wasimaster.wmkeyboard.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.SpaceBar
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.PersonaLanguages
import kotlinx.coroutines.delay

/**
 * The last page: a real text field, so the keyboard that was just set up
 * appears in its chosen theme and the user types on it before Finish. The
 * hints below the field point at the wow moments the wizard just enabled (a
 * calculation chip, a tool hotword), not at things the keys already show.
 * Nothing typed here is stored anywhere.
 *
 * Everything on this page is packed against the top — a one-line header, a
 * two-line field, then the hints. The keyboard takes the bottom half of the
 * screen the moment this page opens, and a page that put anything below the
 * fold here would be hiding the very prompts it is asking the user to try.
 */
@Composable
internal fun TryPage(settings: KeyboardSettings) {
    val context = LocalContext.current
    // Reachable with the keyboard still not set up (Skip exists, and a replay
    // may start after the keyboard was disabled elsewhere); the field would
    // summon some other keyboard, so offer the setup card instead.
    val setup = rememberKeyboardSetup(context) {}
    if (!setup.ready) {
        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            SetupCard(context, setup = setup)
        }
        CaptionText(stringResource(R.string.onboarding_try_not_ready_body))
        return
    }
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Focused only once the page has finished sliding in. Asking for focus
    // during the turn set three things moving at once — the page sliding
    // across, the window shrinking around the keyboard, and Compose scrolling
    // the newly focused field into view — and the field visibly shot to the
    // top and settled back. Waiting for the slide leaves only the keyboard.
    LaunchedEffect(Unit) {
        delay(NavTransitionMs.toLong())
        focusRequester.requestFocus()
        keyboard?.show()
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text(stringResource(R.string.onboarding_try_field_hint)) },
        minLines = 2,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .focusRequester(focusRequester),
    )
    AnimatedVisibility(
        visible = text.text.isNotBlank(),
        enter = onboardingRevealEnter(settings.reduceMotion),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.onboarding_try_success),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    OnboardingSectionTitle(stringResource(R.string.onboarding_try_hints_title))
    for ((icon, textRes) in tryHints(settings)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(textRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** How many try-this prompts the page offers. */
private const val TRY_HINT_LIMIT = 3

/**
 * The first few tricks worth trying, picked from what is actually on. The
 * smart chips lead because they are the one that makes people stop: an answer
 * appearing over the keys as you type sells the keyboard better than any page
 * of copy.
 */
private fun tryHints(settings: KeyboardSettings): List<Pair<ImageVector, Int>> = buildList {
    if (settings.smartSuggestions) {
        add(Icons.Outlined.Calculate to R.string.onboarding_try_hint_chips)
    }
    if (settings.smartToolKeywords) {
        add(Icons.Outlined.Bolt to R.string.onboarding_try_hint_hotword)
    }
    add(Icons.Outlined.Widgets to R.string.onboarding_try_hint_toolbox)
    if (settings.clipboard.history) {
        add(Icons.Outlined.ContentPaste to R.string.onboarding_try_hint_clipboard)
    }
    if (settings.onboarding.personaLanguages == PersonaLanguages.MANY) {
        add(Icons.Outlined.SpaceBar to R.string.onboarding_try_hint_spacebar)
    }
}.take(TRY_HINT_LIMIT)

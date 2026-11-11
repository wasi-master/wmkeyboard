package com.wasimaster.wmkeyboard.ime.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The Retype bar button (#1189) is an icon (#1231) that still speaks its name,
 * acts as a button with its own action label, keeps a full touch target, and
 * runs Retype once a press, in both directions (#1192).
 */
@RunWith(RobolectricTestRunner::class)
class RetypeBarButtonTest {

    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(
            object : ExternalResource() {
                override fun before() {
                    val app = RuntimeEnvironment.getApplication()
                    shadowOf(app.packageManager)
                        .addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                }
            },
        )
        .around(compose)

    private fun show(direction: LayoutDirection): () -> Int {
        var presses = 0
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Box(Modifier.height(48.dp)) {
                    RetypeBarButton(SuggestionHoldCallbacks(onRetype = { presses++ }))
                }
            }
        }
        return { presses }
    }

    private fun checkButton(direction: LayoutDirection) {
        val presses = show(direction)
        val name = RuntimeEnvironment.getApplication().getString(com.wasimaster.wmkeyboard.ime.R.string.ime_retype_button)
        // No visible word: the name is the icon's description only.
        compose.onAllNodesWithText(name).assertCountEquals(0)
        val node = compose.onNodeWithContentDescription(name, useUnmergedTree = false)
        node.assertHasClickAction()
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        val label = node.fetchSemanticsNode().config[SemanticsActions.OnClick].label
        assertEquals(
            RuntimeEnvironment.getApplication().getString(com.wasimaster.wmkeyboard.ime.R.string.ime_retype_button_desc),
            label,
        )
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        assertTrue("touch width ${bounds.width / density}dp", bounds.width / density >= 48f - 0.5f)
        node.performClick()
        assertEquals(1, presses())
    }

    @Test
    fun `an icon button with its name, left to right`() = checkButton(LayoutDirection.Ltr)

    @Test
    fun `an icon button with its name, right to left`() = checkButton(LayoutDirection.Rtl)
}

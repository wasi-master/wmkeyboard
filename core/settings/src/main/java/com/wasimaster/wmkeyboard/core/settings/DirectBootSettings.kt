package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.theme.ThemeSpec

/**
 * The settings as they apply during direct boot — before the user has unlocked
 * the device for the first time since it booted.
 *
 * Everything switched off here is switched off because the thing behind it is
 * *unreadable*, not because it would be rude to show: the fonts, theme images
 * and word lists live in credential-encrypted storage, the contact and app
 * lists behind providers that are themselves locked. Turning them off in the
 * settings object rather than at each use site means every downstream reader —
 * the toolbar, the toolbox, the hardware-keyboard shortcuts, the tool tap
 * handler, the renderer — agrees on what exists without any of them having to
 * know about direct boot.
 *
 * This is a view, never persisted: it is applied on the way out of the
 * repository, and the moment the user unlocks, the unrestricted settings emit
 * again and everything comes back.
 */
fun KeyboardSettings.restrictedToDirectBoot(): KeyboardSettings {
    val tools = enabledTools.filter(::isDirectBootSafeTool)
    return copy(
        enabledTools = tools,
        toolbarTools = toolbarTools.filter(::isDirectBootSafeTool),
        toolboxOrder = toolboxOrder.filter(::isDirectBootSafeTool),
        // A custom theme's background image is an absolute path under filesDir.
        // Drop the path and the theme still renders — as its own colors, with
        // no image behind them — instead of the loader failing per frame. The
        // photographer's credit goes with it: naming whose photo this is while
        // the photo is not on screen would simply be untrue.
        customThemes = customThemes.map { it.strippedForDirectBoot() },
        // The rotation pool is under filesDir as well, so there is nothing to
        // rotate to. Turning it off here means nothing downstream has to ask.
        photoBackground = photoBackground.copy(rotateEnabled = false),
        // Imported font files are in filesDir; the Google Fonts families come
        // from a GMS content provider that is itself locked. Both fall back to
        // the system face rather than to no glyphs at all.
        keyFontId = "default",
        scriptFontIds = emptyMap(),
        emojiFont = EmojiFontChoice.SYSTEM,
        // An installed key sound is a file under filesDir too, and a sound pack
        // is a whole directory of them. Unlike the fonts there is no per-glyph
        // fallback to hide it — the style would simply make no sound — so both
        // drop back to the system click.
        keySoundStyle = if (keySoundStyle == KeySoundStyle.CUSTOM || keySoundStyle == KeySoundStyle.PACK) {
            KeySoundStyle.CLICK
        } else {
            keySoundStyle
        },
        // Contacts, contact e-mails and the installed-app list all come from
        // providers that need the user's credential.
        contactSuggestions = false,
        contactEmailSuggestions = false,
        appNameSuggestions = false,
        // Android's shared personal dictionary is credential-encrypted too, and
        // nothing typed on a lock screen should be learned anywhere regardless.
        addWordsToSystemDictionary = false,
        // Clipboard history is not loaded while locked, so the paste chip would
        // only ever offer something copied inside this same locked session; the
        // screenshot watcher reads MediaStore, which is locked with everything
        // else.
        clipboard = clipboard.copy(
            // Nothing copied on a lock screen is recorded: the store has no
            // file to write to, and the images would want one under filesDir.
            history = false,
            suggestRecent = false,
            userScreenshots = false,
        ),
        // Handwriting recognizes through an ML Kit model downloaded into
        // filesDir. Without it the swipe would only ever reach the "download
        // the model" prompt, so the gesture goes back to typing words.
        letterSwipeAction = if (letterSwipeAction == LetterSwipeAction.HANDWRITE) {
            LetterSwipeAction.TYPE_WORDS
        } else {
            letterSwipeAction
        },
        // Offline dictation loads its weights from filesDir; the OS recognizer
        // does not, so dictation started from a key still works.
        whisper = whisper.copy(engine = "system"),
        // Before the first unlock the lock screen still hides notification
        // *content* — a chip printing a code out of one would put on the
        // keyboard exactly what the shade is redacting.
        otp = otp.copy(enabled = false),
        // Every selection macro that hands the text to another app starts an
        // activity, and nothing can be started before the first unlock. The row
        // itself stays: copying and the case ladder only touch the field the
        // user is already typing in, which is exactly what still works here.
        selectionMacros = selectionMacros.copy(
            macros = selectionMacros.macros.filterNotTo(mutableSetOf()) { it.leavesApp },
        ),
    )
}

/**
 * The image-path strip [restrictedToDirectBoot] applies to a custom theme,
 * recursing into the theme's variants — their images live in the same
 * credential-encrypted directory.
 */
private fun ThemeSpec.strippedForDirectBoot(): ThemeSpec = copy(
    backgroundImage = null,
    backgroundImageLandscape = null,
    backgroundPhoto = null,
    backgroundPhotoLandscape = null,
    variants = variants.map { it.strippedForDirectBoot() },
)

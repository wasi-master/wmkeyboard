package com.wasimaster.wmkeyboard.ime

import android.text.SpannableString
import android.text.Spanned
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper

/**
 * Hands the field the word being typed without its composing underline, while
 * [hide] says so (Typing → Capitals, punctuation and spacing → "Hide the
 * underline under the word being typed").
 *
 * The underline is not the keyboard's. `BaseInputConnection.replaceText`
 * gives a plain-String composition the theme's default composing spans
 * (`candidatesTextStyleSpans`, an underline), and skips that for anything
 * already Spannable. The Spanned kind survives the binder trip
 * (`TextUtils.writeToParcel`), so an empty [SpannableString] lands in the
 * app as a SpannableString with nothing on it, and nothing is drawn.
 *
 * Text that already carries spans is passed through untouched: Mozc's segment
 * underline marks what the arrow keys move between and has to stay. Editors
 * that draw their own composition marker (Chrome, WebView, some code editors)
 * keep drawing it; only committing as you type would avoid that.
 */
internal class PlainComposingConnection(
    base: InputConnection,
    private val hide: () -> Boolean,
) : InputConnectionWrapper(base, true) {

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
        super.setComposingText(plain(text), newCursorPosition)

    private fun plain(text: CharSequence?): CharSequence? =
        if (text == null || text.isEmpty() || text is Spanned || !hide()) text else SpannableString(text)
}

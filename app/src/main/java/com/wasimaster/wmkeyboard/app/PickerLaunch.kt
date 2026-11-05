package com.wasimaster.wmkeyboard.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher

/**
 * Opens Android's file picker, or says there is none.
 *
 * Some phones and most TV boxes ship without the Files app (DocumentsUI), or
 * with it disabled, and then `OPEN_DOCUMENT` and `CREATE_DOCUMENT` have no
 * activity to go to: `launch` throws `ActivityNotFoundException` straight out
 * of the click handler and takes the screen down with it.
 */
internal fun <I> ActivityResultLauncher<I>.launchPicker(context: Context, input: I) {
    try {
        launch(input)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.file_picker_missing, Toast.LENGTH_LONG).show()
    }
}

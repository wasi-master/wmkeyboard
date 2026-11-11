package com.wasimaster.wmkeyboard.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.wasimaster.wmkeyboard.ime.paperless.PaperlessHandoff
import com.wasimaster.wmkeyboard.ime.paperless.PaperlessScanCard
import kotlinx.coroutines.launch
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.wasimaster.wmkeyboard.core.docscan.DocScanNative
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.ime.R
import java.io.File

/**
 * Invisible trampoline for the doc-scan tool: IMEs cannot launch
 * activity-result flows, and ML Kit's document scanner is one (a
 * full-screen Google-provided activity with edge detection, crop and
 * filters). This activity starts the scanner, copies the resulting page
 * JPEGs into the app's private storage and parks them in [DocScanPages];
 * the keyboard commits them into the editor when it regains the input
 * connection ([onStartInputView] fires as the target app refocuses).
 */
class DocScanActivity : ComponentActivity() {

    companion object {
        /** Hands over (and clears) whatever the last scan produced, from either scanner. */
        fun consumePendingPages(): List<File> = DocScanPages.consume()
    }

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        val pages = scan?.pages.orEmpty().mapIndexedNotNull { index, page ->
            runCatching {
                val file = File(DocScanPages.dir(this), "SCAN_${System.currentTimeMillis()}_$index.jpg")
                contentResolver.requireInputStream(page.imageUri).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                file
            }.getOrNull()
        }
        if (pages.isEmpty()) {
            finish()
            return@registerForActivityResult
        }
        // The text box, or Paperless as its settings say (#1198).
        lifecycleScope.launch {
            val paperless = PaperlessHandoff.settings(this@DocScanActivity)
            when (PaperlessHandoff.route(paperless)) {
                PaperlessHandoff.Route.FIELD -> {
                    DocScanPages.offer(pages)
                    finish()
                }
                PaperlessHandoff.Route.ASK -> setContent {
                    PaperlessScanCard(pages.size, paperless) { choice, toField ->
                        lifecycleScope.launch {
                            PaperlessHandoff.deliver(this@DocScanActivity, pages, choice, toField)
                            finish()
                        }
                    }
                }
                PaperlessHandoff.Route.AUTO -> {
                    val (choice, toField) = PaperlessHandoff.auto(paperless)
                    PaperlessHandoff.deliver(this@DocScanActivity, pages, choice, toField)
                    finish()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DocScanPages.prune(this)

        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                launcher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener {
                // The scanner module lives in Google Play services and can
                // be missing (or still downloading) on some devices: the
                // built-in scanner takes over, which says so itself if its
                // own library cannot be had either.
                if (DocScanNative.shipped) {
                    startActivity(
                        Intent(this, PageScanActivity::class.java)
                            .putExtras(intent)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } else {
                    Toast.makeText(
                        this,
                        R.string.ime_docscan_unavailable_error,
                        Toast.LENGTH_LONG,
                    ).show()
                }
                finish()
            }
    }
}

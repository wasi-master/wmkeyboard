package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.aicore.GeminiNano
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmCatalog
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmRuntime
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmStore
import com.wasimaster.wmkeyboard.core.localllm.ModelFormat
import com.wasimaster.wmkeyboard.core.modules.GoogleLibraries
import com.wasimaster.wmkeyboard.core.ocr.PaddleOcrModel
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.semantic.SemanticModels
import com.wasimaster.wmkeyboard.core.settings.BackupDestination
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.OcrEngine
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.aiRows
import com.wasimaster.wmkeyboard.core.settings.resolveProviders
import com.wasimaster.wmkeyboard.core.stickers.CutoutModel
import com.wasimaster.wmkeyboard.core.stickers.SubjectCutout
import com.wasimaster.wmkeyboard.core.translate.OfflineTranslateLanguages
import com.wasimaster.wmkeyboard.core.translate.OnDeviceTranslator
import com.wasimaster.wmkeyboard.core.util.PlayServices
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCatalog
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperStore
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Privacy › Google libraries: the two levels of [GoogleLibraries], on every
 * build that carries Google's libraries. F-Droid's carries none, so the group
 * is not there.
 *
 * Either change restarts the app, because the level is read once per process.
 * Switching the libraries off first lists what the user has set up that then
 * stops working or changes ([googleLibraryUses]), so a downloaded LiteRT model
 * or a Drive backup does not go quiet without a word.
 */
@Composable
internal fun GoogleLibrariesGroup(repository: SettingsRepository) {
    if (!GoogleLibraries.inBuild) return
    val running by GoogleLibraries.level.collectAsStateWithLifecycle()
    var asking by remember { mutableStateOf<GoogleLibraries.Level?>(null) }
    SettingsGroup(stringResource(R.string.privacy_google_group_title)) {
        item {
            ChoiceSetting(
                R.string.privacy_google_libraries_title,
                subtitle = stringResource(
                    if (running == GoogleLibraries.Level.NO_LIBRARIES) {
                        R.string.privacy_google_libraries_off_subtitle
                    } else {
                        R.string.privacy_google_libraries_no_analytics_subtitle
                    },
                ),
                info = stringResource(R.string.privacy_google_libraries_info),
                options = listOf(
                    GoogleLibraries.Level.NO_ANALYTICS to stringResource(R.string.privacy_google_libraries_no_analytics),
                    GoogleLibraries.Level.NO_LIBRARIES to stringResource(R.string.privacy_google_libraries_off),
                ),
                selected = running,
                default = GoogleLibraries.Level.NO_ANALYTICS,
            ) { picked -> if (picked != running) asking = picked }
        }
    }
    asking?.let { level -> GoogleLibrariesConfirmDialog(repository, level, onDismiss = { asking = null }) }
}

/**
 * Asks before the restart. Going to [GoogleLibraries.Level.NO_LIBRARIES], it
 * first looks for what the user has set up on Google's libraries and lists
 * it; the Restart button waits for that look, so nobody switches past a list
 * they never saw.
 */
@Composable
private fun GoogleLibrariesConfirmDialog(
    repository: SettingsRepository,
    level: GoogleLibraries.Level,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val switchingOff = level == GoogleLibraries.Level.NO_LIBRARIES
    var uses by remember(level) { mutableStateOf(if (switchingOff) null else emptyList<String>()) }
    if (switchingOff) {
        LaunchedEffect(level) {
            uses = runCatching { googleLibraryUses(context, repository.settings.first()) }.getOrDefault(emptyList())
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (switchingOff) {
                        R.string.privacy_google_libraries_off_confirm_title
                    } else {
                        R.string.privacy_google_libraries_on_confirm_title
                    },
                ),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(
                        if (switchingOff) {
                            R.string.privacy_google_libraries_off_confirm_body
                        } else {
                            R.string.privacy_google_libraries_on_confirm_body
                        },
                    ),
                )
                if (switchingOff) {
                    Spacer(Modifier.height(12.dp))
                    val found = uses
                    when {
                        found == null -> Text(stringResource(R.string.privacy_google_libraries_checking))
                        found.isEmpty() -> Text(stringResource(R.string.privacy_google_libraries_off_confirm_nothing))
                        else -> {
                            Text(
                                stringResource(R.string.privacy_google_libraries_off_confirm_uses),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            for (use in found) {
                                Text("• $use", modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = uses != null,
                onClick = {
                    if (GoogleLibraries.store(context, level)) {
                        restartIntoPrivacy(context)
                    } else {
                        Toast.makeText(context, R.string.privacy_google_libraries_save_failed, Toast.LENGTH_LONG).show()
                        onDismiss()
                    }
                },
            ) { Text(stringResource(R.string.privacy_google_libraries_restart)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * What [settings] and the files on the phone have set up on Google's
 * libraries, one line each, for the dialog that switches them off. Read at
 * the first level, while those libraries can still answer (the translator's
 * list of models comes from ML Kit itself).
 */
internal suspend fun googleLibraryUses(context: Context, settings: KeyboardSettings): List<String> =
    withContext(Dispatchers.IO) {
        val filesDir = context.filesDir
        buildList {
            // On-device AI on LiteRT-LM: the catalog's downloads and imported files alike.
            val liteRtModels = LocalLlmCatalog.models
                .filter { it.runtime == LocalLlmRuntime.LITERT_LM && LocalLlmStore.isDownloaded(filesDir, it) }
                .map { it.displayName } +
                LocalLlmStore.customModels(filesDir)
                    .filter { file ->
                        ModelFormat.entries.firstOrNull { it.extension.equals(file.extension, ignoreCase = true) }
                            ?.runtime == LocalLlmRuntime.LITERT_LM
                    }
                    .map { it.nameWithoutExtension }
            if (liteRtModels.isNotEmpty()) {
                add(context.getString(R.string.privacy_google_libraries_use_llm, liteRtModels.joinToString()))
            }
            val nanoPicked = settings.ai.localModelId == GeminiNano.MODEL_ID ||
                runCatching { settings.aiRows().any { it.model == GeminiNano.MODEL_ID } }.getOrDefault(false)
            if (nanoPicked) add(context.getString(R.string.privacy_google_libraries_use_gemini_nano))
            // Offline Whisper on LiteRT; whisper.cpp's models (ggml) stay.
            val whisper = WhisperCatalog.models
                .filter { !it.ggml && WhisperStore.isDownloaded(filesDir, it) }
                .map { it.displayName }
            if (whisper.isNotEmpty()) {
                add(context.getString(R.string.privacy_google_libraries_use_whisper, whisper.joinToString()))
            }
            if (settings.semanticSearch.enabled ||
                SemanticModels.isDownloaded(context.noBackupFilesDir, SemanticModels.Pack.TEXT)
            ) {
                add(context.getString(R.string.privacy_google_libraries_use_semantic))
            }
            // English is the translator's pivot and always "downloaded".
            val translations = runCatching { OnDeviceTranslator.refresh(context) }.getOrDefault(emptySet()) -
                OfflineTranslateLanguages.PIVOT
            if (translations.isNotEmpty()) {
                val names = translations.sorted().joinToString { Locale.forLanguageTag(it).displayName }
                add(context.getString(R.string.privacy_google_libraries_use_translate, names))
            }
            // ML Kit's handwriting models: Mobile Data Download keeps them here.
            if (File(filesDir, INK_MODELS_DIR).walkTopDown().any { it.isFile }) {
                add(context.getString(R.string.privacy_google_libraries_use_handwriting))
            }
            val cutoutReady = CutoutModel.file(filesDir).isFile ||
                (PlayServices.isInstalled(context) && runCatching { SubjectCutout.modelReady(context) }.getOrDefault(false))
            if (SubjectCutout.supported && cutoutReady) add(context.getString(R.string.privacy_google_libraries_use_cutout))
            if (settings.scanner.ocrEngine == OcrEngine.ML_KIT) {
                add(context.getString(R.string.privacy_google_libraries_use_ocr_engine))
            }
            val paddleOnLiteRt = PaddleOcrModel.parts(PaddleOcrModel.Runtime.LITERT)
                .all { PaddleOcrModel.file(filesDir, it).isFile }
            if (paddleOnLiteRt) {
                val bytes = PaddleOcrModel.parts(PaddleOcrModel.Runtime.MNN).sumOf { it.bytes }
                add(context.getString(R.string.privacy_google_libraries_use_paddle, Formatter.formatShortFileSize(context, bytes)))
            }
            if (settings.autoBackup.enabled && settings.autoBackup.destination == BackupDestination.DRIVE) {
                add(context.getString(R.string.privacy_google_libraries_use_drive))
            }
            val googleHome = runCatching {
                settings.resolveProviders(ProviderTool.SMART_HOME, all = true)
                    .any { it.typeId == ProviderTypes.GOOGLE_HOME.id }
            }.getOrDefault(false)
            if (googleHome) add(context.getString(R.string.privacy_google_libraries_use_google_home))
            if (settings.send.pairDrop) add(context.getString(R.string.privacy_google_libraries_use_pairdrop))
            val googleFonts = (listOf(settings.keyFontId) + settings.scriptFontIds.values).any { it.startsWith(GOOGLE_FONT) }
            if (googleFonts) add(context.getString(R.string.privacy_google_libraries_use_fonts))
        }
    }

/** Where ML Kit's Mobile Data Download keeps the handwriting models, under filesDir. */
private const val INK_MODELS_DIR = "mlkit_digital_ink_recognition"

/** The font id prefix of a Google Fonts family, fetched through Play services. */
private const val GOOGLE_FONT = "google:"

/**
 * Ends this process and opens the Privacy screen in a new one, which reads
 * the level just stored. The keyboard's service shares the process, and
 * Android binds it again the next time a text field wants it, so the
 * keyboard comes back on its own with the new level too.
 */
private fun restartIntoPrivacy(context: Context) {
    val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    val privacy = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("${SettingsDeepLink.SCHEME}://${SettingsDeepLink.SCREEN_HOST}/privacy"),
    ).setPackage(context.packageName).addFlags(flags)
    val intent = privacy.takeIf { it.resolveActivity(context.packageManager) != null }
        ?: context.packageManager.getLaunchIntentForPackage(context.packageName)?.addFlags(flags)
    intent?.let { runCatching { context.startActivity(it) } }
    Runtime.getRuntime().exit(0)
}

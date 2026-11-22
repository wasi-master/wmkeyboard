package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.plugins.PluginActionSpec
import com.wasimaster.wmkeyboard.core.plugins.PluginManifest
import com.wasimaster.wmkeyboard.core.plugins.PluginManifestCodec
import com.wasimaster.wmkeyboard.core.plugins.PluginPermission
import com.wasimaster.wmkeyboard.core.plugins.PluginProviderSpec
import com.wasimaster.wmkeyboard.core.plugins.PluginSettingSpec
import com.wasimaster.wmkeyboard.core.plugins.PyApiEntry
import com.wasimaster.wmkeyboard.core.plugins.PyFix
import com.wasimaster.wmkeyboard.core.plugins.PyHostShape
import com.wasimaster.wmkeyboard.core.plugins.PyHover
import com.wasimaster.wmkeyboard.core.plugins.PySignature
import com.wasimaster.wmkeyboard.core.plugins.PythonTooling

/**
 * What the strip under the code explains: the call being typed, the name at
 * the caret, or the API entry around it, in that order of interest.
 */
internal sealed interface IdeHelp {
    data class Call(val signature: PySignature) : IdeHelp

    data class Name(val hover: PyHover) : IdeHelp

    data class Api(val entry: PyApiEntry) : IdeHelp
}

/** The help for [caret] in [text]. Off the main thread: it parses. */
internal fun ideHelpAt(text: String, caret: Int, host: PyHostShape?): IdeHelp? {
    PythonTooling.signature(text, caret, host)?.let { return IdeHelp.Call(it) }
    // The name the caret is in or just after.
    val hover = PythonTooling.hover(text, caret, host) ?: if (caret > 0) PythonTooling.hover(text, caret - 1, host) else null
    hover?.let { return IdeHelp.Name(it) }
    return PythonTooling.apiAt(text, caret)?.let { IdeHelp.Api(it) }
}

/** The strip under the code for [help]. */
@Composable
internal fun IdeHelpStrip(help: IdeHelp, colors: CodeColors) {
    when (help) {
        is IdeHelp.Call -> SignatureStrip(help.signature, colors)
        is IdeHelp.Name -> CodeDocStrip(help.hover.title, help.hover.title, help.hover.doc, colors)
        is IdeHelp.Api -> ApiDocStrip(help.entry, colors)
    }
}

/**
 * The call being typed, with the argument the caret is in lit, what that
 * argument takes, and the function's doc under it. A tap opens the doc whole.
 */
@Composable
internal fun SignatureStrip(signature: PySignature, colors: CodeColors) {
    var open by rememberSaveable(signature.label) { mutableStateOf(false) }
    val label = buildAnnotatedString {
        append(signature.label)
        signature.params.getOrNull(signature.active)?.let { span ->
            val start = span.start.coerceIn(0, signature.label.length)
            val end = span.end.coerceIn(start, signature.label.length)
            addStyle(SpanStyle(color = colors.keyword, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline), start, end)
        }
    }
    val toggleLabel = stringResource(if (open) R.string.plugin_ide_doc_less_action else R.string.plugin_ide_doc_more_action)
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.gutter)
            .clickable(onClickLabel = toggleLabel) { open = !open }
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(label, fontFamily = CodeFontFamily, fontSize = 12.sp, color = colors.function, maxLines = 2, overflow = TextOverflow.Ellipsis)
        signature.paramDoc?.let { hint ->
            val name = signature.names.getOrNull(signature.active)
            Text(
                buildAnnotatedString {
                    if (name != null) {
                        withStyle(SpanStyle(fontFamily = CodeFontFamily, color = colors.keyword)) { append(name) }
                        append("  ")
                    }
                    append(hint)
                },
                fontSize = 12.sp,
                color = colors.text,
                maxLines = if (open) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (signature.doc.isNotBlank()) {
            Text(
                signature.doc,
                fontSize = 12.sp,
                color = colors.gutterText,
                maxLines = if (open) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The problem at the caret, in words, with the button that settles it. */
@Composable
internal fun FixStrip(diagnostic: CodeDiagnostic, colors: CodeColors, onFix: () -> Unit) {
    val context = LocalContext.current
    val fix = diagnostic.fix ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.gutter)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            diagnostic.message(context),
            fontSize = 12.sp,
            color = when (diagnostic.severity) {
                CodeSeverity.ERROR -> colors.problem
                CodeSeverity.WARNING -> colors.warning
                CodeSeverity.INFO -> colors.gutterText
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onFix) { Text(fixLabel(context, fix), maxLines = 1) }
    }
}

/** What pressing Fix does, as the button says it. */
internal fun fixLabel(context: Context, fix: PyFix): String = when (fix) {
    is PyFix.Replace -> context.getString(R.string.plugin_ide_fix_replace, fix.text.trim('"', '\''))
    is PyFix.Insert -> context.getString(
        if (fix.text.trimStart().startsWith("@")) R.string.plugin_ide_fix_stub else R.string.plugin_ide_fix_import,
    )
    is PyFix.Permission -> context.getString(R.string.plugin_ide_fix_permission, fix.wire)
    is PyFix.Host -> context.getString(R.string.plugin_ide_fix_host, fix.host)
    is PyFix.Provider -> context.getString(R.string.plugin_ide_fix_provider)
    is PyFix.Action -> context.getString(R.string.plugin_ide_fix_action)
    is PyFix.Keyword -> context.getString(R.string.plugin_ide_fix_keyword, fix.word)
    is PyFix.Setting -> context.getString(R.string.plugin_ide_fix_setting, fix.key)
}

/**
 * The change [fix] makes to the script, for a diagnostic over [range], with
 * the caret at [caret] before it. Null for a fix that changes plugin.json.
 */
internal fun fixEdit(fix: PyFix, range: TextRange, caret: Int): CodeTextEdit? = when (fix) {
    is PyFix.Replace -> CodeTextEdit(range, fix.text, TextRange(range.min + fix.text.length))
    is PyFix.Insert -> {
        // A stub is shown where it lands; an import keeps the caret where it was.
        val stub = fix.text.trimStart().startsWith("@")
        val after = when {
            stub -> fix.at + fix.text.length
            caret >= fix.at -> caret + fix.text.length
            else -> caret
        }
        CodeTextEdit(TextRange(fix.at), fix.text, TextRange(after))
    }
    else -> null
}

/** [manifest] with [fix] made, or null for a fix that changes the script. */
internal fun manifestWith(manifest: PluginManifest, fix: PyFix): PluginManifest? = when (fix) {
    is PyFix.Permission -> {
        val wanted = (manifest.permissions + fix.wire).distinct()
        // Every API 5 permission needs the API 5 manifest.
        val api5 = PluginPermission.parse(fix.wire)?.let { it.sinceApi >= PluginManifestCodec.PACKAGE_API } == true
        manifest.copy(
            permissions = PluginPermission.all.map { it.wire }.filter { it in wanted } + wanted.filter { w -> PluginPermission.all.none { it.wire == w } },
            apiVersion = if (api5) maxOf(manifest.apiVersion, PluginManifestCodec.PACKAGE_API) else manifest.apiVersion,
        )
    }
    is PyFix.Host -> manifest.copy(hosts = (manifest.hosts + fix.host).distinct())
    is PyFix.Provider -> manifest.copy(
        providers = manifest.providers + PluginProviderSpec(tool = fix.tool, id = if (fix.id == fix.tool) "" else fix.id),
    )
    is PyFix.Action -> manifest.copy(
        actions = manifest.actions + PluginActionSpec(id = fix.id, name = fix.id.replace('_', ' ').replaceFirstChar { it.uppercase() }),
    )
    is PyFix.Keyword -> manifest.copy(tool = manifest.tool.copy(keyword = fix.word, chip = true))
    is PyFix.Setting -> manifest.copy(
        settings = manifest.settings + PluginSettingSpec(key = fix.key, type = PluginSettingSpec.TYPE_TEXT, label = fix.key.replace('_', ' ').replaceFirstChar { it.uppercase() }),
    )
    is PyFix.Replace, is PyFix.Insert -> null
}

/** The first diagnostic with a fix that covers [caret], errors first. */
internal fun fixableAt(diagnostics: List<CodeDiagnostic>, caret: Int): CodeDiagnostic? =
    diagnostics
        .filter { it.fix != null && caret >= it.range.min && caret <= it.range.max }
        .minByOrNull { it.severity }

package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.app.share.AppShareSheet
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.voice.OpusEncoder
import com.wasimaster.wmkeyboard.core.voice.VoiceDenoise
import com.wasimaster.wmkeyboard.core.voice.executorch.EtNative
import com.wasimaster.wmkeyboard.core.input.mozc.MozcRuntime
import com.wasimaster.wmkeyboard.core.input.rime.RimeRuntime
import com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCatalog
import com.wasimaster.wmkeyboard.app.updates.InstalledReleaseNotesDialog
import com.wasimaster.wmkeyboard.app.updates.AllLanguagesRow
import com.wasimaster.wmkeyboard.app.updates.KeepEnglishOnlyRow
import com.wasimaster.wmkeyboard.app.updates.UpdateSettings
import com.wasimaster.wmkeyboard.app.updates.languageSwitchCanBeDropped
import com.wasimaster.wmkeyboard.core.settings.OnboardingSettings
import com.wasimaster.wmkeyboard.core.settings.PersonaDepth
import com.wasimaster.wmkeyboard.core.settings.PersonaLanguages
import com.wasimaster.wmkeyboard.core.settings.SettingLinkMode
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.isTelevision
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.support.Support
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Attribution and licence notices, in three sections.
 *
 * Every third-party component that ships inside the APK is listed here with
 * its licence, and the full licence texts (or, for aggregated sources, a
 * notice naming each one) are bundled as assets under `assets/licenses/` —
 * Apache-2.0 §4(a) and the MIT/BSD/OFL/Unicode notices all require the text
 * to travel with the binary, not merely a link to it. Data packs the app
 * downloads on demand are listed the same way: their licences (CC BY,
 * CC BY-SA, BSD, …) attach to the data wherever it ends up, bundled or not.
 * Online services the tools call are listed last: no code or data of theirs
 * is distributed, but their terms still ask to be credited.
 */

internal const val SOURCE_URL = Support.SOURCE_URL
internal const val DOCS_URL = "https://wmkeyboard.pages.dev"
/**
 * The privacy policy for *this* build, not for the project in general.
 *
 * F-Droid's build is a different app underneath — no ML Kit, no Play
 * libraries and no Google sign-in compiled into it — so it carries a policy of
 * its own, and this row has to open the one that is true of the APK the reader
 * is holding. Opening the wrong one would disclose collection that does not
 * happen, or worse, fail to disclose collection that does.
 *
 * Keyed on what was compiled in (`GOOGLE_SDKS`, false only when the build was
 * made with wmkb.enableFdroid) rather than on where the APK came from: the
 * page is about what the binary can do.
 */
private val PRIVACY_POLICY_URL = if (!com.wasimaster.wmkeyboard.config.BuildConfig.GOOGLE_SDKS) {
    "$DOCS_URL/privacy/policy-fdroid/"
} else {
    "$DOCS_URL/privacy/policy/"
}
private const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}"
private const val FDROID_URL = "https://f-droid.org/packages/${BuildConfig.APPLICATION_ID}/"

/**
 * Opens the system share sheet with the share blurb and [url].
 *
 * The blurb and the link go out on one line. A newline between them reads
 * better, but a receiving app is free to take the two lines as two parts of a
 * structured share: Messenger sends them on tagged with its own markers, and
 * the friend reads `mp:Try WM Keyboard…ms:https://…`. One line has no seam to
 * split on.
 */
private fun shareLink(context: android.content.Context, url: String) {
    val blurb = context.getString(R.string.about_share_blurb)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "$blurb $url")
    }
    AppShareSheet.start(context, intent, context.getString(R.string.about_share_chooser_title))
}

/**
 * One row in the licences list. [licenseAsset] is a file in `assets/licenses/`.
 *
 * Only [usedRes] is translated. [name], [copyright] and [license] are the legal
 * identity of the component: a product name, a copyright notice and a licence
 * identifier all have to travel verbatim, so they stay in English.
 */
private data class Attribution(
    val name: String,
    @StringRes val usedRes: Int,
    val copyright: String,
    val license: String,
    val licenseAsset: String?,
    val url: String,
)

/**
 * Components whose code or data is bundled into the APK. Gated entries are
 * absent from F-Droid's build, where their libraries are not linked.
 */
private val bundledAttributions: List<Attribution> = buildList {
    add(
        Attribution(
            "AndroidX & Jetpack Compose",
            R.string.about_bundled_androidx_used,
            "Copyright The Android Open Source Project",
            "Apache-2.0", "apache-2.0.txt",
            "https://developer.android.com/jetpack/androidx",
        ),
    )
    add(
        Attribution(
            "Kotlin, coroutines & serialization",
            R.string.about_bundled_kotlin_used,
            "Copyright JetBrains s.r.o. and Kotlin contributors",
            "Apache-2.0", "apache-2.0.txt",
            "https://github.com/JetBrains/kotlin",
        ),
    )
    add(
        Attribution(
            "Coil",
            R.string.about_bundled_coil_used,
            "Copyright Coil Contributors",
            "Apache-2.0", "apache-2.0.txt",
            "https://github.com/coil-kt/coil",
        ),
    )
    add(
        Attribution(
            "OkHttp & Okio",
            R.string.about_bundled_okhttp_used,
            "Copyright Square, Inc.",
            "Apache-2.0", "apache-2.0.txt",
            "https://github.com/square/okhttp",
        ),
    )
    add(
        Attribution(
            "JSch",
            R.string.about_bundled_jsch_used,
            "Copyright (c) 2002-2015 Atsuhiko Yamanaka, JCraft, Inc.; jBCrypt copyright (c) 2006 Damien Miller",
            "BSD-3-Clause", "bsd-jsch.txt",
            "https://github.com/mwiede/jsch",
        ),
    )
    add(
        Attribution(
            "Bouncy Castle",
            R.string.about_bundled_bouncycastle_used,
            "Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc.",
            "MIT", "mit-bouncycastle.txt",
            "https://www.bouncycastle.org",
        ),
    )
    add(
        Attribution(
            "APNG4Android",
            R.string.about_bundled_apng_used,
            "Copyright Pengfei Zhou",
            "Apache-2.0", "apache-2.0.txt",
            "https://github.com/penfeizhou/APNG4Android",
        ),
    )
    add(
        Attribution(
            "Lottie for Android",
            R.string.about_bundled_lottie_used,
            "Copyright Airbnb, Inc.",
            "Apache-2.0", "apache-2.0.txt",
            "https://github.com/airbnb/lottie-android",
        ),
    )
    add(
        Attribution(
            "ZXing",
            R.string.about_bundled_zxing_used,
            "Copyright ZXing authors",
            "Apache-2.0", "apache-2.0.txt",
            "https://github.com/zxing/zxing",
        ),
    )
    add(
        Attribution(
            "LuaJ",
            R.string.about_bundled_luaj_used,
            "Copyright (c) 2007 LuaJ. All rights reserved.",
            "MIT", "mit-luaj.txt",
            "https://github.com/luaj/luaj",
        ),
    )
    // Spell checking against an imported Hunspell dictionary, in every build:
    // packaged, pinned for download, or a Play split. See native/hunspell-jni.
    add(
        Attribution(
            "Hunspell",
            R.string.about_bundled_hunspell_used,
            "Copyright (C) 2002-2022 Németh László and Hunspell contributors",
            "MPL-1.1", "mpl-1.1.txt",
            "https://github.com/hunspell/hunspell",
        ),
    )
    // Chinese and Japanese handwriting without ML Kit, in every build:
    // packaged, pinned for download, or a Play split. See native/zinnia-jni.
    add(
        Attribution(
            "zinnia",
            R.string.about_bundled_zinnia_used,
            "Copyright (c) 2005-2007, Taku Kudo",
            "BSD-3-Clause", "bsd-zinnia.txt",
            "https://github.com/taku910/zinnia",
        ),
    )
    // GiellaLT spellers, in the same library module as Hunspell. See
    // native/divvunspell-jni; the spellers are fetched, never shipped.
    add(
        Attribution(
            "divvunspell",
            R.string.about_bundled_divvunspell_used,
            "Copyright UiT The Arctic University of Norway and divvunspell contributors",
            "MIT OR Apache-2.0", "divvunspell-third-party.txt",
            "https://github.com/divvun/divvunspell",
        ),
    )
    // Finnish spell checking, beside Hunspell in the same library module but
    // never in an APK: downloaded or Play's split. See native/voikko-jni; the
    // dictionary (voikko-fi, GPL 2+) is fetched, never shipped.
    add(
        Attribution(
            "Voikko",
            R.string.about_bundled_voikko_used,
            "Copyright Harri Pitkänen, Hannu Väisänen, Teemu Likonen and Voikko contributors",
            "MPL-1.1 OR GPL-2.0-or-later OR LGPL-2.1-or-later", "voikko.txt",
            "https://github.com/voikko/corevoikko",
        ),
    )
    if (BuildConfig.ENABLE_GRAMMAR) {
        add(
            Attribution(
                "Harper",
                R.string.about_bundled_harper_used,
                "Copyright Automattic, Inc. and Harper contributors",
                "Apache-2.0", "apache-2.0.txt",
                "https://github.com/automattic/harper",
            ),
        )
        add(
            Attribution(
                "Rust crates used by Harper",
                R.string.about_bundled_rust_crates_used,
                "Copyright the respective crate authors",
                "MIT / Apache-2.0 / others", "harper-third-party.txt",
                "https://crates.io",
            ),
        )
    }
    // GGUF models for on-device AI, in every build that has the On-device
    // provider: packaged, pinned for download, or a Play split. See
    // native/llamacpp-jni.
    if (BuildConfig.ENABLE_LOCAL_LLM) {
        add(
            Attribution(
                "llama.cpp and ggml",
                R.string.about_bundled_llama_cpp_used,
                "Copyright (c) 2023-2026 The ggml authors; nlohmann/json Copyright (c) 2013-2025 Niels Lohmann",
                "MIT", "mit-llama-cpp.txt",
                "https://github.com/ggml-org/llama.cpp",
            ),
        )
    }
    // The sticker generator's runtime, in every build: packaged, pinned for
    // download, or a Play split. See native/imagegen-jni.
    add(
        Attribution(
            "MNN",
            R.string.about_bundled_mnn_used,
            "Copyright (C) 2018 Alibaba Group Holding Limited",
            "Apache-2.0", "apache-2.0.txt",
            "https://github.com/alibaba/MNN",
        ),
    )
    // On-device translation without Google, in every build: packaged, pinned
    // for download, or a Play split. See native/bergamot-jni.
    add(
        Attribution(
            "Bergamot translator",
            R.string.about_bundled_bergamot_used,
            "Copyright Mozilla Foundation, the University of Edinburgh and the Bergamot contributors",
            "MPL-2.0", "mpl-2.0.txt",
            "https://github.com/mozilla/translations",
        ),
    )
    add(
        Attribution(
            "Marian NMT, ruy, SentencePiece, PCRE2, Zstandard and others",
            R.string.about_bundled_bergamot_libs_used,
            "Copyright the respective authors",
            "MIT / Apache-2.0 / BSD / zlib", "bergamot-third-party.txt",
            "https://github.com/mozilla/translations/tree/main/inference",
        ),
    )
    // The NLLB-200 engine's runtime, in every build: packaged, pinned for
    // download, or a Play split. See native/ctranslate2-jni.
    add(
        Attribution(
            "CTranslate2",
            R.string.about_bundled_ct2_used,
            "Copyright (c) 2018- SYSTRAN; Copyright (c) 2019- The OpenNMT Authors",
            "MIT", "ct2-third-party.txt",
            "https://github.com/OpenNMT/CTranslate2",
        ),
    )
    add(
        Attribution(
            "SentencePiece, ruy, Abseil, spdlog and others",
            R.string.about_bundled_ct2_libs_used,
            "Copyright the respective authors",
            "Apache-2.0 / MIT / BSD / zlib", "ct2-third-party.txt",
            "https://github.com/google/sentencepiece",
        ),
    )
    // The built-in document scanner, in every build: packaged, pinned for
    // download, or a Play split. Only OpenCV's core and imgproc modules, with
    // Android's cpufeatures; see native/docscan-jni.
    add(
        Attribution(
            "OpenCV",
            R.string.about_bundled_opencv_used,
            "Copyright (C) 2000-2022 Intel Corporation, 2009-2011 Willow Garage Inc., 2015-2023 OpenCV Foundation and others",
            "Apache-2.0", "apache-2.0.txt",
            "https://github.com/opencv/opencv",
        ),
    )
    // The QR scanner's reader without ML Kit, in every build: packaged,
    // pinned for download, or a Play split. Readers only, with the libzueci
    // copy upstream carries; see native/zxingcpp-jni.
    add(
        Attribution(
            "zxing-cpp",
            R.string.about_bundled_zxingcpp_used,
            "Copyright 2016 ZXing authors, Nu-book Inc., Axel Waggershauser and zxing-cpp contributors",
            "Apache-2.0", "apache-2.0.txt",
            "https://github.com/zxing-cpp/zxing-cpp",
        ),
    )
    add(
        Attribution(
            "libzueci",
            R.string.about_bundled_zueci_used,
            "Copyright (C) 2022 gitlost; Copyright (c) 2008-2009 Bjoern Hoehrmann",
            "BSD-3-Clause / MIT", "bsd-3-clause-zueci.txt",
            "https://sourceforge.net/projects/libzueci/",
        ),
    )
    if (BuildConfig.ENABLE_ML_KIT_SCANNERS) {
        // Compiled into one native library for the text scanner; see
        // native/tesseract-jni.
        add(
            Attribution(
                "Tesseract",
                R.string.about_bundled_tesseract_used,
                "Copyright Hewlett-Packard, Google and Tesseract contributors",
                "Apache-2.0", "apache-2.0.txt",
                "https://github.com/tesseract-ocr/tesseract",
            ),
        )
        add(
            Attribution(
                "Leptonica",
                R.string.about_bundled_leptonica_used,
                "Copyright (C) 2001-2020 Leptonica",
                "BSD-2-Clause", "bsd-2-clause-leptonica.txt",
                "http://www.leptonica.org",
            ),
        )
    }
    if (BuildConfig.ENABLE_ML_KIT_HANDWRITING || BuildConfig.ENABLE_ML_KIT_SCANNERS) {
        add(
            Attribution(
                "Google ML Kit",
                R.string.about_bundled_mlkit_used,
                "Copyright Google LLC",
                "Google APIs Terms of Service", null,
                "https://developers.google.com/ml-kit/terms",
            ),
        )
    }
    if (BuildConfig.ENABLE_PLAY_STORE) {
        // Linked only into the Play build, which is the only one where in-app
        // updates can work. Its licence is Google's own SDK terms rather than
        // an open-source licence, so the row opens those terms instead of a
        // bundled text file.
        add(
            Attribution(
                "Play In-App Updates",
                R.string.about_bundled_play_update_used,
                "Copyright Google LLC",
                "Play Core Software Development Kit Terms of Service", null,
                "https://developer.android.com/guide/playcore/license",
            ),
        )
    }
    if (BuildConfig.ENABLE_LITERT_LM) {
        add(
            Attribution(
                "LiteRT-LM",
                R.string.about_bundled_litert_lm_used,
                "Copyright Google LLC",
                "Apache-2.0", "apache-2.0.txt",
                "https://github.com/google-ai-edge/LiteRT-LM",
            ),
        )
    }
    if (BuildConfig.ENABLE_WHISPER) {
        add(
            Attribution(
                "LiteRT",
                R.string.about_bundled_litert_used,
                "Copyright Google LLC",
                "Apache-2.0", "apache-2.0.txt",
                "https://github.com/google-ai-edge/LiteRT",
            ),
        )
        add(
            Attribution(
                "whisper_android",
                R.string.about_bundled_whisper_android_used,
                "Copyright (c) 2023 Vilas Ninawe",
                "MIT", "mit-whisper-android.txt",
                "https://github.com/vilassn/whisper_android",
            ),
        )
        // The other offline engine (#548), in the same builds.
        add(
            Attribution(
                "Cactus Needle and Whistle",
                R.string.about_bundled_whistle_used,
                "Copyright Cactus Compute, Inc.",
                "Apache-2.0", "apache-2.0.txt",
                "https://huggingface.co/Cactus-Compute/whistle",
            ),
        )
    }
    // OpenAI's weights reach both runtimes: LiteRT's graphs in every build but
    // F-Droid's, whisper.cpp's models in every build that has its library (#440).
    if (BuildConfig.ENABLE_WHISPER || WhisperCatalog.cppAvailable) {
        add(
            Attribution(
                "OpenAI Whisper",
                R.string.about_bundled_whisper_used,
                "Copyright (c) 2022 OpenAI",
                "MIT", "mit-whisper.txt",
                "https://github.com/openai/whisper",
            ),
        )
    }
    if (WhisperCatalog.cppAvailable) {
        // The runtime of Offline Whisper's ggml models (#440), and the
        // quantized models it downloads, wherever its library is.
        add(
            Attribution(
                "whisper.cpp and ggml",
                R.string.about_bundled_whisper_cpp_used,
                "Copyright (c) 2023-2026 The ggml authors",
                "MIT", "mit-whisper-cpp.txt",
                "https://github.com/ggml-org/whisper.cpp",
            ),
        )
    }
    if (RimeRuntime.libraryShipped) {
        // The optional RIME engine for Chinese: librime and the libraries it
        // is built with, in one library of its own. The schema packs it
        // downloads carry their own licences inside each pack.
        add(
            Attribution(
                "librime",
                R.string.about_bundled_rime_used,
                "Copyright RIME Developers",
                "BSD-3-Clause", "rime-third-party.txt",
                "https://github.com/rime/librime",
            ),
        )
    }
    if (MozcRuntime.libraryShipped) {
        // The optional Mozc engine for Japanese: Mozc with Abseil and
        // Protocol Buffers in one library, and the notices of the dictionary
        // it downloads, which come from Mozc's sources at the same release.
        add(
            Attribution(
                "Mozc",
                R.string.about_bundled_mozc_used,
                "Copyright 2010-2026 Google Inc.",
                "BSD-3-Clause", "mozc-third-party.txt",
                "https://github.com/google/mozc",
            ),
        )
    }
    if (EtNative.shipped) {
        // Offline Asr4All (#289): the runtime built into the app, and the
        // models it downloads, which carry their own restricted-use licence.
        add(
            Attribution(
                "ExecuTorch",
                R.string.about_bundled_executorch_used,
                "Copyright (c) Meta Platforms, Inc. and affiliates",
                "BSD-3-Clause", "bsd-executorch.txt",
                "https://github.com/pytorch/executorch",
            ),
        )
        // The glide engine's models, on the same runtime. The weights licence
        // asks for this notice wherever the product says what it is built on.
        add(
            Attribution(
                "FUTO Swipe",
                R.string.about_bundled_futo_swipe_used,
                "FUTO Swipe Model weights, FUTO Holdings, Inc.",
                "FUTO Model Weights License 1.0", "futo-swipe-weights.txt",
                "https://huggingface.co/futo-org/futo-swipe",
            ),
        )
        add(
            Attribution(
                "Asr4All Model",
                R.string.about_bundled_asr4all_used,
                "Asr4All Model weights",
                "Model Weights License (Restricted Use) 1.0", "asr4all-weights.txt",
                "https://huggingface.co/futo-org/asr4all-s",
            ),
        )
        // Offline Parakeet and Offline Moonshine, on the same runtime; their
        // weights download on request.
        add(
            Attribution(
                "NVIDIA Parakeet TDT 0.6B v3",
                R.string.about_bundled_parakeet_used,
                "Copyright NVIDIA Corporation",
                "CC-BY-4.0", "cc-by-4.0-parakeet.txt",
                "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3",
            ),
        )
        add(
            Attribution(
                "Moonshine",
                R.string.about_bundled_moonshine_used,
                "Copyright (c) 2024 Useful Sensors",
                "MIT", "mit-moonshine.txt",
                "https://github.com/moonshine-ai/moonshine",
            ),
        )
    }
    if (VoiceDenoise.shipped) {
        add(
            Attribution(
                "RNNoise",
                R.string.about_bundled_rnnoise_used,
                "Copyright (c) 2017 Mozilla, (c) 2007-2017 Jean-Marc Valin, (c) 2005-2017 Xiph.Org Foundation",
                "BSD-3-Clause", "bsd-rnnoise.txt",
                "https://github.com/xiph/rnnoise",
            ),
        )
    }
    if (OpusEncoder.shipped) {
        add(
            Attribution(
                "Opus",
                R.string.about_bundled_opus_used,
                "Copyright (c) 2001-2023 Xiph.Org, Skype Limited, Octasic, Jean-Marc Valin, Timothy B. Terriberry, " +
                    "CSIRO, Gregory Maxwell, Mark Borgerding, Erik de Castro Lopo, Mozilla, Amazon",
                "BSD-3-Clause", "bsd-opus.txt",
                "https://opus-codec.org",
            ),
        )
    }
    if (SherpaNative.shipped) {
        // Offline Sherpa: Microsoft's runtime, fetched from Maven Central,
        // k2-fsa's toolkit built into the bridge, and the weights it
        // downloads, each under its own licence.
        add(
            Attribution(
                "ONNX Runtime",
                R.string.about_bundled_onnxruntime_used,
                "Copyright (c) Microsoft Corporation",
                "MIT", "mit-onnxruntime.txt",
                "https://github.com/microsoft/onnxruntime",
            ),
        )
        add(
            Attribution(
                "sherpa-onnx",
                R.string.about_bundled_sherpa_used,
                "Copyright (c) 2022-2026 Xiaomi Corporation",
                "Apache-2.0", "apache-2.0.txt",
                "https://github.com/k2-fsa/sherpa-onnx",
            ),
        )
        add(
            Attribution(
                "Kiwi",
                R.string.about_bundled_kiwi_used,
                "Copyright 2017-2026 Minchul Lee and the Kiwi contributors",
                "Apache-2.0", "kiwi-notice.txt",
                "https://github.com/bab2min/Kiwi",
            ),
        )
        add(
            Attribution(
                "WebRTC (webrtc-sdk build)",
                R.string.about_bundled_webrtc_used,
                "Copyright The WebRTC project authors; webrtc-sdk contributors",
                "BSD-3-Clause", "bsd-3-clause.txt",
                "https://github.com/webrtc-sdk/android",
            ),
        )
        add(
            Attribution(
                "Vosk models",
                R.string.about_bundled_vosk_used,
                "Copyright Alpha Cephei Inc.",
                "Apache-2.0", "apache-2.0.txt",
                "https://alphacephei.com/vosk/models",
            ),
        )
        add(
            Attribution(
                "Kroko ASR community models",
                R.string.about_bundled_kroko_used,
                "Copyright Banafo",
                "CC-BY-SA-4.0", "cc-by-sa-4.0.txt",
                "https://huggingface.co/Banafo/Kroko-ASR",
            ),
        )
        // Speech out (Read back, the speaker buttons) on the same runtime:
        // Meta's MMS voices, which the phone fetches after the user accepts
        // their non-commercial licence.
        add(
            Attribution(
                "MMS-TTS voices",
                R.string.about_bundled_mms_used,
                "Copyright Meta Platforms, Inc.; ONNX conversion by Will Wade",
                "CC-BY-NC-4.0", "cc-by-nc-4.0.txt",
                "https://huggingface.co/facebook/mms-tts",
            ),
        )
        // Reduce background noise at Strong: the model the phone downloads
        // on request, run by sherpa-onnx's streaming denoiser.
        add(
            Attribution(
                "DPDFNet",
                R.string.about_bundled_dpdfnet_used,
                "Daniel Rika, Nino Sapir and Ido Gus, Ceva",
                "Apache-2.0", "apache-2.0.txt",
                "https://github.com/ceva-ip/DPDFNet",
            ),
        )
        // Add punctuation and capitals: the model the phone downloads on
        // request, run by libwmsherpa's token classifier.
        add(
            Attribution(
                "punctuation-restoration-small",
                R.string.about_bundled_punctuation_used,
                "Horizon Labs, on mmBERT by Johns Hopkins University (MIT)",
                "Apache-2.0", "apache-2.0.txt",
                "https://huggingface.co/Horizon-Labs/punctuation-restoration-small",
            ),
        )
        add(
            Attribution(
                "Omnilingual ASR",
                R.string.about_bundled_omnilingual_used,
                "Copyright 2025 (c) Meta Platforms, Inc. and affiliates",
                "Apache-2.0", "apache-2.0.txt",
                "https://github.com/facebookresearch/omnilingual-asr",
            ),
        )
        add(
            Attribution(
                "Silero VAD",
                R.string.about_bundled_silero_used,
                "Copyright (c) 2020-present Silero Team",
                "MIT", "mit-silero-vad.txt",
                "https://github.com/snakers4/silero-vad",
            ),
        )
        // Phrase end detector (#976): models the phone downloads on request.
        add(
            Attribution(
                "FireRedVAD",
                R.string.about_bundled_firered_vad_used,
                "Copyright 2026 Xiaohongshu (FireRedTeam)",
                "Apache-2.0", "apache-2.0.txt",
                "https://github.com/FireRedTeam/FireRedVAD",
            ),
        )
        add(
            Attribution(
                "Smart Turn",
                R.string.about_bundled_smart_turn_used,
                "Copyright (c) 2024-2025, Daily",
                "BSD-2-Clause", "bsd-2-clause-smart-turn.txt",
                "https://github.com/pipecat-ai/smart-turn",
            ),
        )
    }
    add(
        Attribution(
            "Unicode CLDR, emoji & Han data",
            R.string.about_bundled_unicode_used,
            "Copyright Unicode, Inc.",
            "Unicode License v3", "unicode-3.0.txt",
            "https://www.unicode.org/license.txt",
        ),
    )
    add(
        Attribution(
            "ICU4X",
            R.string.about_bundled_icu4x_used,
            "Copyright © 2020-2024 Unicode, Inc.",
            "Unicode License v3", "unicode-3.0.txt",
            "https://github.com/unicode-org/icu4x",
        ),
    )
    add(
        Attribution(
            "gemoji",
            R.string.about_bundled_gemoji_used,
            "Copyright (c) 2019 GitHub, Inc.",
            "MIT", "mit-gemoji.txt",
            "https://github.com/github/gemoji",
        ),
    )
    add(
        Attribution(
            "BanglaTLit",
            R.string.about_bundled_banglatlit_used,
            "Copyright (c) 2024 Aplycaebous",
            "MIT", "mit-banglatlit.txt",
            "https://github.com/farhanishmam/BanglaTLit",
        ),
    )
    // One row for 862 keyboards. Each carries its own MIT licence differing
    // only in the holder line, so the asset states the shared text once and
    // then names every holder; shipping 862 near-identical LICENSE.md files
    // would be the same obligation met less readably.
    add(
        Attribution(
            "Keyman keyboard layouts",
            R.string.about_bundled_keyman_used,
            "Copyright the individual keyboard authors, listed in the licence",
            "MIT", "mit-keyman.txt",
            "https://github.com/keymanapp/keyboards",
        ),
    )
    // The keysym names in XkbKeysyms.kt, generated from xorgproto's
    // keysymdef.h, whose notice asks to travel with copies.
    add(
        Attribution(
            "X11 keysym table",
            R.string.about_bundled_keysyms_used,
            "Copyright 1987, 1994, 1998 The Open Group; 1987 Digital Equipment Corporation",
            "X11", "x11-keysymdef.txt",
            "https://gitlab.freedesktop.org/xorg/proto/xorgproto",
        ),
    )
    // The layouts added for parity with FUTO Keyboard and FlorisBoard were
    // converted from their layout files, which both projects publish under
    // Apache-2.0 (FUTO's app is source-available; its layout repository is not).
    add(
        Attribution(
            "FUTO Keyboard layouts",
            R.string.about_bundled_futo_layouts_used,
            "Copyright the FUTO Keyboard layout contributors",
            "Apache-2.0", "apache-2.0.txt",
            "https://github.com/futo-org/futo-keyboard-layouts",
        ),
    )
    add(
        Attribution(
            "FlorisBoard layouts",
            R.string.about_bundled_floris_layouts_used,
            "Copyright The FlorisBoard Contributors",
            "Apache-2.0", "apache-2.0.txt",
            "https://github.com/florisboard/florisboard",
        ),
    )
    add(
        Attribution(
            "myanmar-tools",
            R.string.about_bundled_myanmar_tools_used,
            "Copyright 2017 Google LLC",
            "Apache-2.0", "apache-2.0.txt",
            "https://github.com/google/myanmar-tools",
        ),
    )
    // myanmar-tools compiles its converters from CLDR's transform, whose
    // notice travels with the rules.
    add(
        Attribution(
            "CLDR Zawgyi transform",
            R.string.about_bundled_cldr_zawgyi_used,
            "Copyright © 1991-2013 Unicode, Inc.",
            "Unicode License v3", "unicode-3.0.txt",
            "https://github.com/unicode-org/cldr",
        ),
    )
    add(
        Attribution(
            "Khipro",
            R.string.about_bundled_khipro_used,
            "Copyright (c) 2024 rank_coder, (c) 2026 KhiproTeam",
            "MIT", "mit-khipro.txt",
            "https://khipro.khiproteam.com/",
        ),
    )
    add(
        Attribution(
            "Bangla BSTI Mobile",
            R.string.about_bundled_bsti_used,
            "Copyright (c) 2026 Ekushey",
            "CC BY 4.0", "cc-by-4.0-bsti.txt",
            "https://github.com/ekushey-org/bangla-bsti-mobile",
        ),
    )
    add(
        Attribution(
            "IndicXlit",
            R.string.about_bundled_indicxlit_used,
            "Copyright (c) 2022 AI4Bhārat",
            "MIT", "mit-indicxlit.txt",
            "https://github.com/AI4Bharat/IndicXlit",
        ),
    )
    add(
        Attribution(
            "ibus-avro",
            R.string.about_bundled_avro_used,
            "Copyright (C) OmicronLab",
            "MPL-1.1", "mpl-1.1.txt",
            "https://github.com/omicronlab/ibus-avro",
        ),
    )
    add(
        Attribution(
            "OpenCC",
            R.string.about_bundled_opencc_used,
            "Copyright Carbo Kuo and OpenCC contributors",
            "Apache-2.0", "apache-2.0.txt",
            "https://github.com/BYVoid/OpenCC",
        ),
    )
    add(
        Attribution(
            "LSHK Jyutping table",
            R.string.about_bundled_lshk_used,
            "Copyright the Linguistic Society of Hong Kong",
            "CC BY 4.0", "cc-by-4.0-lshk.txt",
            "https://github.com/lshk-org/jyutping-table",
        ),
    )
    add(
        Attribution(
            "Editor colour palettes",
            R.string.about_bundled_palettes_used,
            "Copyright the respective theme authors",
            "MIT", "mit-color-themes.txt",
            "https://github.com/dracula/dracula-theme",
        ),
    )
    add(
        Attribution(
            "Google Fonts",
            R.string.about_bundled_fonts_used,
            "Copyright the respective font authors",
            "SIL Open Font License 1.1", "ofl-1.1.txt",
            "https://fonts.google.com/attribution",
        ),
    )
    // The two faces the settings app itself is set in. Unlike the keyboard's
    // typefaces above, these files are in the APK, so their own notices travel
    // with them rather than the generic licence text.
    add(
        Attribution(
            "Inter & Manrope",
            R.string.about_bundled_ui_fonts_used,
            "Copyright 2020 The Inter Project Authors; Copyright 2018 The Manrope Project Authors",
            "SIL Open Font License 1.1", "ofl-1.1-fonts.txt",
            "https://github.com/rsms/inter",
        ),
    )
}

/**
 * Data the app downloads on demand rather than bundling: the CJK conversion
 * packs and the per-language wordlists, offensive lists and emoji keyword
 * dictionaries served from the wmkeyboard-data repository, the sticker
 * editor's own background remover, and the on-device AI models. Their
 * licences attach to the data itself, so they are listed with full notices
 * exactly like the bundled components.
 */
private val dataPackAttributions: List<Attribution> = buildList {
    add(Attribution(
        "Firefox Translations models",
        R.string.about_pack_firefox_translations_used,
        "Copyright Mozilla Foundation",
        "MPL-2.0", "mpl-2.0.txt",
        "https://github.com/mozilla/translations",
    ))
    // The sticker generator's models, fetched only after the user accepts
    // their licences (NativeConsent.OPENRAIL_PP / OPENRAIL_M). SDXS is our MNN
    // conversion in the data repository; the NPU build is Qualcomm's own,
    // fetched from Qualcomm.
    add(Attribution(
        "SDXS-512-DreamShaper",
        R.string.about_pack_sdxs_used,
        "Copyright IDKiro (Yuda Song, Zehao Sun, Xuanwu Yin); distilled from DreamShaper 8 by Lykon; MNN conversion by WM Keyboard",
        "CreativeML Open RAIL++-M", "creativeml-openrail-plus-plus-m.txt",
        "https://huggingface.co/IDKiro/sdxs-512-dreamshaper",
    ))
    add(Attribution(
        "TAESD",
        R.string.about_pack_taesd_used,
        "Copyright (c) 2023 Ollin Boer Bohan",
        "MIT", "mit-taesd.txt",
        "https://github.com/madebyollin/taesd",
    ))
    add(Attribution(
        "Stable Diffusion v1.5 (Qualcomm AI Hub, w8a16)",
        R.string.about_pack_sd15_npu_used,
        "Copyright (c) 2022 Robin Rombach, Patrick Esser and contributors; compiled for Snapdragon by Qualcomm AI Hub",
        "CreativeML Open RAIL-M", "creativeml-openrail-m.txt",
        "https://huggingface.co/qualcomm/Stable-Diffusion-v1.5",
    ))
    // The upscaler's two networks (sticker editor, photo editor, small
    // stickers sent as a 512 square): nagadomi's own weights, exported to MNN
    // by the data repository's export_upscalers_mnn.py.
    add(Attribution(
        "waifu2x UpConv7 (art and photo)",
        R.string.about_pack_waifu2x_used,
        "Copyright (C) 2015 nagadomi; MNN conversion by WM Keyboard",
        "MIT", "mit-waifu2x.txt",
        "https://github.com/nagadomi/waifu2x",
    ))
    add(Attribution(
        "NSFW image detection (Falconsai)",
        R.string.about_pack_nsfw_used,
        "Copyright Falconsai; ViT-Base by Google; MNN conversion by WM Keyboard",
        "Apache-2.0", "apache-2.0.txt",
        "https://huggingface.co/Falconsai/nsfw_image_detection",
    ))
    // Fetched from Hugging Face only after the user accepts this licence
    // (NativeConsent.NLLB); the converted copy keeps Meta's terms.
    add(Attribution(
        "NLLB-200 (distilled 600M)",
        R.string.about_pack_nllb_used,
        "Copyright (c) Meta Platforms, Inc. and affiliates; CTranslate2 conversion by JustFrederik",
        "CC BY-NC 4.0", "cc-by-nc-4.0.txt",
        "https://huggingface.co/facebook/nllb-200-distilled-600M",
    ))
    add(Attribution(
        "Frequency wordlists",
        R.string.about_pack_wordlists_used,
        "Copyright the respective corpus authors",
        "CC BY-SA 4.0 / CC BY 4.0 / MIT / others", "wordlist-sources.txt",
        "https://github.com/wasi-master/wmkeyboard-data",
    ))
    add(Attribution(
        "AOSP LatinIME dictionaries",
        R.string.about_pack_aosp_wordlists_used,
        "Copyright (C) The Android Open Source Project",
        "Apache-2.0", "apache-2.0.txt",
        "https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/",
    ))
    add(Attribution(
        "Offensive word lists",
        R.string.about_pack_offensive_used,
        "Aggregated from LDNOOBW V2, profanity-list and other open lists",
        "CC0 / Unlicense / MIT", "wordlist-sources.txt",
        "https://github.com/wasi-master/wmkeyboard-data",
    ))
    add(Attribution(
        "Emoji keyword dictionaries",
        R.string.about_pack_emoji_keywords_used,
        "Copyright Unicode, Inc. (CLDR annotations and emoji data)",
        "Unicode License v3", "unicode-3.0.txt",
        "https://github.com/KDE/kemoji",
    ))
    add(Attribution(
        "Noto Sans Myanmar",
        R.string.about_pack_noto_myanmar_used,
        "Copyright 2022 The Noto Project Authors",
        "SIL Open Font License 1.1", "ofl-1.1.txt",
        "https://github.com/notofonts/myanmar",
    ))
    add(Attribution(
        "Noto Animated Emoji",
        R.string.about_pack_animated_emoji_used,
        "Copyright Google LLC",
        "CC BY 4.0", "cc-by-4.0-noto-animated.txt",
        "https://googlefonts.github.io/noto-emoji-animation/",
    ))
    add(Attribution(
        "CC-CEDICT",
        R.string.about_pack_cedict_used,
        "Copyright MDBG and CC-CEDICT contributors",
        "CC BY-SA 4.0", "cc-by-sa-4.0.txt",
        "https://cc-cedict.org/",
    ))
    add(Attribution(
        "mozc",
        R.string.about_pack_mozc_used,
        "Copyright 2010-2021 Google Inc.",
        "BSD-3-Clause", "bsd-3-clause.txt",
        "https://github.com/google/mozc",
    ))
    add(Attribution(
        "Phonikud & DictaBERT",
        R.string.about_pack_phonikud_used,
        "Copyright the Phonikud authors and Dicta: The Israel Center for Text Analysis",
        "CC BY 4.0", "phonikud-notice.txt",
        "https://github.com/thewh1teagle/phonikud",
    ))
    add(Attribution(
        "eyo-kernel",
        R.string.about_pack_eyo_used,
        "Copyright Denis Seleznev",
        "MIT", "mit-eyo.txt",
        "https://github.com/e2yo/eyo-kernel",
    ))
    add(Attribution(
        "Hazm",
        R.string.about_pack_hazm_used,
        "Copyright Alireza Nourian",
        "MIT", "mit-hazm.txt",
        "https://github.com/roshan-research/hazm",
    ))
    add(Attribution(
        "liblouis",
        R.string.about_pack_louis_used,
        "Copyright the liblouis contributors",
        "LGPL-2.1-or-later", "lgpl-2.1-liblouis.txt",
        "https://github.com/liblouis/liblouis",
    ))
    add(Attribution(
        "Tegaki handwriting models",
        R.string.about_pack_tegaki_used,
        "Copyright the Tegaki and Tomoe project contributors",
        "LGPL-2.1", "lgpl-2.1-tegaki.txt",
        "https://github.com/tegaki/tegaki",
    ))
    add(Attribution(
        "rime-cantonese & CC-Canto",
        R.string.about_pack_cantonese_used,
        "Copyright CanCLID and Pleco Inc.",
        "CC BY 4.0 / CC BY-SA 3.0", "jyutping-sources.txt",
        "https://github.com/rime/rime-cantonese",
    ))
    add(Attribution(
        "Chinese stroke code table",
        R.string.about_pack_stroke_used,
        "Copyright (c) 2021, FeiJiang Ye",
        "BSD-2-Clause", "bsd-2-clause-stroke.txt",
        "https://github.com/yefeijiang/Chinese-characters-code-table",
    ))
    add(Attribution(
        "Kiwi model",
        R.string.about_pack_kiwi_used,
        "Copyright 2017-2026 Minchul Lee and the Kiwi contributors",
        "Apache-2.0", "kiwi-notice.txt",
        "https://github.com/bab2min/Kiwi",
    ))
    add(Attribution(
        "libhangul Hanja table",
        R.string.about_pack_hanja_used,
        "Copyright (c) 2005, 2006 Choe Hwanjin",
        "BSD-3-Clause", "bsd-3-clause-hanja.txt",
        "https://github.com/libhangul/libhangul",
    ))
    add(Attribution(
        "Unicode Unihan database",
        R.string.about_pack_unihan_used,
        "Copyright Unicode, Inc.",
        "Unicode License v3", "unicode-3.0.txt",
        "https://www.unicode.org/",
    ))
    // The three below are downloaded by features F-Droid's build lacks, so it
    // does not list what it cannot fetch.
    if (com.wasimaster.wmkeyboard.core.stickers.SubjectCutout.supported) {
        add(Attribution(
            "U\u00b2-Net-P background remover",
            R.string.about_pack_cutout_used,
            "Copyright the U\u00b2-Net authors (Xuebin Qin et al., 2020); rembg's u2netp.onnx, converted with onnx2tf",
            "Apache-2.0", "apache-2.0.txt",
            "https://github.com/xuebinqin/U-2-Net",
        ))
    }
    // Every build: the LiteRT conversion where LiteRT is, our MNN export of
    // the same weights in F-Droid's (the data repository's export_ppocrv5_mnn.py).
    add(Attribution(
        "PaddleOCR PP-OCRv5 models",
        R.string.about_pack_paddle_used,
        if (BuildConfig.ENABLE_WHISPER) {
            "Copyright PaddlePaddle Authors; LiteRT conversion by iFleey (PPOCRv5-Android)"
        } else {
            "Copyright PaddlePaddle Authors"
        },
        "Apache-2.0", "apache-2.0.txt",
        "https://github.com/PaddlePaddle/PaddleOCR",
    ))
    if (BuildConfig.ENABLE_LITERT_LM) {
        add(Attribution(
            "On-device AI models (Gemma, Qwen, SmolLM2)",
            R.string.about_pack_llm_used,
            "Copyright Google LLC, Alibaba Cloud and Hugging Face; mobile builds by Google's litert-community",
            "Apache-2.0 / Gemma Terms of Use", "apache-2.0.txt",
            "https://huggingface.co/litert-community",
        ))
    }
}

/**
 * Services the tools call over the network. Nothing of theirs is bundled, so
 * these are attribution and terms links rather than licence texts.
 */
private val serviceAttributions: List<Attribution> = listOf(
    Attribution(
        "PairDrop", R.string.about_service_pairdrop_used, "",
        "The server's own terms", null,
        "https://pairdrop.net/",
    ),
    Attribution(
        "Brave Search", R.string.about_service_brave_used, "",
        "Brave Search API terms", null,
        "https://brave.com/search/api/",
    ),
    Attribution(
        "Tavily", R.string.about_service_tavily_used, "",
        "Tavily terms of service", null,
        "https://www.tavily.com/terms",
    ),
    Attribution(
        "KLIPY & GIPHY", R.string.about_service_gif_used, "",
        "Provider API terms", null,
        "https://developers.giphy.com/",
    ),
    Attribution(
        "Wikipedia", R.string.about_service_wikipedia_used, "",
        "CC BY-SA (article text keeps its own licence)", null,
        "https://en.wikipedia.org/wiki/Wikipedia:Copyrights",
    ),
    Attribution(
        "Google Translate", R.string.about_service_translate_used, "",
        "Google Cloud terms", null,
        "https://cloud.google.com/terms",
    ),
    Attribution(
        "Open-Meteo", R.string.about_service_weather_used, "",
        "CC BY 4.0", null,
        "https://open-meteo.com/en/license",
    ),
    Attribution(
        "Frankfurter & ExchangeRate-API", R.string.about_service_currency_used, "",
        "Provider terms", null,
        "https://www.frankfurter.app/",
    ),
    Attribution(
        "Coinbase, currency-api & CoinGecko", R.string.about_service_crypto_used, "",
        "Provider terms", null,
        "https://docs.cdp.coinbase.com/",
    ),
    Attribution(
        "Free Dictionary API", R.string.about_service_dictionary_used, "",
        "Provider terms", null,
        "https://dictionaryapi.dev/",
    ),
    Attribution(
        "Wiktionary & kaikki.org", R.string.about_service_wiktionary_used, "",
        "CC BY-SA 4.0 and GFDL", null,
        "https://en.wiktionary.org/wiki/Wiktionary:Copyrights",
    ),
    // Downloaded, not called: the Dictionary tool's offline dictionary (#665).
    Attribution(
        "Open English WordNet", R.string.about_service_oewn_used, "",
        "CC BY 4.0", null,
        "https://en-word.net/",
    ),
    Attribution(
        "Datamuse", R.string.about_service_synonyms_used, "",
        "Provider terms", null,
        "https://www.datamuse.com/api/",
    ),
    Attribution(
        "Hugging Face", R.string.about_service_models_used, "",
        "Per-model licence, accepted on the model's page", null,
        "https://huggingface.co/terms-of-service",
    ),
    Attribution(
        "Anthropic", R.string.about_service_byok_used, "",
        "Provider terms, under your own account", null,
        "https://www.anthropic.com/legal/consumer-terms",
    ),
    Attribution(
        "OpenAI", R.string.about_service_byok_used, "",
        "Provider terms, under your own account", null,
        "https://openai.com/policies/terms-of-use/",
    ),
    Attribution(
        "Google AI", R.string.about_service_byok_used, "",
        "Provider terms, under your own account", null,
        "https://ai.google.dev/terms",
    ),
    Attribution(
        "xAI", R.string.about_service_byok_used, "",
        "Provider terms, under your own account", null,
        "https://x.ai/legal/terms-of-service",
    ),
    Attribution(
        "DeepSeek", R.string.about_service_byok_used, "",
        "Provider terms, under your own account", null,
        "https://platform.deepseek.com/downloads/DeepSeek%20Open%20Platform%20Terms%20of%20Service.html",
    ),
    Attribution(
        "Brave Answers", R.string.about_service_byok_used, "",
        "Brave Search API terms, under your own account", null,
        "https://brave.com/search/api/",
    ),
    // AiProvider.OPENAI_COMPATIBLE has no fixed endpoint: the address is typed
    // by the user, so the only honest attribution is that whatever they point
    // it at governs itself. The row still needs a destination (an empty URL
    // would throw out of openUri), so it goes to the docs page that explains
    // the escape hatch. Listed last so the named providers stay grouped.
    Attribution(
        "Any other OpenAI-compatible service", R.string.about_service_byok_used, "",
        "Terms of whichever service you point it at", null,
        "$DOCS_URL/tools/ai/#any-other-openai-compatible-service",
    ),
)

/**
 * "A good middle · Several languages", or "Not answered".
 *
 * Depth first, because it is the answer that changes the most: it decides how
 * many tools are pinned, how many wizard pages exist and whether the theme
 * gallery groups families. The privacy answer is left out — it wrote its
 * settings once and they are visible on the Privacy screen, so repeating it
 * here would imply this row still governs them.
 */
@Composable
private fun personaSummary(persona: OnboardingSettings): String {
    val depth = when (persona.personaDepth) {
        PersonaDepth.MINIMAL -> R.string.about_persona_depth_minimal
        PersonaDepth.BALANCED -> R.string.about_persona_depth_balanced
        PersonaDepth.POWER -> R.string.about_persona_depth_power
        PersonaDepth.UNSET -> return stringResource(R.string.about_persona_unset)
    }
    val languages = when (persona.personaLanguages) {
        PersonaLanguages.ONE -> R.string.about_persona_languages_one
        PersonaLanguages.MANY -> R.string.about_persona_languages_many
        PersonaLanguages.UNSET -> return stringResource(depth)
    }
    return stringResource(R.string.about_persona_value, stringResource(depth), stringResource(languages))
}

/**
 * The app's own interface language (#322). "System default" is the empty key
 * and follows the phone; the rest are every translation this build carries,
 * each named in itself with our name for it underneath.
 *
 * Choosing restarts the screen in the new language, so the selection read
 * below is fresh on every visit and needs no state of its own.
 */
@Composable
private fun AppLanguageSetting() {
    val context = LocalContext.current
    val uiLocale = LocalConfiguration.current.locales[0]
    val selected = remember { AppLanguage.selected(context).orEmpty() }
    val systemName = nativeLanguageName(AppLanguage.systemLocale().toLanguageTag())
    val systemLabel = stringResource(R.string.about_app_language_system, systemName)
    val options = remember(uiLocale, systemLabel) {
        val collator = java.text.Collator.getInstance(uiLocale)
        listOf("" to systemLabel) + AppLanguage.available
            .map { it to nativeLanguageName(it) }
            .sortedWith(compareBy(collator) { it.second })
    }
    ChoiceSetting(
        R.string.about_app_language_title,
        subtitle = stringResource(R.string.about_app_language_subtitle),
        info = stringResource(R.string.about_app_language_info),
        options = options,
        selected = selected,
        default = "",
        detail = { tag ->
            if (tag.isEmpty()) {
                null
            } else {
                languageNameIn(tag, uiLocale)
                    .takeIf { it != nativeLanguageName(tag) }
                    ?.let { ChoiceDetail(description = it) }
            }
        },
        onChange = { tag -> if (tag != selected) AppLanguage.select(context, tag.ifEmpty { null }) },
    )
}

@Composable
internal fun AboutSettings(
    settings: LiveSettings,
    repository: SettingsRepository,
    persona: OnboardingSettings,
    onOpenLicenses: () -> Unit,
    onOpenSupporters: () -> Unit = {},
    onOpenLicenseText: (String) -> Unit,
    onOpenDebugLog: () -> Unit = {},
    onOpenStorage: () -> Unit = {},
    onOpenImportHub: () -> Unit = {},
    onOpenStatistics: () -> Unit = {},
    onOpenEggGame: () -> Unit = {},
    onReplayOnboarding: () -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val flavor = BuildConfig.EDITION
    val channel = when {
        BuildConfig.ENABLE_PLAY_STORE -> " · Play Store"
        BuildConfig.ENABLE_FDROID -> " · F-Droid"
        else -> ""
    }

    // Read outside the click handlers: they are plain lambdas, not composables.
    val bugReportSubject = stringResource(R.string.about_bug_report_email_subject)

    SettingsGroup(
        stringResource(R.string.about_community_title),
        info = stringResource(R.string.about_community_body),
    ) {
        item { TelegramRow { uriHandler.openUri(Support.TELEGRAM_URL) } }
        item { SocialStrip() }
        item {
            NavRow(
                R.string.about_supporters_title,
                stringResource(R.string.about_supporters_subtitle),
                route = "supporters",
                onClick = onOpenSupporters,
            )
        }
    }

    SettingsGroup(
        stringResource(R.string.about_feedback_title),
        info = stringResource(R.string.about_feedback_body),
    ) {
        item {
            NavRow(
                R.string.about_report_bug_title,
                stringResource(R.string.about_report_bug_subtitle),
            ) {
                uriHandler.openUri(Support.ISSUES_URL)
            }
        }
        item {
            NavRow(R.string.about_email_developer_title, Support.EMAIL) {
                if (!Support.email(context, bugReportSubject, Support.bugReport())) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.about_no_email_app_error, Support.EMAIL),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    // The version row's Android-style secret: seven taps open the keycap
    // catcher. The counter never leaves this screen, and the row keeps
    // looking like the inert fact it is the other 6 taps.
    var versionTaps by remember { mutableIntStateOf(0) }
    var versionTapToast by remember { mutableStateOf<Toast?>(null) }

    // The launcher's name for the app lives in PackageManager, not in
    // settings (see LauncherName), and nothing else changes it while this
    // screen is open, so one read per visit is the truth.
    var shortName by remember { mutableStateOf(LauncherName.isShort(context)) }
    var launcherShown by remember { mutableStateOf(LauncherName.isShown(context)) }
    // A TV home screen lists only the television alias, which this never
    // touches, so the switch would do nothing there.
    val television = remember(context) { context.isTelevision() }
    // Read out here because the group's builder below is not composable.
    val canDropLanguageSwitch = !AppLanguage.canChoose && languageSwitchCanBeDropped()

    SettingsGroup(
        stringResource(R.string.about_app_title),
        info = stringResource(R.string.about_free_software_body),
    ) {
        item {
            NavRow(
                R.string.about_version_title,
                stringResource(
                    R.string.about_version_subtitle,
                    flavor,
                    BuildConfig.BUILD_TYPE,
                    channel,
                    BuildConfig.VERSION_CODE,
                ),
                value = BuildConfig.VERSION_NAME,
            ) {
                versionTaps++
                when {
                    versionTaps >= 7 -> {
                        versionTaps = 0
                        versionTapToast?.cancel()
                        onOpenEggGame()
                    }
                    versionTaps >= 4 -> {
                        val left = 7 - versionTaps
                        versionTapToast?.cancel()
                        versionTapToast = Toast.makeText(
                            context,
                            context.resources.getQuantityString(
                                R.plurals.egg_version_taps_body, left, left,
                            ),
                            Toast.LENGTH_SHORT,
                        ).also { it.show() }
                    }
                }
            }
        }
        item {
            // The installed version's notes, from the APK (see
            // InstalledReleaseNotes), so a changelog is one tap away on every
            // channel and not only while an update is on offer.
            var showNotes by remember { mutableStateOf(false) }
            if (showNotes) InstalledReleaseNotesDialog { showNotes = false }
            NavRow(
                R.string.update_action_notes,
                stringResource(R.string.about_whats_new_subtitle, BuildConfig.VERSION_NAME),
            ) { showNotes = true }
        }
        item(level = SettingLevel.STANDARD) {
            // The licence identifier and the copyright line travel verbatim.
            NavRow(R.string.about_licence_title, "MIT, © 2026 Wasi Master") {
                onOpenLicenseText("mit-wmkeyboard.txt")
            }
        }
        item(level = SettingLevel.STANDARD) {
            NavRow(
                R.string.about_source_title,
                SOURCE_URL.removePrefix("https://"),
            ) {
                uriHandler.openUri(SOURCE_URL)
            }
        }
        // How much of the settings app shows, and whether what it leaves
        // out is pointed at; see SettingsDepth.kt. Here, with the other rows
        // about the app itself, rather than on every page.
        item {
            val depth = LocalSettingsDepth.current
            ChoiceSetting(
                title = R.string.settings_depth_title,
                subtitle = stringResource(R.string.settings_depth_subtitle),
                info = stringResource(R.string.settings_depth_info),
                options = depthOptions(),
                selected = depth.depth,
                detail = { ChoiceDetail(depthDescription(it)) },
            ) { depth.change(it) }
        }
        item {
            ToggleSetting(
                R.string.settings_hidden_rows_title,
                stringResource(R.string.settings_hidden_rows_subtitle),
                settings.watch { it.appUi.hiddenSettingsRows },
                default = SettingsDefaults.appUi.hiddenSettingsRows,
            ) { scope.launch { repository.setHiddenSettingsRows(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.about_launcher_name_title,
                stringResource(R.string.about_launcher_name_subtitle),
                checked = shortName,
                info = stringResource(R.string.about_launcher_name_info),
                default = LauncherName.DEFAULT_SHORT,
                onChange = {
                    shortName = it
                    LauncherName.setShort(context, it)
                },
            )
        }
        item(visible = !television, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.about_launcher_shown_title,
                stringResource(R.string.about_launcher_shown_subtitle),
                checked = launcherShown,
                info = stringResource(R.string.about_launcher_shown_info),
                default = LauncherName.DEFAULT_SHOWN,
                onChange = {
                    launcherShown = it
                    LauncherName.setShown(context, it)
                },
            )
        }
        // An English-only build has nothing to choose between, so the same
        // slot says so and offers the build with every language instead.
        if (AppLanguage.canChoose) {
            item { AppLanguageSetting() }
        } else {
            item {
                AllLanguagesRow(
                    settings,
                    extraLanguages = BuildConfig.TRANSLATED_LANGUAGE_COUNT,
                    englishName = nativeLanguageName("en"),
                )
            }
            item(visible = canDropLanguageSwitch, level = SettingLevel.ADVANCED) { KeepEnglishOnlyRow() }
        }
        item(level = SettingLevel.STANDARD) {
            NavRow(
                R.string.about_storage_title,
                stringResource(R.string.about_storage_subtitle),
                route = "storage",
                onClick = onOpenStorage,
            )
        }
        item {
            NavRow(
                R.string.import_hub_title,
                stringResource(R.string.import_hub_subtitle),
                route = "import_hub",
                onClick = onOpenImportHub,
            )
        }
        // Owned by the Statistics extension: gone with it.
        item(level = SettingLevel.STANDARD) {
            NavRow(
                R.string.statistics_title,
                stringResource(R.string.statistics_subtitle),
                route = "statistics",
                onClick = onOpenStatistics,
            )
        }
        item(level = SettingLevel.ADVANCED) {
            NavRow(
                R.string.about_diagnostics_title,
                stringResource(R.string.about_diagnostics_subtitle),
                route = "debug_log",
                onClick = onOpenDebugLog,
            )
        }
        item(level = SettingLevel.STANDARD) {
            // The quiz answers still decide how many tools are pinned, how the
            // theme gallery is laid out and which pages the wizard even has,
            // and until this row existed there was nowhere in settings that
            // said so or showed what had been answered. Tapping goes to the
            // wizard, whose first question is this one.
            NavRow(
                R.string.about_persona_title,
                stringResource(R.string.about_persona_subtitle),
                value = personaSummary(persona),
                onClick = onReplayOnboarding,
            )
        }
        item(level = SettingLevel.STANDARD) {
            // The wizard's only way back in once it has been finished. A
            // replay never rewrites settings, so the row can promise that.
            NavRow(
                R.string.about_replay_onboarding_title,
                stringResource(R.string.about_replay_onboarding_subtitle),
                onClick = onReplayOnboarding,
            )
        }
    }

    // Directly under the version it is about. What it can offer depends on the
    // channel: a Play build hands off to the Play Store, a direct-download
    // build downloads and installs the new version itself, and an F-Droid
    // build points at F-Droid. A build with no update source reports
    // "unsupported" and this draws nothing.
    UpdateSettings(settings)

    SettingsGroup(stringResource(R.string.about_documentation_title)) {
        item {
            NavRow(
                R.string.about_user_guide_title,
                DOCS_URL.removePrefix("https://"),
            ) {
                uriHandler.openUri(DOCS_URL)
            }
        }
        item {
            NavRow(
                R.string.about_privacy_policy_title,
                stringResource(R.string.about_privacy_policy_subtitle),
            ) {
                uriHandler.openUri(PRIVACY_POLICY_URL)
            }
        }
        item(level = SettingLevel.ADVANCED) {
            // A web link to one setting, from the setting itself (#646). The
            // rows read the mode through LocalSettingLinks, so this row and
            // its own heading change the moment it is picked.
            ChoiceSetting(
                R.string.about_setting_links_title,
                subtitle = stringResource(R.string.about_setting_links_subtitle),
                info = stringResource(R.string.about_setting_links_info),
                options = listOf(
                    SettingLinkMode.OFF to stringResource(CommonR.string.common_off),
                    SettingLinkMode.HOLD to stringResource(R.string.about_setting_links_hold),
                    SettingLinkMode.BUTTON to stringResource(R.string.about_setting_links_button),
                ),
                selected = settings.watch { it.appUi.settingLinks },
                default = SettingsDefaults.appUi.settingLinks,
            ) { scope.launch { repository.setSettingLinks(it) } }
        }
    }

    SettingsGroup(stringResource(CommonR.string.common_share)) {
        item(visible = !BuildConfig.ENABLE_FDROID) {
            NavRow(
                R.string.about_share_play_link,
                PLAY_STORE_URL.removePrefix("https://"),
            ) {
                shareLink(context, PLAY_STORE_URL)
            }
        }
        item(visible = !BuildConfig.ENABLE_PLAY_STORE) {
            NavRow(
                R.string.about_share_fdroid_link,
                FDROID_URL.removePrefix("https://"),
            ) {
                shareLink(context, FDROID_URL)
            }
        }
        item(visible = !BuildConfig.ENABLE_PLAY_STORE, level = SettingLevel.ADVANCED) {
            NavRow(
                R.string.about_share_github_link,
                SOURCE_URL.removePrefix("https://"),
            ) {
                shareLink(context, SOURCE_URL)
            }
        }
    }

    SettingsGroup(stringResource(R.string.about_third_party_title), level = SettingLevel.STANDARD) {
        item {
            NavRow(
                R.string.about_licences_title,
                stringResource(R.string.about_licences_subtitle),
                route = "licenses",
            ) { onOpenLicenses() }
        }
    }

    SettingsGroup(stringResource(R.string.about_word_lists_title), level = SettingLevel.ADVANCED) {
        item {
            NavRow(
                R.string.about_dictionaries_title,
                stringResource(R.string.about_dictionaries_subtitle),
            ) {}
        }
    }
}

@Composable
internal fun LicensesScreen(onOpenLicenseText: (String) -> Unit) {
    val uriHandler = LocalUriHandler.current

    SettingsGroup(
        stringResource(R.string.about_section_bundled_title),
        info = stringResource(R.string.about_licences_intro_body),
    ) {
        bundledAttributions.forEach { entry ->
            item {
                NavRow(entry.name, licenceRowSubtitle(entry)) {
                    if (entry.licenseAsset != null) onOpenLicenseText(entry.licenseAsset)
                    else uriHandler.openUri(entry.url)
                }
            }
        }
    }
    SettingsGroup(
        stringResource(R.string.about_section_data_packs_title),
        info = stringResource(R.string.about_data_packs_body),
    ) {
        dataPackAttributions.forEach { entry ->
            item {
                NavRow(entry.name, licenceRowSubtitle(entry)) {
                    if (entry.licenseAsset != null) onOpenLicenseText(entry.licenseAsset)
                    else uriHandler.openUri(entry.url)
                }
            }
        }
    }
    SettingsGroup(
        stringResource(R.string.about_section_services_title),
        info = stringResource(R.string.about_services_body),
    ) {
        serviceAttributions.forEach { entry ->
            item {
                val subtitle = stringResource(
                    R.string.about_service_row_subtitle,
                    stringResource(entry.usedRes),
                    entry.license,
                )
                NavRow(entry.name, subtitle) {
                    uriHandler.openUri(entry.url)
                }
            }
        }
    }
}

/** "What it is used for", then the copyright line and the licence name. */
@Composable
private fun licenceRowSubtitle(entry: Attribution): String = stringResource(
    R.string.about_licence_row_subtitle,
    stringResource(entry.usedRes),
    entry.copyright,
    entry.license,
)

/** Renders one bundled licence file verbatim. */
@Composable
internal fun LicenseTextScreen(assetName: String) {
    val context = LocalContext.current
    var text by remember(assetName) { mutableStateOf<String?>(null) }
    LaunchedEffect(assetName) {
        text = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("licenses/$assetName").use { it.readBytes().decodeToString() }
            }.getOrElse { context.getString(R.string.about_licence_text_error) }
        }
    }
    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(8.dp))
        // Licence texts are hard-wrapped at 80 columns; scroll sideways rather
        // than reflow, so the original layout stays intact.
        Text(
            text.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        )
    }
}

/**
 * One of the project's accounts, drawn as a brand tile in [SocialStrip].
 * [name] and [handle] are proper nouns and stay in English.
 */
private data class SocialLink(
    val name: String,
    val handle: String,
    val url: String,
    @DrawableRes val glyph: Int,
    /** The brand's own colour. Null for the black-and-white marks, which follow the theme. */
    val brand: Color?,
)

private val socialLinks = listOf(
    SocialLink("X", "@wmkeyboard", Support.X_URL, R.drawable.ic_brand_x, null),
    SocialLink("Reddit", "r/wmkeyboard", Support.REDDIT_URL, R.drawable.ic_brand_reddit, Color(0xFFFF4500)),
    SocialLink("Instagram", "@wmkeyboardapp", Support.INSTAGRAM_URL, R.drawable.ic_brand_instagram, Color(0xFFE4405F)),
    SocialLink("TikTok", "@wmkeyboard", Support.TIKTOK_URL, R.drawable.ic_brand_tiktok, null),
)

private val TelegramBlue = Color(0xFF26A5E4)

/**
 * The Telegram group, as the community group's lead row: a solid Telegram tile
 * where the house rows have a tinted one, so it reads as somewhere to go rather
 * than a setting, and a Join pill that says what the tap does. The pill is
 * drawn, not a second button: the whole row is the target, which keeps one
 * touch target per row the way every other row works.
 */
@Composable
private fun TelegramRow(onClick: () -> Unit) {
    val title = stringResource(R.string.about_telegram_title)
    WmRow(
        title = title,
        subtitle = stringResource(R.string.about_telegram_subtitle),
        highlightKey = R.string.about_telegram_title,
        leading = { BrandTile(R.drawable.ic_brand_telegram, TelegramBlue, size = WmIconTileSize) },
        trailing = {
            Surface(
                shape = CircleShape,
                color = TelegramBlue,
                contentColor = Color.White,
            ) {
                Text(
                    stringResource(R.string.about_telegram_join),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        },
        onClick = onClick,
    )
}

/**
 * "Follow WM Keyboard" and a tile per account, shared across the row's width so
 * four marks sit evenly on any phone. A tap opens the account (the platform's
 * app takes the link when it is installed); a long press copies the link
 * instead, for sharing it somewhere the app is not.
 */
@Composable
private fun SocialStrip() {
    val title = stringResource(R.string.about_follow_title)
    HighlightableRow(title, R.string.about_follow_title) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.about_follow_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                socialLinks.forEach { link ->
                    SocialTile(link, Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SocialTile(link: SocialLink, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val still = LocalReduceMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && !still) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "socialTile",
    )
    val description = stringResource(R.string.about_social_cd, link.name, link.handle)
    val copied = stringResource(R.string.about_link_copied)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(),
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    manager?.setPrimaryClip(ClipData.newPlainText(link.name, link.url))
                    // Android 13+ confirms a copy itself; a toast on top would say it twice.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                    }
                },
                onClick = { uriHandler.openUri(link.url) },
            )
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = 8.dp),
    ) {
        BrandTile(
            link.glyph,
            link.brand ?: MaterialTheme.colorScheme.onSurface,
            glyphColor = if (link.brand == null) MaterialTheme.colorScheme.surface else Color.White,
            size = 52.dp,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            link.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A brand mark on a solid circle of its own colour. */
@Composable
private fun BrandTile(
    @DrawableRes glyph: Int,
    color: Color,
    modifier: Modifier = Modifier,
    glyphColor: Color = Color.White,
    size: Dp,
) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(glyph),
            contentDescription = null,
            tint = glyphColor,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}


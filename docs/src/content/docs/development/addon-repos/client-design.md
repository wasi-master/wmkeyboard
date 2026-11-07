---
title: "Addon repositories: client handling design"
description: "How the app fetches, verifies and installs addons from a repository."
sidebar:
  label: "Client design"
  order: 4
---

How the app should fetch, resolve, validate and install addons from a repository.
Companion to the [repository format](/development/addon-repos/repo-format/). Status: **implemented**. This page describes what the app does.

Guiding principle: **reuse everything.** Every addon payload is already a native import
format, so install = *download the file, hand it to the existing importer*. The only new
code is the manifest layer + a fetch/browse/install shell.

## 1. Data model (new)

New file `core/addons/AddonRepo.kt`, kotlinx-serialization, decoded with the same tolerant
codec settings the rest of the app uses (`Json { ignoreUnknownKeys = true; coerceInputValues = true }`):

```kotlin
@Serializable data class AddonRepoManifest(
    val format: String,           // must equal "wmkeyboard-repo"
    val version: Int = 1,
    val repo: AddonRepoInfo,
    val addons: List<AddonEntry> = emptyList(),
)
@Serializable data class AddonRepoInfo(
    val id: String, val name: String,
    val description: String = "", val author: String = "",
    val homepage: String = "", val icon: String? = null,
    val updatedAt: String = "",
)
@Serializable data class AddonEntry(
    val id: String,
    val type: AddonType,          // enum with an `Unknown` fallback for forward-compat
    val name: String,
    val version: String,          // semver
    val author: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val path: String,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    val previews: List<String> = emptyList(),
    val minAppVersion: Int? = null,
    val langId: String? = null,
    val langIds: List<String> = emptyList(),   // script coverage, mostly for fonts
    val license: String? = null,               // SPDX id or short name
    val licenseText: String? = null,           // inline
    val licenseFile: String? = null,           // relative or absolute; fetched on demand
)
```

`AddonRepoCodec.decode(json)` validates the `format` tag (reject on mismatch, like
`LayoutFile.decode` does at `LayoutFile.kt:77`) and returns `null` on failure rather than
throwing.

## 2. Add-a-repo → manifest URL resolution

Accept a pasted string and normalise to the raw manifest URL (§4 of the format doc):

- `github.com/USER/REPO` → `raw.githubusercontent.com/USER/REPO/HEAD/wmkeyboard-repo.json`
- `github.com/USER/REPO/tree/BRANCH` (or `/blob/BRANCH`) → same on `BRANCH`
- any other `https` URL ending in `.json` → as-is
- a repository page on another forge (Codeberg, a GitLab, SourceHut, Bitbucket, a self-hosted
  Forgejo) → that forge's raw `wmkeyboard-repo.json`, via `GitForge` / `RepoLocation`
- any other `https` URL → read as a directory, with `wmkeyboard-repo.json` appended
- a pasted address with no scheme at all → assumed `https`, then the rules above

Keep the resolved manifest URL's directory as the base for resolving relative
`path` / `previews` / `icon`. Enforce **`https` only**.

## 3. Persistence: a file-backed store, not DataStore

`filesDir/addons/`, following the same contract as `IconPackStore` / `StickerPackStore`:

- `repos.json` holds `{ version, repos: [{ url, manifestUrl, addedAt, cachedManifest, fetchedAt,
  seeded }], autoRefresh, refreshUnmeteredOnly }`. The last two are the repo-refresh settings.
  Both default to what a file written before they existed already did: fetch on every visit,
  over any connection.
- `installed.json` holds `{ version, installed: { "<repoId>/<addonId>": { version, type, localRef,
  installedAt, name, repoName, manifestUrl, description, author } } }`, where `localRef` is the
  created custom-theme/layout id, dictionary file path, pack id, font id or sound id. `name`,
  `repoName`, `description` and `author` let the Installed list and the offline page render
  without the manifest; `manifestUrl` lets the Installed list reopen the addon's own page. Drives
  the Installed / Update-available / Uninstall states.

The store's directory getter returns `null` when `!DirectBoot.isUserUnlocked`, so it reads as
empty before first unlock; `attach(context)` re-points it afterwards. A `reconcile()` sweep drops
entries whose local target the user has since deleted by hand.

> This is a deliberate change from the original design, which put `addon_repos` /
> `installed_addons` in DataStore next to `custom_themes` / `custom_layouts`. Cached manifests
> run to several KB each, and `KeyboardSettings` is re-emitted to the IME on every settings
> change. Parking manifests there would push that payload through the keyboard's hot path for
> no benefit, and `KeyboardSettings` is already near the JVM's 255-argument `copy$default`
> ceiling. Net new `KeyboardSettings` fields for the addon layer: zero.

## 4. Fetch

- Manifest and small text payloads (theme/layout/snippets JSON): existing blocking
  `ToolHttp.get(url)` on `Dispatchers.IO` (`core/tools/ToolHttp.kt`).
- Dictionaries can be large: stream to a temp file with a **32 MiB cap** (matches
  `CustomDictionaries.MAX_BYTES`), following the resumable pattern in
  `core/localllm/LocalLlmDownloadManager.kt`. Accept optional `.txt.gz`.
- `sha256` is **optional**. When present, verify it in a separate pass over the finished
  `.part` file, after the transfer completes and before install, aborting on mismatch. Hashing
  the download only once it has landed avoids re-hashing an already-verified prefix on a resumed
  transfer. When absent, install proceeds and the UI marks the addon unverified. A beginner
  hand-writing a manifest must not be blocked on computing hashes. `sizeBytes` is likewise a
  pre-download guard only. The mid-stream cap does the real work.
- If `minAppVersion > BuildConfig.VERSION_CODE`, disable install with an "update the app" note.

## 5. Install dispatch

Most types route straight to an importer that already existed. Three did not, and were built
as part of this work. Those rows are marked **new**.

| type | Install path |
|---|---|
| `theme` | `ThemeCodec.decode` → `copy(id = "custom_" + now)` → `ThemeSpec.withExtractedImages(themeImagesDir(ctx))` → `SettingsRepository.upsertCustomTheme` |
| `layout` | `LayoutFile.decode` → `LayoutCodec.migrateLayout` → `LayoutSpec.repair` → fresh id → `SettingsRepository.upsertCustomLayout` |
| `dictionary` | `CustomDictionaries.import(filesDir, langId, name, stream)`. Validates ≥1 word, 32 MiB cap. Guard: `langId` must exist in `LanguageRegistry` (else surface a clear "unsupported language" error). |
| `emoji_keywords` | `EmojiKeywordPacks.import(filesDir, langId, name, stream)`. Per-language TSV of emoji keywords, validated to ≥1 emoji, 8 MiB cap, gzip tolerated. Same `langId`-must-exist-in-`LanguageRegistry` guard as `dictionary`. Merged into the bundled catalogue by `EmojiKeywordPack.merge` at load, so the pack feeds emoji search, the inline `:name:` search, emoji prediction and the long-press description in one step. |
| `snippets` | **new** `SnippetFile.decode` (the `.wmsnippets.json` codec was specified but never written) → for each entry `SnippetStore.add(snippet)`, ids reassigned (the whole snippet, so a field added to the format later cannot be silently dropped on the way in). The same codec gives the app snippet export/import, so anything a repo can ship the app can also produce. |
| `espanso` | `SnippetPayload.read(payload, name)` filtered to `isEspanso`, converted to snippets, then stored through the same `storeSnippets` path `snippets` uses. Uninstalls by the same rule too. |
| `stickers` | `StickerPackFile.import(input, store)`. Extracts the `*.wmstickers` ZIP archive, validates the `wmkeyboard-stickers` envelope in `pack.json`, normalizes images to app-private sticker storage, and registers the pack in `StickerPackStore`. |
| `icon_pack` | `IconPackFile.import(input, store)`. Extracts `*.wmicons`, validates the `wmkeyboard-icons` envelope in `pack.json`, keeps every entry naming a slot `IconSlots` knows (parsing each SVG to prove it renders), and registers the pack in `IconPackStore`. |
| `font` | **new subsystem.** The app had three fixed custom-font slots, each overwritten on import, so a *library* of installed fonts had to be built first: `FontStore` (`filesDir/fonts/installed/`, `fonts.json` index, 50-font cap) + `FontFile.import(stream, store, name)` validating the sfnt magic and proving the face actually loads. `KeyboardFonts` resolves an `installed:<id>` font id through the store, so installed faces appear in the font picker beside the Google Fonts. |
| `emoji_font` | Same `FontFile.import` as `font`, flagged `emoji = true` in the store. Kept a separate type because it is chosen somewhere else entirely (`EmojiFontChoice.INSTALLED` under Emoji settings, not the key-label pickers), and because a colour emoji font on the key labels is not a choice anyone makes on purpose. There is exactly one emoji slot, which `AddonApply` offers to fill once the font has landed. |
| `sound` | **new subsystem.** Key sounds were five synthesised waveforms behind a `KeySoundStyle` enum with no import path at all. Adds a `CUSTOM` style, `SoundStore` (`filesDir/keysounds/`, `sounds.json`, 30-sound cap) and `SoundFile.import(stream, store, name)` sniffing the header for MP3, OGG or WAV; `KeySoundPlayer` loads the chosen file into its `SoundPool` instead of a synthesised buffer. |
| `sound_pack` | `SoundPackFile.import(input, store)`. Extracts the `*.wmsoundpack` ZIP, validates the `wmkeyboard-sound-pack` envelope in `pack.json`, sniffs every recording's header, rewrites the manifest onto file names it chose itself, and registers the pack in `SoundPackStore` under a `PACK` style beside `CUSTOM`. |
| `plugin` | `PluginFile.import(input, store)` into `PluginStore`, refused outright when the plugin subsystem is off. Then `setEnabled(id, false)`: a `.wmplugin` opened from a file lands enabled, one that arrived from a repository lands switched off and is offered. See §7a. |
| `vocabulary` | `VocabPacks.import(filesDir, langId, name, stream)`. Per-language pack of words to nudge toward, validated to ≥1 word, 8 MiB cap. Same `langId`-must-exist-in-`LanguageRegistry` guard as `dictionary`. |

Record the result in `installed_addons`. Uninstall reverses the local action
(`deleteCustomTheme` / `deleteCustomLayout` / delete the dict or keyword-pack file / remove snippets / delete sticker pack / etc.).

Installing puts the payload on the device and stops there. Nothing is selected, switched to or
turned on: browsing a repository and tapping the download arrow on three themes must not leave
the user wearing the third one. `AddonApply` asks instead. The types with one obvious slot
(`theme`, `icon_pack`, `emoji_font`, `sound`, `sound_pack`, `layout`, `plugin`) raise a one-question
dialog the moment the install lands. The rest have nothing to ask about: they are either live already
(`dictionary`, `emoji_keywords`, `snippets`, `espanso`, `stickers`, `vocabulary`) or bound for a picker with
several slots (`font`).

Updating is the exception: an addon that *was* the active theme, sound or layout is re-selected
under its new local id without asking, because every importer mints a fresh id and the user never
revoked the choice.

Separately, the detail page offers **Use**, which navigates to whichever settings screen owns
that type (`themes`, `layout`, `customdictionaries`, `emojikeywords`, `expander`,
`sticker_packs`, `icons`, `fonts`, `emoji`, `keypress`, `plugins`, `vocab/packs`). Layouts go to
Layout & size rather than Key layouts: an installed layout arrives switched off, and the switch
that turns it on is under Layout & size → Your layouts, while Key layouts lists only layouts that
are already on.

### Reconciliation

Nothing forces a user through the Addons screen to get rid of something. A theme deleted from
the Themes gallery, a font from the font picker, an icon pack from the Icons screen: each of
those leaves `installed.json` still claiming the addon is there. That shows as *Uninstall* for
something already gone, and later offers an *Update* for it. So `AddonReconciler.reconcile()`
runs when each addon screen appears: it asks every record's own subsystem whether its
`localRef` still resolves, and drops the ones that don't. It runs before the status
recompute, so a deleted theme reads as available again rather than installed.

## 5b. Previewing without installing

Eight types have *content* that is itself the choice: `snippets`, `dictionary`, `emoji_keywords`,
`sound`, `sound_pack`, `stickers`, `espanso` and `vocabulary`. Their detail pages offer **Preview**. It
downloads the payload to `cacheDir` (a 12 MiB ceiling, under every install cap) and reads it into
a summary: the snippets in the pack, a sample of the word list with a count, two dozen emoji
beside their keywords, a play button, up to eight play rows per half of a sound pack, the first
two dozen sticker images, a sample of the vocabulary pack's words. Nothing is installed and no
setting changes. The path touches neither the status map nor the single-install lock, so a
preview can't interfere with a download in flight.

`plugin` previews too, for the opposite reason: not to judge the content but to read what the
thing would be allowed to do before any of its code lands. That one is built from the manifest
alone and never reads the script.

`theme`, `layout`, `icon_pack`, `font` and `emoji_font` deliberately offer no preview: they are
judged by looking at the keyboard wearing them, which a panel in the settings app cannot honestly
reproduce. Those rely on `previews[]` screenshots instead.

## 6. Update detection

On the Addons screen (or manual refresh): re-fetch each repo's manifest, and for every entry
whose `"<repoId>/<addonId>"` is in `installed_addons`, semver-compare `entry.version` to the
stored version → show **Update**. Re-running install overwrites in place.

## 7. Security & privacy

Every addon type but one is pure data, and no code runs. The exception is
`plugin`, which ships Python and is covered separately below. Residual surface and
mitigations:

- **Transport:** `https` only. Size caps on manifest and every payload type (theme image cap,
  32 MiB dict cap). Optional but recommended `sha256` verification.
- **Images & Stickers:** theme backgrounds and sticker images decode through `BitmapFactory`,
  which is the parser exposed to untrusted bytes. Cap dimensions and bytes. Decode off the main
  thread. Import already nulls any local absolute `backgroundImage` path and re-extracts to
  app-private storage. Keep that.
- **Layouts:** the `send_key` / `mod` key actions are a capability, but they only inject into the
  field the user is typing in (no exfiltration, no off-device effect). Already part of the trusted
  layout format. Note it in review. No extra gate is needed for v1.
- **Privacy:** manifests are fetched only from user-added URLs. No telemetry, no phone-home.
  Previews and licence texts are fetched only when the user asks for them.
- **Licences:** `license` / `licenseText` / `licenseFile` are metadata shown on the addon's
  page so the user can see what they are installing. Nothing is enforced, and a licence file is
  fetched lazily, capped at 256 KB.
- **Deep links:** a `wmkeyboard://` link is untrusted input from a web page, so it may never
  install on arrival. It navigates to the addon's detail screen showing the repo URL, name and
  author, and the user taps Install. Adding a repository from a link needs the same explicit
  confirm, with the resolved host shown so a lookalike URL is visible before it is trusted.


### 7a. Plugins

`plugin` is the one type whose payload is code, so the sentence above stops being
true for it and a different set of guarantees takes over.

A plugin's Python runs in a sandbox with **no API for reading typed text, the text
field, the clipboard, or the network**. Those are not gated behind a permission
and not prompted for at use. They are absent. It also cannot load code at
runtime, reach Android, see other apps, or run while its panel is closed. The one thing it can declare is
local storage, which cannot leave the device because nothing in the sandbox can
send anything anywhere.

That capability set is deliberate rather than minimal-for-now. A keyboard sees
everything its user types, and third-party code that could read that *and* reach
the network is a keylogger with extra steps. No consent dialog makes that
untrue, since the user cannot audit what a script does with a permission after
granting it. Play's Device and Network Abuse policy asks the same question of
runtime-loaded interpreted code, naming Python among its examples: it "must not
allow potential violations". That is about capability, not consent.

Rules this type does not share with the others:

- **`sha256` is required**, not optional. The app refuses to install code it
  cannot verify; "unverified" is not a state a plugin gets to be in.
- **It lands switched off**, unlike a `.wmplugin` the user opened from a file.
  No type is applied on install, but for code "apply" would mean "execute", so
  this one does not even appear in the plugins panel until the user says yes.
- **The subsystem is off by default.** Nothing installs or runs until the user
  turns plugins on.
- **The preview is a capability list**, shown before Install and built from the
  manifest alone. The script is never read to work out what a plugin claims it
  can do.

Full detail: [the plugin sandbox](/plugins/security/).

## 8. Future work

- **Data-driven languages.** A downloadable dictionary or layout for a *new* language currently
  needs a compiled `LanguageDef` in `LanguageRegistry.all` (`core/script/Language.kt:54`). Making
  that registry data-driven would let repos ship whole new languages. Larger effort, tracked
  separately.
- **Curated index.** An optional list of known repos the app suggests when the user has none added.

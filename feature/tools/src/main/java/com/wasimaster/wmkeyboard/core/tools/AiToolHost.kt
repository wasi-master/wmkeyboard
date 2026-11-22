package com.wasimaster.wmkeyboard.core.tools

/**
 * What a surface lends the AI's tools that this module cannot reach on its own
 * (#698): the parts of a tool that live in the keyboard module, or that only
 * make sense where there is a screen to act on.
 *
 * Every member has a default meaning "not here", so a surface implements only
 * what it has, and a tool whose piece is missing is simply not offered.
 */
interface AiToolHost {

    /** Whether [translateOnDevice] can do anything in this build. */
    val canTranslateOnDevice: Boolean get() = false

    /**
     * [text] translated on the phone, or a failure saying why not: the pair's
     * models are not downloaded, the engine's module is missing. Blocking.
     */
    fun translateOnDevice(text: String, target: String, source: String): Result<Translation> =
        Result.failure(UnsupportedOperationException("no on-device translator here"))

    /** Whether [openApp] can launch anything from this surface. */
    val canOpenApps: Boolean get() = false

    /**
     * Opens the installed app best matching [name], and gives back its label.
     * A [NoSuchElementException] means no app matched. Blocking.
     */
    fun openApp(name: String): Result<String> =
        Result.failure(UnsupportedOperationException("no app launcher here"))

    /** Whether [sendToPc] has KDE Connect to send through. */
    val canSendToPc: Boolean get() = false

    /**
     * Sends [text] to the user's connected computer over KDE Connect, and
     * gives back the computer's name. Blocking: it waits for the link.
     */
    fun sendToPc(text: String): Result<String> =
        Result.failure(UnsupportedOperationException("no KDE Connect here"))

    /** Whether [homeControl], [homeAction] and [homeStates] have a signed-in home behind them. */
    val canControlHome: Boolean get() = false

    /**
     * Sends [request] to Home Assistant's Assist and gives back what it said.
     * Blocking: it waits for the connection and the answer.
     */
    fun homeControl(request: String): Result<String> =
        Result.failure(UnsupportedOperationException("no Home Assistant here"))

    /**
     * Runs [action] (`turn_on`, or `domain.service`) on the device [entityId]
     * names, on whichever home has it, with [data] as the service's fields.
     * Works on every kind of home, not only one with Assist. Blocking.
     */
    fun homeAction(entityId: String, action: String, data: kotlinx.serialization.json.JsonObject): Result<String> =
        Result.failure(UnsupportedOperationException("no smart home here"))

    /** The user's entities matching [query], one state per line; everything on or open when [query] is null. Blocking. */
    fun homeStates(query: String?): Result<String> =
        Result.failure(UnsupportedOperationException("no Home Assistant here"))

    /**
     * Whether [searchClipboard], [notesByMeaning] and [snippetsByMeaning] can
     * answer: search by meaning is on and its model is on the phone. Without
     * it `search_clipboard` is not offered, and `search_notes` and
     * `search_snippets` match words only.
     */
    val canSearchByMeaning: Boolean get() = false

    /**
     * Clipboard history that means what [query] does, best first. Passwords
     * and one-time codes are never among it. Blocking: the query is embedded
     * by the model's own process.
     */
    fun searchClipboard(query: String): Result<List<AiMeaningHit>> =
        Result.failure(UnsupportedOperationException("no search by meaning here"))

    /** Notes that mean what [query] does, best first; never a locked or deleted one. Blocking. */
    fun notesByMeaning(query: String): Result<List<AiMeaningHit>> =
        Result.failure(UnsupportedOperationException("no search by meaning here"))

    /** Snippets that mean what [query] does, best first; never a deleted one. Blocking. */
    fun snippetsByMeaning(query: String): Result<List<AiMeaningHit>> =
        Result.failure(UnsupportedOperationException("no search by meaning here"))

    /** Whether [renameChat] has a chat to rename: only a chat's host does (#882). */
    val canRenameChat: Boolean get() = false

    /**
     * Gives the chat this run answers in the name [title], once the user has
     * accepted it, and gives back the name it got.
     */
    fun renameChat(title: String): Result<String> =
        Result.failure(UnsupportedOperationException("no chat to rename here"))

    /** How many pictures the user has sent in this conversation (#698). */
    val imageCount: Int get() = 0

    /** Whether the model reads pictures itself, which makes [readImageText] needless. */
    val modelSeesImages: Boolean get() = false

    /** Picture [number], counted from 1, as JPEG bytes; null when it is gone. */
    fun imageJpeg(number: Int): ByteArray? = null

    /** Whether [readImageText] has a reader in this build and setup. */
    val canReadImageText: Boolean get() = false

    /** The text in picture [number], counted from 1; empty when it has none. Blocking. */
    fun readImageText(number: Int): Result<String> =
        Result.failure(UnsupportedOperationException("no picture reader here"))

    /** Whether [describeImage] has a model that sees to ask: the "Describe pictures with" setting. */
    val canDescribeImage: Boolean get() = false

    /**
     * Picture [number], counted from 1, described by the model that sees,
     * with [question] (blank for none) as what the user wants to know.
     * Already says which model looked. Blocking.
     */
    fun describeImage(number: Int, question: String): Result<String> =
        Result.failure(UnsupportedOperationException("no picture describer here"))
}

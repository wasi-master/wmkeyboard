package com.wasimaster.wmkeyboard.core.script

/**
 * One Fancy Text style: a per-letter map onto a Unicode "font"
 * (mathematical alphanumerics, enclosed letters, combining marks).
 *
 * [lower]/[upper] are keyed by the plain ASCII letter; values are
 * strings because a styled letter can be an astral pair (\uD835…)
 * or a base letter plus a combining mark. A style with no form for
 * a letter maps it to itself (superscript q), and small caps has no
 * capitals at all, so its two maps carry the same glyphs.
 */
data class FancyStyle(
    val id: String,
    /** Plain-text name, for TalkBack and anywhere astral glyphs can't go. */
    val name: String,
    /** The name written in the style itself — the WYSIWYG chip label. */
    val sample: String,
    val lower: Map<Char, String>,
    val upper: Map<Char, String>,
)

/**
 * The Fancy Text styles: [NORMAL_ID] first, then the 22 Unicode styles in
 * the order the old per-style layouts shipped. The Unicode ones were
 * generated from those layouts' key labels, so the glyphs are exactly what
 * each style always typed. Do not retype by hand: several values are
 * surrogate pairs or letter+combining-mark sequences that editors love to
 * normalise.
 */
object FancyStyles {

    /**
     * The plain style: every letter maps to itself. It is a real entry
     * rather than a null so the strip can offer "back to normal" as a chip
     * without leaving the fancy keyboard, and so every reader of the table
     * keeps treating a style as "a transform", not "maybe a transform".
     */
    const val NORMAL_ID = "normal"

    const val DEFAULT_ID = "bold"

    /**
     * The language id the fancy layout carries. Everything that has to ask
     * "is this the fancy keyboard?" reads it from here rather than repeating
     * the string, since a style only applies on that one language.
     */
    const val LANG_ID = "fancy"

    val all: List<FancyStyle> = listOf(
        style(
            NORMAL_ID, "Normal", "Normal",
            ('a'..'z').map { it.toString() },
            ('A'..'Z').map { it.toString() },
        ),
        style(
            "bold", "Bold", "𝐁𝐨𝐥𝐝",
            listOf(
                "𝐚", "𝐛", "𝐜", "𝐝", "𝐞", "𝐟", "𝐠", "𝐡", "𝐢",
                "𝐣", "𝐤", "𝐥", "𝐦", "𝐧", "𝐨", "𝐩", "𝐪", "𝐫",
                "𝐬", "𝐭", "𝐮", "𝐯", "𝐰", "𝐱", "𝐲", "𝐳",
            ),
            listOf(
                "𝐀", "𝐁", "𝐂", "𝐃", "𝐄", "𝐅", "𝐆", "𝐇", "𝐈",
                "𝐉", "𝐊", "𝐋", "𝐌", "𝐍", "𝐎", "𝐏", "𝐐", "𝐑",
                "𝐒", "𝐓", "𝐔", "𝐕", "𝐖", "𝐗", "𝐘", "𝐙",
            ),
        ),
        style(
            "italic", "Italic", "𝐼𝑡𝑎𝑙𝑖𝑐",
            listOf(
                "𝑎", "𝑏", "𝑐", "𝑑", "𝑒", "𝑓", "𝑔", "ℎ", "𝑖",
                "𝑗", "𝑘", "𝑙", "𝑚", "𝑛", "𝑜", "𝑝", "𝑞", "𝑟",
                "𝑠", "𝑡", "𝑢", "𝑣", "𝑤", "𝑥", "𝑦", "𝑧",
            ),
            listOf(
                "𝐴", "𝐵", "𝐶", "𝐷", "𝐸", "𝐹", "𝐺", "𝐻", "𝐼",
                "𝐽", "𝐾", "𝐿", "𝑀", "𝑁", "𝑂", "𝑃", "𝑄", "𝑅",
                "𝑆", "𝑇", "𝑈", "𝑉", "𝑊", "𝑋", "𝑌", "𝑍",
            ),
        ),
        style(
            "bold_italic", "Bold Italic", "𝑩𝒐𝒍𝒅 𝑰𝒕𝒂𝒍𝒊𝒄",
            listOf(
                "𝒂", "𝒃", "𝒄", "𝒅", "𝒆", "𝒇", "𝒈", "𝒉", "𝒊",
                "𝒋", "𝒌", "𝒍", "𝒎", "𝒏", "𝒐", "𝒑", "𝒒", "𝒓",
                "𝒔", "𝒕", "𝒖", "𝒗", "𝒘", "𝒙", "𝒚", "𝒛",
            ),
            listOf(
                "𝑨", "𝑩", "𝑪", "𝑫", "𝑬", "𝑭", "𝑮", "𝑯", "𝑰",
                "𝑱", "𝑲", "𝑳", "𝑴", "𝑵", "𝑶", "𝑷", "𝑸", "𝑹",
                "𝑺", "𝑻", "𝑼", "𝑽", "𝑾", "𝑿", "𝒀", "𝒁",
            ),
        ),
        style(
            "script", "Script", "𝒮𝒸𝓇𝒾𝓅𝓉",
            listOf(
                "𝒶", "𝒷", "𝒸", "𝒹", "ℯ", "𝒻", "ℊ", "𝒽", "𝒾",
                "𝒿", "𝓀", "𝓁", "𝓂", "𝓃", "ℴ", "𝓅", "𝓆", "𝓇",
                "𝓈", "𝓉", "𝓊", "𝓋", "𝓌", "𝓍", "𝓎", "𝓏",
            ),
            listOf(
                "𝒜", "ℬ", "𝒞", "𝒟", "ℰ", "ℱ", "𝒢", "ℋ", "ℐ",
                "𝒥", "𝒦", "ℒ", "ℳ", "𝒩", "𝒪", "𝒫", "𝒬", "ℛ",
                "𝒮", "𝒯", "𝒰", "𝒱", "𝒲", "𝒳", "𝒴", "𝒵",
            ),
        ),
        style(
            "bold_script", "Bold Script", "𝓑𝓸𝓵𝓭 𝓢𝓬𝓻𝓲𝓹𝓽",
            listOf(
                "𝓪", "𝓫", "𝓬", "𝓭", "𝓮", "𝓯", "𝓰", "𝓱", "𝓲",
                "𝓳", "𝓴", "𝓵", "𝓶", "𝓷", "𝓸", "𝓹", "𝓺", "𝓻",
                "𝓼", "𝓽", "𝓾", "𝓿", "𝔀", "𝔁", "𝔂", "𝔃",
            ),
            listOf(
                "𝓐", "𝓑", "𝓒", "𝓓", "𝓔", "𝓕", "𝓖", "𝓗", "𝓘",
                "𝓙", "𝓚", "𝓛", "𝓜", "𝓝", "𝓞", "𝓟", "𝓠", "𝓡",
                "𝓢", "𝓣", "𝓤", "𝓥", "𝓦", "𝓧", "𝓨", "𝓩",
            ),
        ),
        style(
            "fraktur", "Fraktur", "𝔉𝔯𝔞𝔨𝔱𝔲𝔯",
            listOf(
                "𝔞", "𝔟", "𝔠", "𝔡", "𝔢", "𝔣", "𝔤", "𝔥", "𝔦",
                "𝔧", "𝔨", "𝔩", "𝔪", "𝔫", "𝔬", "𝔭", "𝔮", "𝔯",
                "𝔰", "𝔱", "𝔲", "𝔳", "𝔴", "𝔵", "𝔶", "𝔷",
            ),
            listOf(
                "𝔄", "𝔅", "ℭ", "𝔇", "𝔈", "𝔉", "𝔊", "ℌ", "ℑ",
                "𝔍", "𝔎", "𝔏", "𝔐", "𝔑", "𝔒", "𝔓", "𝔔", "ℜ",
                "𝔖", "𝔗", "𝔘", "𝔙", "𝔚", "𝔛", "𝔜", "ℨ",
            ),
        ),
        style(
            "bold_fraktur", "Bold Fraktur", "𝕭𝖔𝖑𝖉 𝕱𝖗𝖆𝖐𝖙𝖚𝖗",
            listOf(
                "𝖆", "𝖇", "𝖈", "𝖉", "𝖊", "𝖋", "𝖌", "𝖍", "𝖎",
                "𝖏", "𝖐", "𝖑", "𝖒", "𝖓", "𝖔", "𝖕", "𝖖", "𝖗",
                "𝖘", "𝖙", "𝖚", "𝖛", "𝖜", "𝖝", "𝖞", "𝖟",
            ),
            listOf(
                "𝕬", "𝕭", "𝕮", "𝕯", "𝕰", "𝕱", "𝕲", "𝕳", "𝕴",
                "𝕵", "𝕶", "𝕷", "𝕸", "𝕹", "𝕺", "𝕻", "𝕼", "𝕽",
                "𝕾", "𝕿", "𝖀", "𝖁", "𝖂", "𝖃", "𝖄", "𝖅",
            ),
        ),
        style(
            "double_struck", "Double-struck", "𝔻𝕠𝕦𝕓𝕝𝕖-𝕤𝕥𝕣𝕦𝕔𝕜",
            listOf(
                "𝕒", "𝕓", "𝕔", "𝕕", "𝕖", "𝕗", "𝕘", "𝕙", "𝕚",
                "𝕛", "𝕜", "𝕝", "𝕞", "𝕟", "𝕠", "𝕡", "𝕢", "𝕣",
                "𝕤", "𝕥", "𝕦", "𝕧", "𝕨", "𝕩", "𝕪", "𝕫",
            ),
            listOf(
                "𝔸", "𝔹", "ℂ", "𝔻", "𝔼", "𝔽", "𝔾", "ℍ", "𝕀",
                "𝕁", "𝕂", "𝕃", "𝕄", "ℕ", "𝕆", "ℙ", "ℚ", "ℝ",
                "𝕊", "𝕋", "𝕌", "𝕍", "𝕎", "𝕏", "𝕐", "ℤ",
            ),
        ),
        style(
            "sans", "Sans-serif", "𝖲𝖺𝗇𝗌-𝗌𝖾𝗋𝗂𝖿",
            listOf(
                "𝖺", "𝖻", "𝖼", "𝖽", "𝖾", "𝖿", "𝗀", "𝗁", "𝗂",
                "𝗃", "𝗄", "𝗅", "𝗆", "𝗇", "𝗈", "𝗉", "𝗊", "𝗋",
                "𝗌", "𝗍", "𝗎", "𝗏", "𝗐", "𝗑", "𝗒", "𝗓",
            ),
            listOf(
                "𝖠", "𝖡", "𝖢", "𝖣", "𝖤", "𝖥", "𝖦", "𝖧", "𝖨",
                "𝖩", "𝖪", "𝖫", "𝖬", "𝖭", "𝖮", "𝖯", "𝖰", "𝖱",
                "𝖲", "𝖳", "𝖴", "𝖵", "𝖶", "𝖷", "𝖸", "𝖹",
            ),
        ),
        style(
            "sans_bold", "Sans Bold", "𝗦𝗮𝗻𝘀 𝗕𝗼𝗹𝗱",
            listOf(
                "𝗮", "𝗯", "𝗰", "𝗱", "𝗲", "𝗳", "𝗴", "𝗵", "𝗶",
                "𝗷", "𝗸", "𝗹", "𝗺", "𝗻", "𝗼", "𝗽", "𝗾", "𝗿",
                "𝘀", "𝘁", "𝘂", "𝘃", "𝘄", "𝘅", "𝘆", "𝘇",
            ),
            listOf(
                "𝗔", "𝗕", "𝗖", "𝗗", "𝗘", "𝗙", "𝗚", "𝗛", "𝗜",
                "𝗝", "𝗞", "𝗟", "𝗠", "𝗡", "𝗢", "𝗣", "𝗤", "𝗥",
                "𝗦", "𝗧", "𝗨", "𝗩", "𝗪", "𝗫", "𝗬", "𝗭",
            ),
        ),
        style(
            "sans_italic", "Sans Italic", "𝘚𝘢𝘯𝘴 𝘐𝘵𝘢𝘭𝘪𝘤",
            listOf(
                "𝘢", "𝘣", "𝘤", "𝘥", "𝘦", "𝘧", "𝘨", "𝘩", "𝘪",
                "𝘫", "𝘬", "𝘭", "𝘮", "𝘯", "𝘰", "𝘱", "𝘲", "𝘳",
                "𝘴", "𝘵", "𝘶", "𝘷", "𝘸", "𝘹", "𝘺", "𝘻",
            ),
            listOf(
                "𝘈", "𝘉", "𝘊", "𝘋", "𝘌", "𝘍", "𝘎", "𝘏", "𝘐",
                "𝘑", "𝘒", "𝘓", "𝘔", "𝘕", "𝘖", "𝘗", "𝘘", "𝘙",
                "𝘚", "𝘛", "𝘜", "𝘝", "𝘞", "𝘟", "𝘠", "𝘡",
            ),
        ),
        style(
            "sans_bold_italic", "Sans Bold Italic", "𝙎𝙖𝙣𝙨 𝘽𝙤𝙡𝙙 𝙄𝙩𝙖𝙡𝙞𝙘",
            listOf(
                "𝙖", "𝙗", "𝙘", "𝙙", "𝙚", "𝙛", "𝙜", "𝙝", "𝙞",
                "𝙟", "𝙠", "𝙡", "𝙢", "𝙣", "𝙤", "𝙥", "𝙦", "𝙧",
                "𝙨", "𝙩", "𝙪", "𝙫", "𝙬", "𝙭", "𝙮", "𝙯",
            ),
            listOf(
                "𝘼", "𝘽", "𝘾", "𝘿", "𝙀", "𝙁", "𝙂", "𝙃", "𝙄",
                "𝙅", "𝙆", "𝙇", "𝙈", "𝙉", "𝙊", "𝙋", "𝙌", "𝙍",
                "𝙎", "𝙏", "𝙐", "𝙑", "𝙒", "𝙓", "𝙔", "𝙕",
            ),
        ),
        style(
            "monospace", "Monospace", "𝙼𝚘𝚗𝚘𝚜𝚙𝚊𝚌𝚎",
            listOf(
                "𝚊", "𝚋", "𝚌", "𝚍", "𝚎", "𝚏", "𝚐", "𝚑", "𝚒",
                "𝚓", "𝚔", "𝚕", "𝚖", "𝚗", "𝚘", "𝚙", "𝚚", "𝚛",
                "𝚜", "𝚝", "𝚞", "𝚟", "𝚠", "𝚡", "𝚢", "𝚣",
            ),
            listOf(
                "𝙰", "𝙱", "𝙲", "𝙳", "𝙴", "𝙵", "𝙶", "𝙷", "𝙸",
                "𝙹", "𝙺", "𝙻", "𝙼", "𝙽", "𝙾", "𝙿", "𝚀", "𝚁",
                "𝚂", "𝚃", "𝚄", "𝚅", "𝚆", "𝚇", "𝚈", "𝚉",
            ),
        ),
        style(
            "fullwidth", "Fullwidth", "Ｆｕｌｌｗｉｄｔｈ",
            listOf(
                "ａ", "ｂ", "ｃ", "ｄ", "ｅ", "ｆ", "ｇ", "ｈ", "ｉ",
                "ｊ", "ｋ", "ｌ", "ｍ", "ｎ", "ｏ", "ｐ", "ｑ", "ｒ",
                "ｓ", "ｔ", "ｕ", "ｖ", "ｗ", "ｘ", "ｙ", "ｚ",
            ),
            listOf(
                "Ａ", "Ｂ", "Ｃ", "Ｄ", "Ｅ", "Ｆ", "Ｇ", "Ｈ", "Ｉ",
                "Ｊ", "Ｋ", "Ｌ", "Ｍ", "Ｎ", "Ｏ", "Ｐ", "Ｑ", "Ｒ",
                "Ｓ", "Ｔ", "Ｕ", "Ｖ", "Ｗ", "Ｘ", "Ｙ", "Ｚ",
            ),
        ),
        style(
            "circled", "Circled", "Ⓒⓘⓡⓒⓛⓔⓓ",
            listOf(
                "ⓐ", "ⓑ", "ⓒ", "ⓓ", "ⓔ", "ⓕ", "ⓖ", "ⓗ", "ⓘ",
                "ⓙ", "ⓚ", "ⓛ", "ⓜ", "ⓝ", "ⓞ", "ⓟ", "ⓠ", "ⓡ",
                "ⓢ", "ⓣ", "ⓤ", "ⓥ", "ⓦ", "ⓧ", "ⓨ", "ⓩ",
            ),
            listOf(
                "Ⓐ", "Ⓑ", "Ⓒ", "Ⓓ", "Ⓔ", "Ⓕ", "Ⓖ", "Ⓗ", "Ⓘ",
                "Ⓙ", "Ⓚ", "Ⓛ", "Ⓜ", "Ⓝ", "Ⓞ", "Ⓟ", "Ⓠ", "Ⓡ",
                "Ⓢ", "Ⓣ", "Ⓤ", "Ⓥ", "Ⓦ", "Ⓧ", "Ⓨ", "Ⓩ",
            ),
        ),
        style(
            "circled_filled", "Circled (Filled)", "🅒🅘🅡🅒🅛🅔🅓 (🅕🅘🅛🅛🅔🅓)",
            listOf(
                "🅐", "🅑", "🅒", "🅓", "🅔", "🅕", "🅖", "🅗", "🅘",
                "🅙", "🅚", "🅛", "🅜", "🅝", "🅞", "🅟", "🅠", "🅡",
                "🅢", "🅣", "🅤", "🅥", "🅦", "🅧", "🅨", "🅩",
            ),
            listOf(
                "🅐", "🅑", "🅒", "🅓", "🅔", "🅕", "🅖", "🅗", "🅘",
                "🅙", "🅚", "🅛", "🅜", "🅝", "🅞", "🅟", "🅠", "🅡",
                "🅢", "🅣", "🅤", "🅥", "🅦", "🅧", "🅨", "🅩",
            ),
        ),
        style(
            "squared", "Squared", "🅂🅀🅄🄰🅁🄴🄳",
            listOf(
                "🄰", "🄱", "🄲", "🄳", "🄴", "🄵", "🄶", "🄷", "🄸",
                "🄹", "🄺", "🄻", "🄼", "🄽", "🄾", "🄿", "🅀", "🅁",
                "🅂", "🅃", "🅄", "🅅", "🅆", "🅇", "🅈", "🅉",
            ),
            listOf(
                "🄰", "🄱", "🄲", "🄳", "🄴", "🄵", "🄶", "🄷", "🄸",
                "🄹", "🄺", "🄻", "🄼", "🄽", "🄾", "🄿", "🅀", "🅁",
                "🅂", "🅃", "🅄", "🅅", "🅆", "🅇", "🅈", "🅉",
            ),
        ),
        style(
            "squared_filled", "Squared (Filled)", "🆂🆀🆄🅰🆁🅴🅳 (🅵🅸🅻🅻🅴🅳)",
            listOf(
                "🅰", "🅱", "🅲", "🅳", "🅴", "🅵", "🅶", "🅷", "🅸",
                "🅹", "🅺", "🅻", "🅼", "🅽", "🅾", "🅿", "🆀", "🆁",
                "🆂", "🆃", "🆄", "🆅", "🆆", "🆇", "🆈", "🆉",
            ),
            listOf(
                "🅰", "🅱", "🅲", "🅳", "🅴", "🅵", "🅶", "🅷", "🅸",
                "🅹", "🅺", "🅻", "🅼", "🅽", "🅾", "🅿", "🆀", "🆁",
                "🆂", "🆃", "🆄", "🆅", "🆆", "🆇", "🆈", "🆉",
            ),
        ),
        style(
            "small_caps", "Small Caps", "ꜱᴍᴀʟʟ ᴄᴀᴘꜱ",
            listOf(
                "ᴀ", "ʙ", "ᴄ", "ᴅ", "ᴇ", "ꜰ", "ɢ", "ʜ", "ɪ",
                "ᴊ", "ᴋ", "ʟ", "ᴍ", "ɴ", "ᴏ", "ᴘ", "ꞯ", "ʀ",
                "ꜱ", "ᴛ", "ᴜ", "ᴠ", "ᴡ", "x", "ʏ", "ᴢ",
            ),
            listOf(
                "ᴀ", "ʙ", "ᴄ", "ᴅ", "ᴇ", "ꜰ", "ɢ", "ʜ", "ɪ",
                "ᴊ", "ᴋ", "ʟ", "ᴍ", "ɴ", "ᴏ", "ᴘ", "ꞯ", "ʀ",
                "ꜱ", "ᴛ", "ᴜ", "ᴠ", "ᴡ", "x", "ʏ", "ᴢ",
            ),
        ),
        style(
            "superscript", "Superscript", "ˢᵘᵖᵉʳˢᶜʳⁱᵖᵗ",
            listOf(
                "ᵃ", "ᵇ", "ᶜ", "ᵈ", "ᵉ", "ᶠ", "ᵍ", "ʰ", "ⁱ",
                "ʲ", "ᵏ", "ˡ", "ᵐ", "ⁿ", "ᵒ", "ᵖ", "q", "ʳ",
                "ˢ", "ᵗ", "ᵘ", "ᵛ", "ʷ", "ˣ", "ʸ", "ᶻ",
            ),
            listOf(
                "ᵃ", "ᵇ", "ᶜ", "ᵈ", "ᵉ", "ᶠ", "ᵍ", "ʰ", "ⁱ",
                "ʲ", "ᵏ", "ˡ", "ᵐ", "ⁿ", "ᵒ", "ᵖ", "q", "ʳ",
                "ˢ", "ᵗ", "ᵘ", "ᵛ", "ʷ", "ˣ", "ʸ", "ᶻ",
            ),
        ),
        style(
            "strikethrough", "Strikethrough", "S̶t̶r̶i̶k̶e̶t̶h̶r̶o̶u̶g̶h̶",
            listOf(
                "a̶", "b̶", "c̶", "d̶", "e̶", "f̶", "g̶", "h̶", "i̶",
                "j̶", "k̶", "l̶", "m̶", "n̶", "o̶", "p̶", "q̶", "r̶",
                "s̶", "t̶", "u̶", "v̶", "w̶", "x̶", "y̶", "z̶",
            ),
            listOf(
                "A̶", "B̶", "C̶", "D̶", "E̶", "F̶", "G̶", "H̶", "I̶",
                "J̶", "K̶", "L̶", "M̶", "N̶", "O̶", "P̶", "Q̶", "R̶",
                "S̶", "T̶", "U̶", "V̶", "W̶", "X̶", "Y̶", "Z̶",
            ),
        ),
        style(
            "underline", "Underline", "U̲n̲d̲e̲r̲l̲i̲n̲e̲",
            listOf(
                "a̲", "b̲", "c̲", "d̲", "e̲", "f̲", "g̲", "h̲", "i̲",
                "j̲", "k̲", "l̲", "m̲", "n̲", "o̲", "p̲", "q̲", "r̲",
                "s̲", "t̲", "u̲", "v̲", "w̲", "x̲", "y̲", "z̲",
            ),
            listOf(
                "A̲", "B̲", "C̲", "D̲", "E̲", "F̲", "G̲", "H̲", "I̲",
                "J̲", "K̲", "L̲", "M̲", "N̲", "O̲", "P̲", "Q̲", "R̲",
                "S̲", "T̲", "U̲", "V̲", "W̲", "X̲", "Y̲", "Z̲",
            ),
        ),
    )

    private val index = all.associateBy { it.id }

    fun byId(id: String): FancyStyle? = index[id]

    /**
     * [text] with every mapped letter swapped for its styled form;
     * everything else (digits, punctuation, other scripts) passes
     * through untouched.
     */
    fun transform(text: String, style: FancyStyle): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length * 2)
        for (ch in text) {
            out.append(style.lower[ch] ?: style.upper[ch] ?: ch)
        }
        return out.toString()
    }

    /** The 26 letters in a-z order, zipped into the two maps. */
    private fun style(
        id: String,
        name: String,
        sample: String,
        lower: List<String>,
        upper: List<String>,
    ): FancyStyle = FancyStyle(
        id = id,
        name = name,
        sample = sample,
        lower = ('a'..'z').zip(lower).toMap(),
        upper = ('A'..'Z').zip(upper).toMap(),
    )
}

package com.wasimaster.wmkeyboard.core.script

/**
 * The languages only Gboard's keyboards type. Generated; do not hand-edit.
 *
 * Produced by tools/gboard-layouts/generate.py from a Gboard APK. Every entry is
 * a language Gboard ships a keyboard for and nothing else here listed, with the
 * layout that keyboard became. The names are Gboard's own identifiers, spelled
 * out, so they are English names rather than endonyms; [KeymanLanguages] has the
 * same gap for the same reason.
 *
 * Kept apart from [LanguageRegistry]'s hand-written list for the reasons that
 * file gives: a generated file and a hand-maintained one should not share a path,
 * and one big list in one initializer runs into the JVM's method size limit.
 */
object GboardLanguages {
    private val chunk0: List<LanguageDef> by lazy { listOf(
        LanguageDef(
            id = "abr",
            displayName = "Abron",
            englishName = "Abron",
            script = ScriptId.LATIN,
            localeTag = "abr",
            layoutIds = listOf("asset_gb_abr_abron"),
        ),
        LanguageDef(
            id = "abs",
            displayName = "Ambonese Malay",
            englishName = "Ambonese Malay",
            script = ScriptId.LATIN,
            localeTag = "abs",
            layoutIds = listOf("asset_gb_abs_qwerty"),
        ),
        LanguageDef(
            id = "acf",
            displayName = "Saint Lucian Creole French",
            englishName = "Saint Lucian Creole French",
            script = ScriptId.LATIN,
            localeTag = "acf",
            layoutIds = listOf("asset_gb_acf_qwerty"),
        ),
        LanguageDef(
            id = "ach",
            displayName = "Acholi",
            englishName = "Acholi",
            script = ScriptId.LATIN,
            localeTag = "ach",
            layoutIds = listOf("asset_gb_ach_qwerty"),
        ),
        LanguageDef(
            id = "ada",
            displayName = "Dangme",
            englishName = "Dangme",
            script = ScriptId.LATIN,
            localeTag = "ada",
            layoutIds = listOf("asset_gb_ada_dangme"),
        ),
        LanguageDef(
            id = "adx",
            displayName = "Amdo (Tibetan)",
            englishName = "Amdo (Tibetan)",
            script = ScriptId.TIBETAN,
            localeTag = "adx",
            layoutIds = listOf("asset_gb_adx_amdo_tibetan"),
        ),
        LanguageDef(
            id = "ahr",
            displayName = "Ahirani",
            englishName = "Ahirani",
            script = ScriptId.DEVANAGARI,
            localeTag = "ahr",
            layoutIds = listOf("asset_gb_ahr_ahirani"),
        ),
        LanguageDef(
            id = "ajg",
            displayName = "Ajagbe",
            englishName = "Ajagbe",
            script = ScriptId.LATIN,
            localeTag = "ajg",
            layoutIds = listOf("asset_gb_ajg_ajagbe"),
        ),
        LanguageDef(
            id = "als",
            displayName = "Tosk Albanian",
            englishName = "Tosk Albanian",
            script = ScriptId.LATIN,
            localeTag = "als",
            layoutIds = listOf("asset_gb_als_albanian"),
        ),
        LanguageDef(
            id = "alz",
            displayName = "Alur",
            englishName = "Alur",
            script = ScriptId.LATIN,
            localeTag = "alz",
            layoutIds = listOf("asset_gb_alz_qwerty"),
        ),
        LanguageDef(
            id = "anw",
            displayName = "Anaang",
            englishName = "Anaang",
            script = ScriptId.LATIN,
            localeTag = "anw",
            layoutIds = listOf("asset_gb_anw_qwerty"),
        ),
        LanguageDef(
            id = "as-Latn",
            displayName = "Assamese (Latin)",
            englishName = "Assamese (Latin)",
            script = ScriptId.LATIN,
            localeTag = "as-Latn",
            layoutIds = listOf("asset_gb_as_latn_qwerty"),
        ),
        LanguageDef(
            id = "az-Arab",
            displayName = "Azerbaijani Iran (Arabic)",
            englishName = "Azerbaijani Iran (Arabic)",
            script = ScriptId.ARABIC,
            localeTag = "az-Arab",
            layoutIds = listOf("asset_gb_az_arab_azerbaijani_iran"),
        ),
        LanguageDef(
            id = "bah",
            displayName = "Bahamian Creole",
            englishName = "Bahamian Creole",
            script = ScriptId.LATIN,
            localeTag = "bah",
            layoutIds = listOf("asset_gb_bah_bahamian_creole"),
        ),
        LanguageDef(
            id = "bas",
            displayName = "Basaa",
            englishName = "Basaa",
            script = ScriptId.LATIN,
            localeTag = "bas",
            layoutIds = listOf("asset_gb_bas_basaa"),
        ),
        LanguageDef(
            id = "bbj",
            displayName = "Ghomala",
            englishName = "Ghomala",
            script = ScriptId.LATIN,
            localeTag = "bbj",
            layoutIds = listOf("asset_gb_bbj_ghomala"),
        ),
        LanguageDef(
            id = "bcc",
            displayName = "Southern Balochi",
            englishName = "Southern Balochi",
            script = ScriptId.ARABIC,
            localeTag = "bcc",
            layoutIds = listOf("asset_gb_bcc_balochi"),
        ),
        LanguageDef(
            id = "bci",
            displayName = "Baoule",
            englishName = "Baoule",
            script = ScriptId.LATIN,
            localeTag = "bci",
            layoutIds = listOf("asset_gb_bci_baoule"),
        ),
        LanguageDef(
            id = "bcq",
            displayName = "Bench Ethiopia",
            englishName = "Bench Ethiopia",
            script = ScriptId.LATIN,
            localeTag = "bcq",
            layoutIds = listOf(
                "asset_gb_bcq_bench",
                "asset_gb_bcq_bench_2",
            ),
        ),
        LanguageDef(
            id = "ber-Tfng",
            displayName = "Tamazight Tifinagh (Tifinagh)",
            englishName = "Tamazight Tifinagh (Tifinagh)",
            script = ScriptId.TIFINAGH,
            localeTag = "ber-Tfng",
            layoutIds = listOf(
                "asset_gb_ber_tfng_tamazight_tifinagh",
                "asset_gb_ber_tfng_tamazight_tifinagh_amazigh",
            ),
        ),
        LanguageDef(
            id = "bfy",
            displayName = "Bagheli",
            englishName = "Bagheli",
            script = ScriptId.DEVANAGARI,
            localeTag = "bfy",
            layoutIds = listOf("asset_gb_bfy_bagheli"),
        ),
        LanguageDef(
            id = "bgc",
            displayName = "Haryanvi",
            englishName = "Haryanvi",
            script = ScriptId.DEVANAGARI,
            localeTag = "bgc",
            layoutIds = listOf("asset_gb_bgc_haryanvi"),
        ),
        LanguageDef(
            id = "bgn",
            displayName = "Western Balochi",
            englishName = "Western Balochi",
            script = ScriptId.ARABIC,
            localeTag = "bgn",
            layoutIds = listOf("asset_gb_bgn_balochi"),
        ),
        LanguageDef(
            id = "bgp",
            displayName = "Eastern Balochi",
            englishName = "Eastern Balochi",
            script = ScriptId.ARABIC,
            localeTag = "bgp",
            layoutIds = listOf("asset_gb_bgp_balochi"),
        ),
        LanguageDef(
            id = "bgq",
            displayName = "Bagri",
            englishName = "Bagri",
            script = ScriptId.ARABIC,
            localeTag = "bgq",
            layoutIds = listOf(
                "asset_gb_bgq_bagri_arabic",
                "asset_gb_bgq_bagri",
            ),
        ),
        LanguageDef(
            id = "bgx",
            displayName = "Balkan Gagauz Turkish",
            englishName = "Balkan Gagauz Turkish",
            script = ScriptId.LATIN,
            localeTag = "bgx",
            layoutIds = listOf(
                "asset_gb_bgx_turkish_q",
                "asset_gb_bgx_turkish_f",
            ),
        ),
        LanguageDef(
            id = "bgz",
            displayName = "Banggai",
            englishName = "Banggai",
            script = ScriptId.LATIN,
            localeTag = "bgz",
            layoutIds = listOf("asset_gb_bgz_qwerty"),
        ),
        LanguageDef(
            id = "bhb",
            displayName = "Bhili (Devanagari)",
            englishName = "Bhili (Devanagari)",
            script = ScriptId.DEVANAGARI,
            localeTag = "bhb",
            layoutIds = listOf(
                "asset_gb_bhb_bhili",
                "asset_gb_bhb_bhili_2",
            ),
        ),
        LanguageDef(
            id = "bjj",
            displayName = "Kannauji",
            englishName = "Kannauji",
            script = ScriptId.DEVANAGARI,
            localeTag = "bjj",
            layoutIds = listOf("asset_gb_bjj_kannauji"),
        ),
        LanguageDef(
            id = "bku",
            displayName = "Buhid",
            englishName = "Buhid",
            script = ScriptId.BUHID,
            localeTag = "bku",
            layoutIds = listOf("asset_gb_bku_buhid"),
        ),
        LanguageDef(
            id = "bmm",
            displayName = "Northern Betsimisaraka Malagasy",
            englishName = "Northern Betsimisaraka Malagasy",
            script = ScriptId.LATIN,
            localeTag = "bmm",
            layoutIds = listOf("asset_gb_bmm_qwerty"),
        ),
        LanguageDef(
            id = "bn-Latn",
            displayName = "Bengali (Latin)",
            englishName = "Bengali (Latin)",
            script = ScriptId.LATIN,
            localeTag = "bn-Latn",
            layoutIds = listOf("asset_gb_bn_latn_qwerty"),
        ),
        LanguageDef(
            id = "bns",
            displayName = "Bundeli",
            englishName = "Bundeli",
            script = ScriptId.DEVANAGARI,
            localeTag = "bns",
            layoutIds = listOf("asset_gb_bns_bundeli_dynamic"),
        ),
        LanguageDef(
            id = "bpr",
            displayName = "Koronadal Blaan",
            englishName = "Koronadal Blaan",
            script = ScriptId.LATIN,
            localeTag = "bpr",
            layoutIds = listOf("asset_gb_bpr_qwerty"),
        ),
        LanguageDef(
            id = "bqi",
            displayName = "Bakhtiari",
            englishName = "Bakhtiari",
            script = ScriptId.ARABIC,
            localeTag = "bqi",
            layoutIds = listOf("asset_gb_bqi_bakhtiari"),
        ),
        LanguageDef(
            id = "brx-Beng",
            displayName = "Bodo (Bengali)",
            englishName = "Bodo (Bengali)",
            script = ScriptId.BENGALI,
            localeTag = "brx-Beng",
            layoutIds = listOf("asset_gb_brx_beng_bodo_bengali_dynamic"),
        ),
        LanguageDef(
            id = "brx-Latn",
            displayName = "Bodo (Latin)",
            englishName = "Bodo (Latin)",
            script = ScriptId.LATIN,
            localeTag = "brx-Latn",
            layoutIds = listOf("asset_gb_brx_latn_qwerty"),
        ),
        LanguageDef(
            id = "bss",
            displayName = "Akoose",
            englishName = "Akoose",
            script = ScriptId.LATIN,
            localeTag = "bss",
            layoutIds = listOf("asset_gb_bss_akoose"),
        ),
        LanguageDef(
            id = "btm-Batk",
            displayName = "Batak Mandailing (Batak)",
            englishName = "Batak Mandailing (Batak)",
            script = ScriptId.BATAK,
            localeTag = "btm-Batk",
            layoutIds = listOf("asset_gb_btm_batk_batak_mandailing"),
        ),
        LanguageDef(
            id = "bto",
            displayName = "Rinconada Bikol",
            englishName = "Rinconada Bikol",
            script = ScriptId.LATIN,
            localeTag = "bto",
            layoutIds = listOf("asset_gb_bto_qwerty"),
        ),
        LanguageDef(
            id = "bts",
            displayName = "Batak Simalungun",
            englishName = "Batak Simalungun",
            script = ScriptId.BATAK,
            localeTag = "bts",
            layoutIds = listOf("asset_gb_bts_batak_simalungun"),
        ),
        LanguageDef(
            id = "btx",
            displayName = "Karo",
            englishName = "Karo",
            script = ScriptId.LATIN,
            localeTag = "btx",
            layoutIds = listOf("asset_gb_btx_qwerty"),
        ),
        LanguageDef(
            id = "btz",
            displayName = "Alas",
            englishName = "Alas",
            script = ScriptId.LATIN,
            localeTag = "btz",
            layoutIds = listOf("asset_gb_btz_qwerty"),
        ),
        LanguageDef(
            id = "bud",
            displayName = "Ntcham",
            englishName = "Ntcham",
            script = ScriptId.LATIN,
            localeTag = "bud",
            layoutIds = listOf("asset_gb_bud_ntcham"),
        ),
        LanguageDef(
            id = "bum",
            displayName = "Bulu Bene",
            englishName = "Bulu Bene",
            script = ScriptId.LATIN,
            localeTag = "bum",
            layoutIds = listOf("asset_gb_bum_bulu_bene"),
        ),
        LanguageDef(
            id = "bxg",
            displayName = "Bangala",
            englishName = "Bangala",
            script = ScriptId.LATIN,
            localeTag = "bxg",
            layoutIds = listOf("asset_gb_bxg_qwerty"),
        ),
        LanguageDef(
            id = "bxk",
            displayName = "Bukusu",
            englishName = "Bukusu",
            script = ScriptId.LATIN,
            localeTag = "bxk",
            layoutIds = listOf("asset_gb_bxk_qwerty"),
        ),
        LanguageDef(
            id = "bxm",
            displayName = "Buryat",
            englishName = "Buryat",
            script = ScriptId.CYRILLIC,
            localeTag = "bxm",
            layoutIds = listOf("asset_gb_bxm_buryat_mongolia"),
        ),
        LanguageDef(
            id = "bzc",
            displayName = "Southern Betsimisaraka Malagasy",
            englishName = "Southern Betsimisaraka Malagasy",
            script = ScriptId.LATIN,
            localeTag = "bzc",
            layoutIds = listOf("asset_gb_bzc_qwerty"),
        ),
        LanguageDef(
            id = "cce",
            displayName = "Chopi",
            englishName = "Chopi",
            script = ScriptId.LATIN,
            localeTag = "cce",
            layoutIds = listOf("asset_gb_cce_portuguese"),
        ),
        LanguageDef(
            id = "cgg",
            displayName = "Chiga",
            englishName = "Chiga",
            script = ScriptId.LATIN,
            localeTag = "cgg",
            layoutIds = listOf("asset_gb_cgg_qwerty"),
        ),
        LanguageDef(
            id = "chw",
            displayName = "Chuwabu",
            englishName = "Chuwabu",
            script = ScriptId.LATIN,
            localeTag = "chw",
            layoutIds = listOf("asset_gb_chw_qwerty_with_c_with_cedilla"),
        ),
        LanguageDef(
            id = "cja",
            displayName = "Western Cham",
            englishName = "Western Cham",
            script = ScriptId.CHAM,
            localeTag = "cja",
            layoutIds = listOf("asset_gb_cja_western_cham"),
        ),
        LanguageDef(
            id = "cjk",
            displayName = "Chokwe",
            englishName = "Chokwe",
            script = ScriptId.LATIN,
            localeTag = "cjk",
            layoutIds = listOf("asset_gb_cjk_qwerty"),
        ),
        LanguageDef(
            id = "cjm-Latn",
            displayName = "Eastern Cham (Latin)",
            englishName = "Eastern Cham (Latin)",
            script = ScriptId.LATIN,
            localeTag = "cjm-Latn",
            layoutIds = listOf("asset_gb_cjm_latn_qwerty"),
        ),
        LanguageDef(
            id = "cnh",
            displayName = "Hakha Chin",
            englishName = "Hakha Chin",
            script = ScriptId.LATIN,
            localeTag = "cnh",
            layoutIds = listOf("asset_gb_cnh_qwerty"),
        ),
        LanguageDef(
            id = "cps",
            displayName = "Capiznon",
            englishName = "Capiznon",
            script = ScriptId.LATIN,
            localeTag = "cps",
            layoutIds = listOf("asset_gb_cps_qwerty"),
        ),
        LanguageDef(
            id = "cr-Cans",
            displayName = "Cree (Canadian Aboriginal Syllabics)",
            englishName = "Cree (Canadian Aboriginal Syllabics)",
            script = ScriptId.CANADIAN_ABORIGINAL_SYLLABICS,
            localeTag = "cr-Cans",
            layoutIds = listOf("asset_gb_cr_cans_cree_syllabics"),
        ),
        LanguageDef(
            id = "crh-Cyrl",
            displayName = "Crimean Tatar (Cyrillic)",
            englishName = "Crimean Tatar (Cyrillic)",
            script = ScriptId.CYRILLIC,
            localeTag = "crh-Cyrl",
            layoutIds = listOf("asset_gb_crh_cyrl_crimean_tatar"),
        ),
        LanguageDef(
            id = "ctg",
            displayName = "Chittagonian",
            englishName = "Chittagonian",
            script = ScriptId.BENGALI,
            localeTag = "ctg",
            layoutIds = listOf("asset_gb_ctg_chittagonian"),
        ),
        LanguageDef(
            id = "dcc",
            displayName = "Dakhini",
            englishName = "Dakhini",
            script = ScriptId.ARABIC,
            localeTag = "dcc",
            layoutIds = listOf("asset_gb_dcc_dakhini"),
        ),
        LanguageDef(
            id = "dhd",
            displayName = "Dhundhari",
            englishName = "Dhundhari",
            script = ScriptId.DEVANAGARI,
            localeTag = "dhd",
            layoutIds = listOf("asset_gb_dhd_dhundhari_dynamic"),
        ),
        LanguageDef(
            id = "dje",
            displayName = "Zarma",
            englishName = "Zarma",
            script = ScriptId.LATIN,
            localeTag = "dje",
            layoutIds = listOf("asset_gb_dje_zarma"),
        ),
        LanguageDef(
            id = "dnj",
            displayName = "Dan",
            englishName = "Dan",
            script = ScriptId.LATIN,
            localeTag = "dnj",
            layoutIds = listOf("asset_gb_dnj_qwerty"),
        ),
        LanguageDef(
            id = "doi-Arab",
            displayName = "Dogri (Arabic)",
            englishName = "Dogri (Arabic)",
            script = ScriptId.ARABIC,
            localeTag = "doi-Arab",
            layoutIds = listOf("asset_gb_doi_arab_dogri_arabic"),
        ),
        LanguageDef(
            id = "doi-Latn",
            displayName = "Dogri (Latin)",
            englishName = "Dogri (Latin)",
            script = ScriptId.LATIN,
            localeTag = "doi-Latn",
            layoutIds = listOf("asset_gb_doi_latn_qwerty"),
        ),
        LanguageDef(
            id = "drs",
            displayName = "Gedeo",
            englishName = "Gedeo",
            script = ScriptId.LATIN,
            localeTag = "drs",
            layoutIds = listOf("asset_gb_drs_qwerty_with_apostrophe"),
        ),
        LanguageDef(
            id = "dyu",
            displayName = "Jula (Latin)",
            englishName = "Jula (Latin)",
            script = ScriptId.LATIN,
            localeTag = "dyu",
            layoutIds = listOf(
                "asset_gb_dyu_jula",
                "asset_gb_dyu_jula_2",
            ),
        ),
        LanguageDef(
            id = "efi",
            displayName = "Efik",
            englishName = "Efik",
            script = ScriptId.LATIN,
            localeTag = "efi",
            layoutIds = listOf("asset_gb_efi_efik"),
        ),
        LanguageDef(
            id = "egl",
            displayName = "Emilian",
            englishName = "Emilian",
            script = ScriptId.LATIN,
            localeTag = "egl",
            layoutIds = listOf("asset_gb_egl_emilian"),
        ),
        LanguageDef(
            id = "ewo",
            displayName = "Ewondo",
            englishName = "Ewondo",
            script = ScriptId.LATIN,
            localeTag = "ewo",
            layoutIds = listOf("asset_gb_ewo_ewondo"),
        ),
        LanguageDef(
            id = "fan",
            displayName = "Fang",
            englishName = "Fang",
            script = ScriptId.LATIN,
            localeTag = "fan",
            layoutIds = listOf("asset_gb_fan_qwerty"),
        ),
        LanguageDef(
            id = "fay",
            displayName = "Kuhmareyi",
            englishName = "Kuhmareyi",
            script = ScriptId.ARABIC,
            localeTag = "fay",
            layoutIds = listOf("asset_gb_fay_kuhmareyi"),
        ),
        LanguageDef(
            id = "fuh",
            displayName = "Fulfulde",
            englishName = "Fulfulde",
            script = ScriptId.LATIN,
            localeTag = "fuh",
            layoutIds = listOf("asset_gb_fuh_fulfulde"),
        ),
        LanguageDef(
            id = "gag-Cyrl",
            displayName = "Gagauz (Cyrillic)",
            englishName = "Gagauz (Cyrillic)",
            script = ScriptId.CYRILLIC,
            localeTag = "gag-Cyrl",
            layoutIds = listOf(
                "asset_gb_gag_cyrl_russian_jcuken",
                "asset_gb_gag_cyrl_gagauz",
            ),
        ),
        LanguageDef(
            id = "gax",
            displayName = "Southern Oromo",
            englishName = "Southern Oromo",
            script = ScriptId.LATIN,
            localeTag = "gax",
            layoutIds = listOf("asset_gb_gax_qwerty_with_apostrophe"),
        ),
        LanguageDef(
            id = "gay",
            displayName = "Gayo",
            englishName = "Gayo",
            script = ScriptId.LATIN,
            localeTag = "gay",
            layoutIds = listOf("asset_gb_gay_qwerty"),
        ),
        LanguageDef(
            id = "gbo",
            displayName = "Northern Grebo",
            englishName = "Northern Grebo",
            script = ScriptId.LATIN,
            localeTag = "gbo",
            layoutIds = listOf("asset_gb_gbo_grebo"),
        ),
        LanguageDef(
            id = "gdq",
            displayName = "Mehri",
            englishName = "Mehri",
            script = ScriptId.ARABIC,
            localeTag = "gdq",
            layoutIds = listOf("asset_gb_gdq_mehri"),
        ),
        LanguageDef(
            id = "gdx",
            displayName = "Godwari",
            englishName = "Godwari",
            script = ScriptId.DEVANAGARI,
            localeTag = "gdx",
            layoutIds = listOf("asset_gb_gdx_godwari"),
        ),
        LanguageDef(
            id = "gez",
            displayName = "Geez",
            englishName = "Geez",
            script = ScriptId.ETHIOPIC,
            localeTag = "gez",
            layoutIds = listOf("asset_gb_gez_geez_dynamic"),
        ),
        LanguageDef(
            id = "gjk",
            displayName = "Kachi Koli",
            englishName = "Kachi Koli",
            script = ScriptId.ARABIC,
            localeTag = "gjk",
            layoutIds = listOf("asset_gb_gjk_kachi_koli"),
        ),
        LanguageDef(
            id = "gju",
            displayName = "Gujari (Arabic)",
            englishName = "Gujari (Arabic)",
            script = ScriptId.ARABIC,
            localeTag = "gju",
            layoutIds = listOf(
                "asset_gb_gju_gujari",
                "asset_gb_gju_gujari_2",
            ),
        ),
        LanguageDef(
            id = "gmv",
            displayName = "Gamo",
            englishName = "Gamo",
            script = ScriptId.LATIN,
            localeTag = "gmv",
            layoutIds = listOf("asset_gb_gmv_qwerty"),
        ),
        LanguageDef(
            id = "gno",
            displayName = "Gondi (Devanagari)",
            englishName = "Gondi (Devanagari)",
            script = ScriptId.DEVANAGARI,
            localeTag = "gno",
            layoutIds = listOf(
                "asset_gb_gno_gondi",
                "asset_gb_gno_gondi_telugu",
            ),
        ),
        LanguageDef(
            id = "gog",
            displayName = "Gogo",
            englishName = "Gogo",
            script = ScriptId.LATIN,
            localeTag = "gog",
            layoutIds = listOf("asset_gb_gog_qwerty"),
        ),
        LanguageDef(
            id = "gqr",
            displayName = "Gor",
            englishName = "Gor",
            script = ScriptId.LATIN,
            localeTag = "gqr",
            layoutIds = listOf("asset_gb_gqr_gor"),
        ),
        LanguageDef(
            id = "grt",
            displayName = "Garo",
            englishName = "Garo",
            script = ScriptId.LATIN,
            localeTag = "grt",
            layoutIds = listOf(
                "asset_gb_grt_garo",
                "asset_gb_grt_garo_2",
            ),
        ),
        LanguageDef(
            id = "gu-Latn",
            displayName = "Gujarati (Latin)",
            englishName = "Gujarati (Latin)",
            script = ScriptId.LATIN,
            localeTag = "gu-Latn",
            layoutIds = listOf("asset_gb_gu_latn_qwerty"),
        ),
        LanguageDef(
            id = "gux",
            displayName = "Gourmanche",
            englishName = "Gourmanche",
            script = ScriptId.LATIN,
            localeTag = "gux",
            layoutIds = listOf("asset_gb_gux_gourmanche"),
        ),
        LanguageDef(
            id = "guz",
            displayName = "Gusii",
            englishName = "Gusii",
            script = ScriptId.LATIN,
            localeTag = "guz",
            layoutIds = listOf("asset_gb_guz_qwerty"),
        ),
        LanguageDef(
            id = "gvl",
            displayName = "Gulay",
            englishName = "Gulay",
            script = ScriptId.LATIN,
            localeTag = "gvl",
            layoutIds = listOf("asset_gb_gvl_gulay"),
        ),
        LanguageDef(
            id = "gyn",
            displayName = "Guyanese Creole",
            englishName = "Guyanese Creole",
            script = ScriptId.LATIN,
            localeTag = "gyn",
            layoutIds = listOf("asset_gb_gyn_qwerty"),
        ),
        LanguageDef(
            id = "hae",
            displayName = "Eastern Oromo",
            englishName = "Eastern Oromo",
            script = ScriptId.LATIN,
            localeTag = "hae",
            layoutIds = listOf("asset_gb_hae_qwerty"),
        ),
        LanguageDef(
            id = "haq",
            displayName = "Ha",
            englishName = "Ha",
            script = ScriptId.LATIN,
            localeTag = "haq",
            layoutIds = listOf("asset_gb_haq_qwerty_with_apostrophe"),
        ),
        LanguageDef(
            id = "hay",
            displayName = "Haya",
            englishName = "Haya",
            script = ScriptId.LATIN,
            localeTag = "hay",
            layoutIds = listOf("asset_gb_hay_qwerty"),
        ),
        LanguageDef(
            id = "hdy",
            displayName = "Hadiyya (Ethiopic)",
            englishName = "Hadiyya (Ethiopic)",
            script = ScriptId.ETHIOPIC,
            localeTag = "hdy",
            layoutIds = listOf(
                "asset_gb_hdy_hadiyya",
                "asset_gb_hdy_qwerty_with_apostrophe",
            ),
        ),
        LanguageDef(
            id = "hea",
            displayName = "Hmu",
            englishName = "Hmu",
            script = ScriptId.LATIN,
            localeTag = "hea",
            layoutIds = listOf("asset_gb_hea_qwerty"),
        ),
        LanguageDef(
            id = "heh",
            displayName = "Hehe",
            englishName = "Hehe",
            script = ScriptId.LATIN,
            localeTag = "heh",
            layoutIds = listOf("asset_gb_heh_qwerty"),
        ),
        LanguageDef(
            id = "hif-Deva",
            displayName = "Fiji Hindi (Devanagari)",
            englishName = "Fiji Hindi (Devanagari)",
            script = ScriptId.DEVANAGARI,
            localeTag = "hif-Deva",
            layoutIds = listOf("asset_gb_hif_deva_fiji_hindi_devanagari_dynamic"),
        ),
    ) }

    private val chunk1: List<LanguageDef> by lazy { listOf(
        LanguageDef(
            id = "hlb",
            displayName = "Halbi",
            englishName = "Halbi",
            script = ScriptId.DEVANAGARI,
            localeTag = "hlb",
            layoutIds = listOf(
                "asset_gb_hlb_halbi",
                "asset_gb_hlb_halbi_2",
            ),
        ),
        LanguageDef(
            id = "hmn",
            displayName = "Hmong",
            englishName = "Hmong",
            script = ScriptId.LATIN,
            localeTag = "hmn",
            layoutIds = listOf("asset_gb_hmn_qwerty"),
        ),
        LanguageDef(
            id = "hne",
            displayName = "Chhattisgarhi",
            englishName = "Chhattisgarhi",
            script = ScriptId.DEVANAGARI,
            localeTag = "hne",
            layoutIds = listOf("asset_gb_hne_chhattisgarhi_dynamic"),
        ),
        LanguageDef(
            id = "hni",
            displayName = "Hani",
            englishName = "Hani",
            script = ScriptId.LATIN,
            localeTag = "hni",
            layoutIds = listOf("asset_gb_hni_hani"),
        ),
        LanguageDef(
            id = "hno",
            displayName = "Northern Hindko",
            englishName = "Northern Hindko",
            script = ScriptId.ARABIC,
            localeTag = "hno",
            layoutIds = listOf("asset_gb_hno_hindko"),
        ),
        LanguageDef(
            id = "ho",
            displayName = "Hiri Motu",
            englishName = "Hiri Motu",
            script = ScriptId.LATIN,
            localeTag = "ho",
            layoutIds = listOf("asset_gb_ho_qwerty"),
        ),
        LanguageDef(
            id = "hoj",
            displayName = "Harauti",
            englishName = "Harauti",
            script = ScriptId.DEVANAGARI,
            localeTag = "hoj",
            layoutIds = listOf("asset_gb_hoj_harauti"),
        ),
        LanguageDef(
            id = "hvn",
            displayName = "Hawu",
            englishName = "Hawu",
            script = ScriptId.LATIN,
            localeTag = "hvn",
            layoutIds = listOf("asset_gb_hvn_hawu"),
        ),
        LanguageDef(
            id = "hwc",
            displayName = "Hawaiian Pidgin",
            englishName = "Hawaiian Pidgin",
            script = ScriptId.LATIN,
            localeTag = "hwc",
            layoutIds = listOf("asset_gb_hwc_qwerty_with_modifier_turned_comma"),
        ),
        LanguageDef(
            id = "ibb",
            displayName = "Ibibio",
            englishName = "Ibibio",
            script = ScriptId.LATIN,
            localeTag = "ibb",
            layoutIds = listOf("asset_gb_ibb_ibibio"),
        ),
        LanguageDef(
            id = "igb",
            displayName = "Ebira",
            englishName = "Ebira",
            script = ScriptId.LATIN,
            localeTag = "igb",
            layoutIds = listOf("asset_gb_igb_qwerty"),
        ),
        LanguageDef(
            id = "ii-Latn",
            displayName = "Nuosu (Latin)",
            englishName = "Nuosu (Latin)",
            script = ScriptId.LATIN,
            localeTag = "ii-Latn",
            layoutIds = listOf("asset_gb_ii_latn_qwerty"),
        ),
        LanguageDef(
            id = "ijc",
            displayName = "Izon",
            englishName = "Izon",
            script = ScriptId.LATIN,
            localeTag = "ijc",
            layoutIds = listOf("asset_gb_ijc_izon"),
        ),
        LanguageDef(
            id = "iso",
            displayName = "Isoko",
            englishName = "Isoko",
            script = ScriptId.LATIN,
            localeTag = "iso",
            layoutIds = listOf("asset_gb_iso_isoko"),
        ),
        LanguageDef(
            id = "itv",
            displayName = "Itawit",
            englishName = "Itawit",
            script = ScriptId.LATIN,
            localeTag = "itv",
            layoutIds = listOf("asset_gb_itv_qwerty"),
        ),
        LanguageDef(
            id = "ium",
            displayName = "Iu Mien",
            englishName = "Iu Mien",
            script = ScriptId.LATIN,
            localeTag = "ium",
            layoutIds = listOf("asset_gb_ium_qwerty_with_circumflex"),
        ),
        LanguageDef(
            id = "jax",
            displayName = "Jambi Malay",
            englishName = "Jambi Malay",
            script = ScriptId.LATIN,
            localeTag = "jax",
            layoutIds = listOf("asset_gb_jax_qwerty"),
        ),
        LanguageDef(
            id = "kao",
            displayName = "Xaasongaxango",
            englishName = "Xaasongaxango",
            script = ScriptId.LATIN,
            localeTag = "kao",
            layoutIds = listOf("asset_gb_kao_xaasongaxango"),
        ),
        LanguageDef(
            id = "kbr",
            displayName = "Kafa",
            englishName = "Kafa",
            script = ScriptId.LATIN,
            localeTag = "kbr",
            layoutIds = listOf("asset_gb_kbr_qwerty_with_apostrophe"),
        ),
        LanguageDef(
            id = "kde",
            displayName = "Makonde",
            englishName = "Makonde",
            script = ScriptId.LATIN,
            localeTag = "kde",
            layoutIds = listOf("asset_gb_kde_qwerty_with_apostrophe"),
        ),
        LanguageDef(
            id = "kea",
            displayName = "Cape Verdean Creole",
            englishName = "Cape Verdean Creole",
            script = ScriptId.LATIN,
            localeTag = "kea",
            layoutIds = listOf("asset_gb_kea_qwerty"),
        ),
        LanguageDef(
            id = "kfq",
            displayName = "Korku",
            englishName = "Korku",
            script = ScriptId.DEVANAGARI,
            localeTag = "kfq",
            layoutIds = listOf("asset_gb_kfq_korku"),
        ),
        LanguageDef(
            id = "kfr",
            displayName = "Kutchi",
            englishName = "Kutchi",
            script = ScriptId.GUJARATI,
            localeTag = "kfr",
            layoutIds = listOf("asset_gb_kfr_kutchi"),
        ),
        LanguageDef(
            id = "kfy",
            displayName = "Kumaoni",
            englishName = "Kumaoni",
            script = ScriptId.DEVANAGARI,
            localeTag = "kfy",
            layoutIds = listOf("asset_gb_kfy_kumaoni_dynamic"),
        ),
        LanguageDef(
            id = "kgp",
            displayName = "Kaingang",
            englishName = "Kaingang",
            script = ScriptId.LATIN,
            localeTag = "kgp",
            layoutIds = listOf("asset_gb_kgp_qwerty"),
        ),
        LanguageDef(
            id = "khg",
            displayName = "Khams (Tibetan)",
            englishName = "Khams (Tibetan)",
            script = ScriptId.TIBETAN,
            localeTag = "khg",
            layoutIds = listOf("asset_gb_khg_khams_tibetan"),
        ),
        LanguageDef(
            id = "kj",
            displayName = "Kwanyama",
            englishName = "Kwanyama",
            script = ScriptId.LATIN,
            localeTag = "kj",
            layoutIds = listOf("asset_gb_kj_qwerty"),
        ),
        LanguageDef(
            id = "kmb",
            displayName = "Kimbundu",
            englishName = "Kimbundu",
            script = ScriptId.LATIN,
            localeTag = "kmb",
            layoutIds = listOf("asset_gb_kmb_qwerty_with_apostrophe"),
        ),
        LanguageDef(
            id = "kmz",
            displayName = "Khorasani Turkic (Arabic)",
            englishName = "Khorasani Turkic (Arabic)",
            script = ScriptId.ARABIC,
            localeTag = "kmz",
            layoutIds = listOf(
                "asset_gb_kmz_khorasani_turkic_arabic",
                "asset_gb_kmz_turkish_q",
                "asset_gb_kmz_khorasani_turkic_latin",
            ),
        ),
        LanguageDef(
            id = "kn-Latn",
            displayName = "Kannada (Latin)",
            englishName = "Kannada (Latin)",
            script = ScriptId.LATIN,
            localeTag = "kn-Latn",
            layoutIds = listOf("asset_gb_kn_latn_qwerty"),
        ),
        LanguageDef(
            id = "kne",
            displayName = "Kankanaey",
            englishName = "Kankanaey",
            script = ScriptId.LATIN,
            localeTag = "kne",
            layoutIds = listOf("asset_gb_kne_qwerty_with_hyphen"),
        ),
        LanguageDef(
            id = "kng",
            displayName = "Koongo",
            englishName = "Koongo",
            script = ScriptId.LATIN,
            localeTag = "kng",
            layoutIds = listOf("asset_gb_kng_qwerty"),
        ),
        LanguageDef(
            id = "knn",
            displayName = "Maharashtrian Konkani",
            englishName = "Maharashtrian Konkani",
            script = ScriptId.DEVANAGARI,
            localeTag = "knn",
            layoutIds = listOf("asset_gb_knn_konkani_devanagari"),
        ),
        LanguageDef(
            id = "kok-Knda",
            displayName = "Konkani (Kannada)",
            englishName = "Konkani (Kannada)",
            script = ScriptId.KANNADA,
            localeTag = "kok-Knda",
            layoutIds = listOf("asset_gb_kok_knda_konkani_kannada"),
        ),
        LanguageDef(
            id = "kok-Latn",
            displayName = "Konkani (Latin)",
            englishName = "Konkani (Latin)",
            script = ScriptId.LATIN,
            localeTag = "kok-Latn",
            layoutIds = listOf("asset_gb_kok_latn_qwerty"),
        ),
        LanguageDef(
            id = "koo",
            displayName = "Konzo",
            englishName = "Konzo",
            script = ScriptId.LATIN,
            localeTag = "koo",
            layoutIds = listOf("asset_gb_koo_qwerty_with_apostrophe"),
        ),
        LanguageDef(
            id = "kr",
            displayName = "Kanuri",
            englishName = "Kanuri",
            script = ScriptId.LATIN,
            localeTag = "kr",
            layoutIds = listOf("asset_gb_kr_kanuri"),
        ),
        LanguageDef(
            id = "kri",
            displayName = "Krio",
            englishName = "Krio",
            script = ScriptId.LATIN,
            localeTag = "kri",
            layoutIds = listOf("asset_gb_kri_krio"),
        ),
        LanguageDef(
            id = "krj",
            displayName = "Karay-A",
            englishName = "Karay-A",
            script = ScriptId.LATIN,
            localeTag = "krj",
            layoutIds = listOf("asset_gb_krj_qwerty_with_hyphen"),
        ),
        LanguageDef(
            id = "kru",
            displayName = "Kurukh",
            englishName = "Kurukh",
            script = ScriptId.DEVANAGARI,
            localeTag = "kru",
            layoutIds = listOf("asset_gb_kru_kurukh"),
        ),
        LanguageDef(
            id = "ks-Deva",
            displayName = "Kashmiri (Devanagari)",
            englishName = "Kashmiri (Devanagari)",
            script = ScriptId.DEVANAGARI,
            localeTag = "ks-Deva",
            layoutIds = listOf("asset_gb_ks_deva_kashmiri_devanagari"),
        ),
        LanguageDef(
            id = "ktb",
            displayName = "Kambaata (Ethiopic)",
            englishName = "Kambaata (Ethiopic)",
            script = ScriptId.ETHIOPIC,
            localeTag = "ktb",
            layoutIds = listOf(
                "asset_gb_ktb_kambaata",
                "asset_gb_ktb_qwerty_with_apostrophe",
            ),
        ),
        LanguageDef(
            id = "ktu",
            displayName = "Kituba",
            englishName = "Kituba",
            script = ScriptId.LATIN,
            localeTag = "ktu",
            layoutIds = listOf("asset_gb_ktu_qwerty"),
        ),
        LanguageDef(
            id = "kxm",
            displayName = "Khmer Surin",
            englishName = "Khmer Surin",
            script = ScriptId.THAI,
            localeTag = "kxm",
            layoutIds = listOf("asset_gb_kxm_khmer_surin"),
        ),
        LanguageDef(
            id = "kxu",
            displayName = "Kui",
            englishName = "Kui",
            script = ScriptId.ORIYA,
            localeTag = "kxu",
            layoutIds = listOf("asset_gb_kxu_kui"),
        ),
        LanguageDef(
            id = "kxv",
            displayName = "Kuvi",
            englishName = "Kuvi",
            script = ScriptId.ORIYA,
            localeTag = "kxv",
            layoutIds = listOf(
                "asset_gb_kxv_kuvi",
                "asset_gb_kxv_kuvi_devanagari",
                "asset_gb_kxv_kuvi_latin",
                "asset_gb_kxv_kuvi_telugu",
            ),
        ),
        LanguageDef(
            id = "laj",
            displayName = "Lango",
            englishName = "Lango",
            script = ScriptId.LATIN,
            localeTag = "laj",
            layoutIds = listOf("asset_gb_laj_lango"),
        ),
        LanguageDef(
            id = "las",
            displayName = "Lama",
            englishName = "Lama",
            script = ScriptId.LATIN,
            localeTag = "las",
            layoutIds = listOf("asset_gb_las_lama"),
        ),
        LanguageDef(
            id = "led",
            displayName = "Lendu",
            englishName = "Lendu",
            script = ScriptId.LATIN,
            localeTag = "led",
            layoutIds = listOf("asset_gb_led_qwerty"),
        ),
        LanguageDef(
            id = "lep-Tibt",
            displayName = "Lepcha (Tibetan)",
            englishName = "Lepcha (Tibetan)",
            script = ScriptId.TIBETAN,
            localeTag = "lep-Tibt",
            layoutIds = listOf("asset_gb_lep_tibt_lepcha"),
        ),
        LanguageDef(
            id = "lew",
            displayName = "Ledo Kaili",
            englishName = "Ledo Kaili",
            script = ScriptId.LATIN,
            localeTag = "lew",
            layoutIds = listOf("asset_gb_lew_qwerty"),
        ),
        LanguageDef(
            id = "lgg",
            displayName = "Lugbara",
            englishName = "Lugbara",
            script = ScriptId.LATIN,
            localeTag = "lgg",
            layoutIds = listOf("asset_gb_lgg_qwerty_with_apostrophe"),
        ),
        LanguageDef(
            id = "lif",
            displayName = "Limbu (Devanagari)",
            englishName = "Limbu (Devanagari)",
            script = ScriptId.DEVANAGARI,
            localeTag = "lif",
            layoutIds = listOf("asset_gb_lif_limbu"),
        ),
        LanguageDef(
            id = "ljp",
            displayName = "Lampung Api",
            englishName = "Lampung Api",
            script = ScriptId.LATIN,
            localeTag = "ljp",
            layoutIds = listOf("asset_gb_ljp_qwerty"),
        ),
        LanguageDef(
            id = "lki",
            displayName = "Laki",
            englishName = "Laki",
            script = ScriptId.ARABIC,
            localeTag = "lki",
            layoutIds = listOf("asset_gb_lki_laki"),
        ),
        LanguageDef(
            id = "lmn",
            displayName = "Lambadi (Devanagari)",
            englishName = "Lambadi (Devanagari)",
            script = ScriptId.DEVANAGARI,
            localeTag = "lmn",
            layoutIds = listOf(
                "asset_gb_lmn_lambadi_devanagari",
                "asset_gb_lmn_lambadi_kannada",
                "asset_gb_lmn_lambadi_telugu",
            ),
        ),
        LanguageDef(
            id = "lon",
            displayName = "Elhomwe",
            englishName = "Elhomwe",
            script = ScriptId.LATIN,
            localeTag = "lon",
            layoutIds = listOf("asset_gb_lon_qwerty"),
        ),
        LanguageDef(
            id = "lrl",
            displayName = "Achomi",
            englishName = "Achomi",
            script = ScriptId.ARABIC,
            localeTag = "lrl",
            layoutIds = listOf("asset_gb_lrl_achomi"),
        ),
        LanguageDef(
            id = "lu",
            displayName = "Luba Katanga",
            englishName = "Luba Katanga",
            script = ScriptId.LATIN,
            localeTag = "lu",
            layoutIds = listOf("asset_gb_lu_qwerty"),
        ),
        LanguageDef(
            id = "lua",
            displayName = "Luba Kasai",
            englishName = "Luba Kasai",
            script = ScriptId.LATIN,
            localeTag = "lua",
            layoutIds = listOf("asset_gb_lua_qwerty"),
        ),
        LanguageDef(
            id = "luo",
            displayName = "Luo",
            englishName = "Luo",
            script = ScriptId.LATIN,
            localeTag = "luo",
            layoutIds = listOf("asset_gb_luo_qwerty"),
        ),
        LanguageDef(
            id = "lus",
            displayName = "Mizo",
            englishName = "Mizo",
            script = ScriptId.LATIN,
            localeTag = "lus",
            layoutIds = listOf("asset_gb_lus_qwerty"),
        ),
        LanguageDef(
            id = "luz",
            displayName = "Southern Luri",
            englishName = "Southern Luri",
            script = ScriptId.ARABIC,
            localeTag = "luz",
            layoutIds = listOf("asset_gb_luz_southern_luri"),
        ),
        LanguageDef(
            id = "mag",
            displayName = "Magahi",
            englishName = "Magahi",
            script = ScriptId.DEVANAGARI,
            localeTag = "mag",
            layoutIds = listOf("asset_gb_mag_magahi"),
        ),
        LanguageDef(
            id = "mai-Latn",
            displayName = "Maithili (Latin)",
            englishName = "Maithili (Latin)",
            script = ScriptId.LATIN,
            localeTag = "mai-Latn",
            layoutIds = listOf("asset_gb_mai_latn_qwerty"),
        ),
        LanguageDef(
            id = "mak",
            displayName = "Makassarese",
            englishName = "Makassarese",
            script = ScriptId.LATIN,
            localeTag = "mak",
            layoutIds = listOf(
                "asset_gb_mak_qwerty_with_apostrophe",
                "asset_gb_mak_makassarese",
            ),
        ),
        LanguageDef(
            id = "mas",
            displayName = "Maasai",
            englishName = "Maasai",
            script = ScriptId.LATIN,
            localeTag = "mas",
            layoutIds = listOf("asset_gb_mas_maasai"),
        ),
        LanguageDef(
            id = "mcn",
            displayName = "Massa",
            englishName = "Massa",
            script = ScriptId.LATIN,
            localeTag = "mcn",
            layoutIds = listOf("asset_gb_mcn_massa"),
        ),
        LanguageDef(
            id = "mde",
            displayName = "Maba",
            englishName = "Maba",
            script = ScriptId.ARABIC,
            localeTag = "mde",
            layoutIds = listOf("asset_gb_mde_maba"),
        ),
        LanguageDef(
            id = "mdh",
            displayName = "Maguindanao",
            englishName = "Maguindanao",
            script = ScriptId.LATIN,
            localeTag = "mdh",
            layoutIds = listOf("asset_gb_mdh_qwerty"),
        ),
        LanguageDef(
            id = "mel",
            displayName = "Melanau",
            englishName = "Melanau",
            script = ScriptId.LATIN,
            localeTag = "mel",
            layoutIds = listOf("asset_gb_mel_qwerty"),
        ),
        LanguageDef(
            id = "men",
            displayName = "Mende",
            englishName = "Mende",
            script = ScriptId.LATIN,
            localeTag = "men",
            layoutIds = listOf("asset_gb_men_mende"),
        ),
        LanguageDef(
            id = "meo",
            displayName = "Kedah Malay",
            englishName = "Kedah Malay",
            script = ScriptId.LATIN,
            localeTag = "meo",
            layoutIds = listOf("asset_gb_meo_qwerty"),
        ),
        LanguageDef(
            id = "mer",
            displayName = "Meru",
            englishName = "Meru",
            script = ScriptId.LATIN,
            localeTag = "mer",
            layoutIds = listOf("asset_gb_mer_meru"),
        ),
        LanguageDef(
            id = "mfa-Arab",
            displayName = "Kelantan Pattani Malay (Arabic)",
            englishName = "Kelantan Pattani Malay (Arabic)",
            script = ScriptId.ARABIC,
            localeTag = "mfa-Arab",
            layoutIds = listOf("asset_gb_mfa_arab_malay"),
        ),
        LanguageDef(
            id = "mfb",
            displayName = "Bangka Malay",
            englishName = "Bangka Malay",
            script = ScriptId.LATIN,
            localeTag = "mfb",
            layoutIds = listOf("asset_gb_mfb_qwerty"),
        ),
        LanguageDef(
            id = "mfp",
            displayName = "Makassar Malay",
            englishName = "Makassar Malay",
            script = ScriptId.LATIN,
            localeTag = "mfp",
            layoutIds = listOf("asset_gb_mfp_qwerty_with_apostrophe"),
        ),
        LanguageDef(
            id = "mfq",
            displayName = "Moba",
            englishName = "Moba",
            script = ScriptId.LATIN,
            localeTag = "mfq",
            layoutIds = listOf("asset_gb_mfq_moba"),
        ),
        LanguageDef(
            id = "mgh",
            displayName = "Makhuwa Meetto",
            englishName = "Makhuwa Meetto",
            script = ScriptId.LATIN,
            localeTag = "mgh",
            layoutIds = listOf("asset_gb_mgh_qwerty"),
        ),
        LanguageDef(
            id = "mhr",
            displayName = "Meadow Mari",
            englishName = "Meadow Mari",
            script = ScriptId.CYRILLIC,
            localeTag = "mhr",
            layoutIds = listOf("asset_gb_mhr_meadow_mari"),
        ),
        LanguageDef(
            id = "mhy",
            displayName = "Maanyan",
            englishName = "Maanyan",
            script = ScriptId.LATIN,
            localeTag = "mhy",
            layoutIds = listOf("asset_gb_mhy_qwerty"),
        ),
        LanguageDef(
            id = "mjl",
            displayName = "Mandeali",
            englishName = "Mandeali",
            script = ScriptId.DEVANAGARI,
            localeTag = "mjl",
            layoutIds = listOf("asset_gb_mjl_mandeali_dynamic"),
        ),
        LanguageDef(
            id = "ml-Latn",
            displayName = "Malayalam (Latin)",
            englishName = "Malayalam (Latin)",
            script = ScriptId.LATIN,
            localeTag = "ml-Latn",
            layoutIds = listOf("asset_gb_ml_latn_qwerty"),
        ),
        LanguageDef(
            id = "mmr",
            displayName = "Xong",
            englishName = "Xong",
            script = ScriptId.LATIN,
            localeTag = "mmr",
            layoutIds = listOf("asset_gb_mmr_qwerty"),
        ),
        LanguageDef(
            id = "mnb",
            displayName = "Muna",
            englishName = "Muna",
            script = ScriptId.LATIN,
            localeTag = "mnb",
            layoutIds = listOf("asset_gb_mnb_qwerty"),
        ),
        LanguageDef(
            id = "mni-Beng",
            displayName = "Manipuri (Bengali)",
            englishName = "Manipuri (Bengali)",
            script = ScriptId.BENGALI,
            localeTag = "mni-Beng",
            layoutIds = listOf("asset_gb_mni_beng_manipuri_bengali"),
        ),
        LanguageDef(
            id = "mni-Latn",
            displayName = "Manipuri (Latin)",
            englishName = "Manipuri (Latin)",
            script = ScriptId.LATIN,
            localeTag = "mni-Latn",
            layoutIds = listOf("asset_gb_mni_latn_qwerty"),
        ),
        LanguageDef(
            id = "mnk",
            displayName = "Mandinka (Latin)",
            englishName = "Mandinka (Latin)",
            script = ScriptId.LATIN,
            localeTag = "mnk",
            layoutIds = listOf("asset_gb_mnk_qwerty"),
        ),
        LanguageDef(
            id = "mnp",
            displayName = "Northern Min",
            englishName = "Northern Min",
            script = ScriptId.LATIN,
            localeTag = "mnp",
            layoutIds = listOf("asset_gb_mnp_northern_min"),
        ),
        LanguageDef(
            id = "mog",
            displayName = "Mongondow",
            englishName = "Mongondow",
            script = ScriptId.LATIN,
            localeTag = "mog",
            layoutIds = listOf("asset_gb_mog_qwerty_with_apostrophe"),
        ),
        LanguageDef(
            id = "mpg",
            displayName = "Marba",
            englishName = "Marba",
            script = ScriptId.LATIN,
            localeTag = "mpg",
            layoutIds = listOf("asset_gb_mpg_qwerty_with_apostrophe"),
        ),
        LanguageDef(
            id = "mqj",
            displayName = "Mamasa",
            englishName = "Mamasa",
            script = ScriptId.LATIN,
            localeTag = "mqj",
            layoutIds = listOf("asset_gb_mqj_qwerty_with_apostrophe"),
        ),
        LanguageDef(
            id = "mqy",
            displayName = "Manggarai",
            englishName = "Manggarai",
            script = ScriptId.LATIN,
            localeTag = "mqy",
            layoutIds = listOf("asset_gb_mqy_qwerty_with_apostrophe"),
        ),
        LanguageDef(
            id = "mr-Latn",
            displayName = "Marathi (Latin)",
            englishName = "Marathi (Latin)",
            script = ScriptId.LATIN,
            localeTag = "mr-Latn",
            layoutIds = listOf("asset_gb_mr_latn_qwerty"),
        ),
        LanguageDef(
            id = "mrw",
            displayName = "Maranao",
            englishName = "Maranao",
            script = ScriptId.LATIN,
            localeTag = "mrw",
            layoutIds = listOf("asset_gb_mrw_qwerty"),
        ),
        LanguageDef(
            id = "msi",
            displayName = "Sabah Malay",
            englishName = "Sabah Malay",
            script = ScriptId.LATIN,
            localeTag = "msi",
            layoutIds = listOf("asset_gb_msi_qwerty"),
        ),
        LanguageDef(
            id = "mtq",
            displayName = "Muong",
            englishName = "Muong",
            script = ScriptId.LATIN,
            localeTag = "mtq",
            layoutIds = listOf("asset_gb_mtq_qwerty"),
        ),
        LanguageDef(
            id = "mtr",
            displayName = "Mewari",
            englishName = "Mewari",
            script = ScriptId.DEVANAGARI,
            localeTag = "mtr",
            layoutIds = listOf("asset_gb_mtr_mewari"),
        ),
        LanguageDef(
            id = "mua",
            displayName = "Mundang",
            englishName = "Mundang",
            script = ScriptId.LATIN,
            localeTag = "mua",
            layoutIds = listOf("asset_gb_mua_mundang"),
        ),
        LanguageDef(
            id = "mui",
            displayName = "Musi",
            englishName = "Musi",
            script = ScriptId.LATIN,
            localeTag = "mui",
            layoutIds = listOf("asset_gb_mui_qwerty"),
        ),
    ) }

    private val chunk2: List<LanguageDef> by lazy { listOf(
        LanguageDef(
            id = "mup",
            displayName = "Malvi",
            englishName = "Malvi",
            script = ScriptId.DEVANAGARI,
            localeTag = "mup",
            layoutIds = listOf("asset_gb_mup_malvi"),
        ),
        LanguageDef(
            id = "mvp",
            displayName = "Duri",
            englishName = "Duri",
            script = ScriptId.LATIN,
            localeTag = "mvp",
            layoutIds = listOf("asset_gb_mvp_qwerty_with_apostrophe"),
        ),
        LanguageDef(
            id = "mwm",
            displayName = "Sar",
            englishName = "Sar",
            script = ScriptId.LATIN,
            localeTag = "mwm",
            layoutIds = listOf("asset_gb_mwm_sar"),
        ),
        LanguageDef(
            id = "myx",
            displayName = "Masaba",
            englishName = "Masaba",
            script = ScriptId.LATIN,
            localeTag = "myx",
            layoutIds = listOf("asset_gb_myx_qwerty"),
        ),
        LanguageDef(
            id = "ndc",
            displayName = "Ndau",
            englishName = "Ndau",
            script = ScriptId.LATIN,
            localeTag = "ndc",
            layoutIds = listOf("asset_gb_ndc_qwerty"),
        ),
        LanguageDef(
            id = "ne-Latn",
            displayName = "Nepali (Latin)",
            englishName = "Nepali (Latin)",
            script = ScriptId.LATIN,
            localeTag = "ne-Latn",
            layoutIds = listOf("asset_gb_ne_latn_qwerty"),
        ),
        LanguageDef(
            id = "ng",
            displayName = "Ndonga",
            englishName = "Ndonga",
            script = ScriptId.LATIN,
            localeTag = "ng",
            layoutIds = listOf("asset_gb_ng_qwerty"),
        ),
        LanguageDef(
            id = "nga",
            displayName = "Ngbaka Gbaya",
            englishName = "Ngbaka Gbaya",
            script = ScriptId.LATIN,
            localeTag = "nga",
            layoutIds = listOf("asset_gb_nga_qwerty"),
        ),
        LanguageDef(
            id = "ngl",
            displayName = "Lomwe",
            englishName = "Lomwe",
            script = ScriptId.LATIN,
            localeTag = "ngl",
            layoutIds = listOf("asset_gb_ngl_qwerty"),
        ),
        LanguageDef(
            id = "nij",
            displayName = "Ngaju",
            englishName = "Ngaju",
            script = ScriptId.LATIN,
            localeTag = "nij",
            layoutIds = listOf("asset_gb_nij_qwerty"),
        ),
        LanguageDef(
            id = "niq",
            displayName = "Nandi",
            englishName = "Nandi",
            script = ScriptId.LATIN,
            localeTag = "niq",
            layoutIds = listOf("asset_gb_niq_qwerty_with_apostrophe"),
        ),
        LanguageDef(
            id = "nod",
            displayName = "Northern Thai",
            englishName = "Northern Thai",
            script = ScriptId.THAI,
            localeTag = "nod",
            layoutIds = listOf("asset_gb_nod_thai"),
        ),
        LanguageDef(
            id = "noe",
            displayName = "Nimadi",
            englishName = "Nimadi",
            script = ScriptId.DEVANAGARI,
            localeTag = "noe",
            layoutIds = listOf("asset_gb_noe_nimadi"),
        ),
        LanguageDef(
            id = "nut",
            displayName = "Nung",
            englishName = "Nung",
            script = ScriptId.LATIN,
            localeTag = "nut",
            layoutIds = listOf("asset_gb_nut_qwerty"),
        ),
        LanguageDef(
            id = "nyf",
            displayName = "Giryama",
            englishName = "Giryama",
            script = ScriptId.LATIN,
            localeTag = "nyf",
            layoutIds = listOf("asset_gb_nyf_qwerty"),
        ),
        LanguageDef(
            id = "nym",
            displayName = "Nyamwezi",
            englishName = "Nyamwezi",
            script = ScriptId.LATIN,
            localeTag = "nym",
            layoutIds = listOf("asset_gb_nym_qwerty"),
        ),
        LanguageDef(
            id = "nyn",
            displayName = "Nkore",
            englishName = "Nkore",
            script = ScriptId.LATIN,
            localeTag = "nyn",
            layoutIds = listOf("asset_gb_nyn_qwerty"),
        ),
        LanguageDef(
            id = "nyo",
            displayName = "Nyoro",
            englishName = "Nyoro",
            script = ScriptId.LATIN,
            localeTag = "nyo",
            layoutIds = listOf("asset_gb_nyo_qwerty"),
        ),
        LanguageDef(
            id = "nyy",
            displayName = "Nyakyusa",
            englishName = "Nyakyusa",
            script = ScriptId.LATIN,
            localeTag = "nyy",
            layoutIds = listOf("asset_gb_nyy_nyakyusa"),
        ),
        LanguageDef(
            id = "oj-Latn",
            displayName = "Ojibwe (Latin)",
            englishName = "Ojibwe (Latin)",
            script = ScriptId.LATIN,
            localeTag = "oj-Latn",
            layoutIds = listOf(
                "asset_gb_oj_latn_ojibwe",
                "asset_gb_oj_latn_qwerty_with_apostrophe",
            ),
        ),
        LanguageDef(
            id = "or-Latn",
            displayName = "Odia (Latin)",
            englishName = "Odia (Latin)",
            script = ScriptId.LATIN,
            localeTag = "or-Latn",
            layoutIds = listOf("asset_gb_or_latn_qwerty"),
        ),
        LanguageDef(
            id = "osa-Latn",
            displayName = "Osage (Latin)",
            englishName = "Osage (Latin)",
            script = ScriptId.LATIN,
            localeTag = "osa-Latn",
            layoutIds = listOf("asset_gb_osa_latn_osage"),
        ),
        LanguageDef(
            id = "pa-Arab",
            displayName = "Punjabi (Arabic)",
            englishName = "Punjabi (Arabic)",
            script = ScriptId.ARABIC,
            localeTag = "pa-Arab",
            layoutIds = listOf("asset_gb_pa_arab_punjabi_pakistan"),
        ),
        LanguageDef(
            id = "pa-Latn",
            displayName = "Punjabi (Latin)",
            englishName = "Punjabi (Latin)",
            script = ScriptId.LATIN,
            localeTag = "pa-Latn",
            layoutIds = listOf("asset_gb_pa_latn_qwerty"),
        ),
        LanguageDef(
            id = "pcc",
            displayName = "Bouyei",
            englishName = "Bouyei",
            script = ScriptId.LATIN,
            localeTag = "pcc",
            layoutIds = listOf("asset_gb_pcc_qwerty"),
        ),
        LanguageDef(
            id = "pko",
            displayName = "Pokot",
            englishName = "Pokot",
            script = ScriptId.LATIN,
            localeTag = "pko",
            layoutIds = listOf("asset_gb_pko_pokot"),
        ),
        LanguageDef(
            id = "plk",
            displayName = "Kohistani Shina",
            englishName = "Kohistani Shina",
            script = ScriptId.ARABIC,
            localeTag = "plk",
            layoutIds = listOf("asset_gb_plk_shina"),
        ),
        LanguageDef(
            id = "plt",
            displayName = "Plateau Malagasy",
            englishName = "Plateau Malagasy",
            script = ScriptId.LATIN,
            localeTag = "plt",
            layoutIds = listOf("asset_gb_plt_qwerty"),
        ),
        LanguageDef(
            id = "pmf",
            displayName = "Pamona",
            englishName = "Pamona",
            script = ScriptId.LATIN,
            localeTag = "pmf",
            layoutIds = listOf("asset_gb_pmf_qwerty_with_apostrophe"),
        ),
        LanguageDef(
            id = "pmy",
            displayName = "Papuan Malay",
            englishName = "Papuan Malay",
            script = ScriptId.LATIN,
            localeTag = "pmy",
            layoutIds = listOf("asset_gb_pmy_qwerty"),
        ),
        LanguageDef(
            id = "pov",
            displayName = "Guinea Bissau Creole",
            englishName = "Guinea Bissau Creole",
            script = ScriptId.LATIN,
            localeTag = "pov",
            layoutIds = listOf("asset_gb_pov_qwerty"),
        ),
        LanguageDef(
            id = "prk",
            displayName = "Parauk Wa",
            englishName = "Parauk Wa",
            script = ScriptId.LATIN,
            localeTag = "prk",
            layoutIds = listOf("asset_gb_prk_qwerty"),
        ),
        LanguageDef(
            id = "pse",
            displayName = "Central Malay",
            englishName = "Central Malay",
            script = ScriptId.LATIN,
            localeTag = "pse",
            layoutIds = listOf("asset_gb_pse_qwerty"),
        ),
        LanguageDef(
            id = "psh",
            displayName = "Southwestern Pashayi",
            englishName = "Southwestern Pashayi",
            script = ScriptId.ARABIC,
            localeTag = "psh",
            layoutIds = listOf("asset_gb_psh_pashayi"),
        ),
        LanguageDef(
            id = "pua",
            displayName = "Purepecha",
            englishName = "Purepecha",
            script = ScriptId.LATIN,
            localeTag = "pua",
            layoutIds = listOf("asset_gb_pua_purepecha"),
        ),
        LanguageDef(
            id = "qug",
            displayName = "Chimborazo Highland Kichwa",
            englishName = "Chimborazo Highland Kichwa",
            script = ScriptId.LATIN,
            localeTag = "qug",
            layoutIds = listOf("asset_gb_qug_chimborazo_highland_kichwa"),
        ),
        LanguageDef(
            id = "quh",
            displayName = "South Bolivian Quechua",
            englishName = "South Bolivian Quechua",
            script = ScriptId.LATIN,
            localeTag = "quh",
            layoutIds = listOf("asset_gb_quh_quechua"),
        ),
        LanguageDef(
            id = "quy",
            displayName = "Ayacucho Quechua",
            englishName = "Ayacucho Quechua",
            script = ScriptId.LATIN,
            localeTag = "quy",
            layoutIds = listOf("asset_gb_quy_qwerty_with_n_with_tilde"),
        ),
        LanguageDef(
            id = "quz",
            displayName = "Cusco Quechua",
            englishName = "Cusco Quechua",
            script = ScriptId.LATIN,
            localeTag = "quz",
            layoutIds = listOf("asset_gb_quz_quechua"),
        ),
        LanguageDef(
            id = "rcf",
            displayName = "Reunion Creole",
            englishName = "Reunion Creole",
            script = ScriptId.LATIN,
            localeTag = "rcf",
            layoutIds = listOf("asset_gb_rcf_qwerty"),
        ),
        LanguageDef(
            id = "rhg-Latn",
            displayName = "Rohingya (Latin)",
            englishName = "Rohingya (Latin)",
            script = ScriptId.LATIN,
            localeTag = "rhg-Latn",
            layoutIds = listOf("asset_gb_rhg_latn_rohingya"),
        ),
        LanguageDef(
            id = "rim",
            displayName = "Nyaturu",
            englishName = "Nyaturu",
            script = ScriptId.LATIN,
            localeTag = "rim",
            layoutIds = listOf("asset_gb_rim_nyaturu"),
        ),
        LanguageDef(
            id = "rkt",
            displayName = "Rangpuri Bangladesh (Bengali)",
            englishName = "Rangpuri Bangladesh (Bengali)",
            script = ScriptId.BENGALI,
            localeTag = "rkt",
            layoutIds = listOf(
                "asset_gb_rkt_rangpuri",
                "asset_gb_rkt_rangpuri_2",
            ),
        ),
        LanguageDef(
            id = "rmn",
            displayName = "Balkan Romani",
            englishName = "Balkan Romani",
            script = ScriptId.CYRILLIC,
            localeTag = "rmn",
            layoutIds = listOf("asset_gb_rmn_romani"),
        ),
        LanguageDef(
            id = "rwr",
            displayName = "Marwari",
            englishName = "Marwari",
            script = ScriptId.DEVANAGARI,
            localeTag = "rwr",
            layoutIds = listOf("asset_gb_rwr_marwari"),
        ),
        LanguageDef(
            id = "sas",
            displayName = "Sasak Bali",
            englishName = "Sasak Bali",
            script = ScriptId.BALINESE,
            localeTag = "sas",
            layoutIds = listOf("asset_gb_sas_sasak"),
        ),
        LanguageDef(
            id = "sat-Beng",
            displayName = "Santali (Bengali)",
            englishName = "Santali (Bengali)",
            script = ScriptId.BENGALI,
            localeTag = "sat-Beng",
            layoutIds = listOf("asset_gb_sat_beng_santali_bengali"),
        ),
        LanguageDef(
            id = "sat-Deva",
            displayName = "Santali (Devanagari)",
            englishName = "Santali (Devanagari)",
            script = ScriptId.DEVANAGARI,
            localeTag = "sat-Deva",
            layoutIds = listOf("asset_gb_sat_deva_santali_devanagari"),
        ),
        LanguageDef(
            id = "sat-Latn",
            displayName = "Santali (Latin)",
            englishName = "Santali (Latin)",
            script = ScriptId.LATIN,
            localeTag = "sat-Latn",
            layoutIds = listOf("asset_gb_sat_latn_qwerty"),
        ),
        LanguageDef(
            id = "sba",
            displayName = "Ngambay",
            englishName = "Ngambay",
            script = ScriptId.LATIN,
            localeTag = "sba",
            layoutIds = listOf("asset_gb_sba_ngambay"),
        ),
        LanguageDef(
            id = "sck",
            displayName = "Sadri",
            englishName = "Sadri",
            script = ScriptId.DEVANAGARI,
            localeTag = "sck",
            layoutIds = listOf("asset_gb_sck_sadri_dynamic"),
        ),
        LanguageDef(
            id = "sd-Deva",
            displayName = "Sindhi (Devanagari)",
            englishName = "Sindhi (Devanagari)",
            script = ScriptId.DEVANAGARI,
            localeTag = "sd-Deva",
            layoutIds = listOf("asset_gb_sd_deva_sindhi_devanagari_dynamic"),
        ),
        LanguageDef(
            id = "sd-Latn",
            displayName = "Sindhi (Latin)",
            englishName = "Sindhi (Latin)",
            script = ScriptId.LATIN,
            localeTag = "sd-Latn",
            layoutIds = listOf("asset_gb_sd_latn_qwerty"),
        ),
        LanguageDef(
            id = "sda",
            displayName = "Toraja Sadan",
            englishName = "Toraja Sadan",
            script = ScriptId.LATIN,
            localeTag = "sda",
            layoutIds = listOf("asset_gb_sda_qwerty_with_apostrophe"),
        ),
        LanguageDef(
            id = "sdc",
            displayName = "Sassarese",
            englishName = "Sassarese",
            script = ScriptId.LATIN,
            localeTag = "sdc",
            layoutIds = listOf("asset_gb_sdc_qwerty"),
        ),
        LanguageDef(
            id = "sef",
            displayName = "Cebaara Senoufo",
            englishName = "Cebaara Senoufo",
            script = ScriptId.LATIN,
            localeTag = "sef",
            layoutIds = listOf("asset_gb_sef_cebaara_senoufo"),
        ),
        LanguageDef(
            id = "seh",
            displayName = "Sena",
            englishName = "Sena",
            script = ScriptId.LATIN,
            localeTag = "seh",
            layoutIds = listOf("asset_gb_seh_qwerty"),
        ),
        LanguageDef(
            id = "sgc",
            displayName = "Kipsigis",
            englishName = "Kipsigis",
            script = ScriptId.LATIN,
            localeTag = "sgc",
            layoutIds = listOf("asset_gb_sgc_qwerty"),
        ),
        LanguageDef(
            id = "sgj",
            displayName = "Surgujia",
            englishName = "Surgujia",
            script = ScriptId.DEVANAGARI,
            localeTag = "sgj",
            layoutIds = listOf("asset_gb_sgj_surgujia"),
        ),
        LanguageDef(
            id = "sid",
            displayName = "Sidamo",
            englishName = "Sidamo",
            script = ScriptId.LATIN,
            localeTag = "sid",
            layoutIds = listOf("asset_gb_sid_qwerty_with_apostrophe"),
        ),
        LanguageDef(
            id = "sjp",
            displayName = "Surjapuri",
            englishName = "Surjapuri",
            script = ScriptId.DEVANAGARI,
            localeTag = "sjp",
            layoutIds = listOf("asset_gb_sjp_surjapuri"),
        ),
        LanguageDef(
            id = "skg",
            displayName = "Sakalava Malagasy",
            englishName = "Sakalava Malagasy",
            script = ScriptId.LATIN,
            localeTag = "skg",
            layoutIds = listOf("asset_gb_skg_qwerty"),
        ),
        LanguageDef(
            id = "snk",
            displayName = "Soninke",
            englishName = "Soninke",
            script = ScriptId.LATIN,
            localeTag = "snk",
            layoutIds = listOf("asset_gb_snk_soninke"),
        ),
        LanguageDef(
            id = "sop",
            displayName = "Songe",
            englishName = "Songe",
            script = ScriptId.LATIN,
            localeTag = "sop",
            layoutIds = listOf("asset_gb_sop_qwerty"),
        ),
        LanguageDef(
            id = "sou",
            displayName = "Southern Thai",
            englishName = "Southern Thai",
            script = ScriptId.THAI,
            localeTag = "sou",
            layoutIds = listOf("asset_gb_sou_thai"),
        ),
        LanguageDef(
            id = "spp",
            displayName = "Supyire",
            englishName = "Supyire",
            script = ScriptId.LATIN,
            localeTag = "spp",
            layoutIds = listOf("asset_gb_spp_supyire"),
        ),
        LanguageDef(
            id = "stv",
            displayName = "Silte (Ethiopic)",
            englishName = "Silte (Ethiopic)",
            script = ScriptId.ETHIOPIC,
            localeTag = "stv",
            layoutIds = listOf(
                "asset_gb_stv_silte",
                "asset_gb_stv_qwerty_with_apostrophe",
            ),
        ),
        LanguageDef(
            id = "sty",
            displayName = "Siberian Tatar",
            englishName = "Siberian Tatar",
            script = ScriptId.CYRILLIC,
            localeTag = "sty",
            layoutIds = listOf("asset_gb_sty_siberian_tatar"),
        ),
        LanguageDef(
            id = "su-Arab",
            displayName = "Sundanese (Arabic)",
            englishName = "Sundanese (Arabic)",
            script = ScriptId.ARABIC,
            localeTag = "su-Arab",
            layoutIds = listOf("asset_gb_su_arab_sundanese"),
        ),
        LanguageDef(
            id = "suk",
            displayName = "Sukuma",
            englishName = "Sukuma",
            script = ScriptId.LATIN,
            localeTag = "suk",
            layoutIds = listOf("asset_gb_suk_qwerty"),
        ),
        LanguageDef(
            id = "swv",
            displayName = "Shekhawati",
            englishName = "Shekhawati",
            script = ScriptId.DEVANAGARI,
            localeTag = "swv",
            layoutIds = listOf("asset_gb_swv_shekhawati"),
        ),
        LanguageDef(
            id = "sxu",
            displayName = "Upper Saxon",
            englishName = "Upper Saxon",
            script = ScriptId.LATIN,
            localeTag = "sxu",
            layoutIds = listOf("asset_gb_sxu_upper_saxon"),
        ),
        LanguageDef(
            id = "syl-Latn",
            displayName = "Sylheti (Latin)",
            englishName = "Sylheti (Latin)",
            script = ScriptId.LATIN,
            localeTag = "syl-Latn",
            layoutIds = listOf("asset_gb_syl_latn_qwerty"),
        ),
        LanguageDef(
            id = "ta-Latn",
            displayName = "Tamil (Latin)",
            englishName = "Tamil (Latin)",
            script = ScriptId.LATIN,
            localeTag = "ta-Latn",
            layoutIds = listOf("asset_gb_ta_latn_qwerty"),
        ),
        LanguageDef(
            id = "taj",
            displayName = "Eastern Tamang",
            englishName = "Eastern Tamang",
            script = ScriptId.DEVANAGARI,
            localeTag = "taj",
            layoutIds = listOf("asset_gb_taj_tamang"),
        ),
        LanguageDef(
            id = "tbw",
            displayName = "Aborlan",
            englishName = "Aborlan",
            script = ScriptId.TAGBANWA,
            localeTag = "tbw",
            layoutIds = listOf("asset_gb_tbw_aborlan"),
        ),
        LanguageDef(
            id = "tdx",
            displayName = "Tandroy Mahafaly Malagasy",
            englishName = "Tandroy Mahafaly Malagasy",
            script = ScriptId.LATIN,
            localeTag = "tdx",
            layoutIds = listOf("asset_gb_tdx_qwerty"),
        ),
        LanguageDef(
            id = "te-Latn",
            displayName = "Telugu (Latin)",
            englishName = "Telugu (Latin)",
            script = ScriptId.LATIN,
            localeTag = "te-Latn",
            layoutIds = listOf("asset_gb_te_latn_qwerty"),
        ),
        LanguageDef(
            id = "tem",
            displayName = "Temne",
            englishName = "Temne",
            script = ScriptId.LATIN,
            localeTag = "tem",
            layoutIds = listOf("asset_gb_tem_temne"),
        ),
        LanguageDef(
            id = "teo",
            displayName = "Teso",
            englishName = "Teso",
            script = ScriptId.LATIN,
            localeTag = "teo",
            layoutIds = listOf("asset_gb_teo_qwerty"),
        ),
        LanguageDef(
            id = "tiv",
            displayName = "Tiv",
            englishName = "Tiv",
            script = ScriptId.LATIN,
            localeTag = "tiv",
            layoutIds = listOf("asset_gb_tiv_qwerty"),
        ),
        LanguageDef(
            id = "tks",
            displayName = "Tati",
            englishName = "Tati",
            script = ScriptId.ARABIC,
            localeTag = "tks",
            layoutIds = listOf("asset_gb_tks_tati"),
        ),
        LanguageDef(
            id = "tll",
            displayName = "Tetela",
            englishName = "Tetela",
            script = ScriptId.LATIN,
            localeTag = "tll",
            layoutIds = listOf("asset_gb_tll_tetela"),
        ),
        LanguageDef(
            id = "tly-Arab",
            displayName = "Talysh (Arabic)",
            englishName = "Talysh (Arabic)",
            script = ScriptId.ARABIC,
            localeTag = "tly-Arab",
            layoutIds = listOf("asset_gb_tly_arab_talysh"),
        ),
        LanguageDef(
            id = "trf",
            displayName = "Trinidadian Creole English",
            englishName = "Trinidadian Creole English",
            script = ScriptId.LATIN,
            localeTag = "trf",
            layoutIds = listOf("asset_gb_trf_qwerty"),
        ),
        LanguageDef(
            id = "trp",
            displayName = "Kok Borok (Bengali)",
            englishName = "Kok Borok (Bengali)",
            script = ScriptId.BENGALI,
            localeTag = "trp",
            layoutIds = listOf("asset_gb_trp_kok_borok"),
        ),
        LanguageDef(
            id = "tsc",
            displayName = "Tswa",
            englishName = "Tswa",
            script = ScriptId.LATIN,
            localeTag = "tsc",
            layoutIds = listOf("asset_gb_tsc_tswa"),
        ),
        LanguageDef(
            id = "tsg",
            displayName = "Tausug",
            englishName = "Tausug",
            script = ScriptId.LATIN,
            localeTag = "tsg",
            layoutIds = listOf("asset_gb_tsg_tausug"),
        ),
        LanguageDef(
            id = "ttj",
            displayName = "Tooro",
            englishName = "Tooro",
            script = ScriptId.LATIN,
            localeTag = "ttj",
            layoutIds = listOf("asset_gb_ttj_qwerty"),
        ),
        LanguageDef(
            id = "tuv",
            displayName = "Turkana",
            englishName = "Turkana",
            script = ScriptId.LATIN,
            localeTag = "tuv",
            layoutIds = listOf("asset_gb_tuv_qwerty_with_apostrophe"),
        ),
        LanguageDef(
            id = "unr",
            displayName = "Mundari (Bengali)",
            englishName = "Mundari (Bengali)",
            script = ScriptId.BENGALI,
            localeTag = "unr",
            layoutIds = listOf(
                "asset_gb_unr_mundari_bengali",
                "asset_gb_unr_mundari",
                "asset_gb_unr_mundari_2",
                "asset_gb_unr_mundari_3",
            ),
        ),
        LanguageDef(
            id = "uum",
            displayName = "Urum (Cyrillic)",
            englishName = "Urum (Cyrillic)",
            script = ScriptId.CYRILLIC,
            localeTag = "uum",
            layoutIds = listOf("asset_gb_uum_urum"),
        ),
        LanguageDef(
            id = "uz-Cyrl",
            displayName = "Uzbek (Cyrillic)",
            englishName = "Uzbek (Cyrillic)",
            script = ScriptId.CYRILLIC,
            localeTag = "uz-Cyrl",
            layoutIds = listOf(
                "asset_gb_uz_cyrl_uzbek",
                "asset_gb_uz_cyrl_uzbek_full",
            ),
        ),
        LanguageDef(
            id = "vah",
            displayName = "Varhadi",
            englishName = "Varhadi",
            script = ScriptId.DEVANAGARI,
            localeTag = "vah",
            layoutIds = listOf("asset_gb_vah_varhadi"),
        ),
        LanguageDef(
            id = "vas",
            displayName = "Vasavi",
            englishName = "Vasavi",
            script = ScriptId.DEVANAGARI,
            localeTag = "vas",
            layoutIds = listOf(
                "asset_gb_vas_vasavi",
                "asset_gb_vas_vasavi_2",
            ),
        ),
        LanguageDef(
            id = "vel",
            displayName = "Veluws",
            englishName = "Veluws",
            script = ScriptId.LATIN,
            localeTag = "vel",
            layoutIds = listOf("asset_gb_vel_qwerty"),
        ),
        LanguageDef(
            id = "vkt",
            displayName = "Kutai Malay",
            englishName = "Kutai Malay",
            script = ScriptId.LATIN,
            localeTag = "vkt",
            layoutIds = listOf("asset_gb_vkt_qwerty"),
        ),
        LanguageDef(
            id = "vmf",
            displayName = "East Franconian German",
            englishName = "East Franconian German",
            script = ScriptId.LATIN,
            localeTag = "vmf",
            layoutIds = listOf(
                "asset_gb_vmf_east_franconian_german",
                "asset_gb_vmf_de_neo2",
            ),
        ),
        LanguageDef(
            id = "vmw",
            displayName = "Central Makhuwa",
            englishName = "Central Makhuwa",
            script = ScriptId.LATIN,
            localeTag = "vmw",
            layoutIds = listOf("asset_gb_vmw_qwerty"),
        ),
        LanguageDef(
            id = "wal",
            displayName = "Wolaytta",
            englishName = "Wolaytta",
            script = ScriptId.LATIN,
            localeTag = "wal",
            layoutIds = listOf("asset_gb_wal_qwerty_with_apostrophe"),
        ),
    ) }

    private val chunk3: List<LanguageDef> by lazy { listOf(
        LanguageDef(
            id = "wbr",
            displayName = "Wagdi",
            englishName = "Wagdi",
            script = ScriptId.DEVANAGARI,
            localeTag = "wbr",
            layoutIds = listOf("asset_gb_wbr_wagdi"),
        ),
        LanguageDef(
            id = "wes",
            displayName = "Cameroon Pidgin",
            englishName = "Cameroon Pidgin",
            script = ScriptId.LATIN,
            localeTag = "wes",
            layoutIds = listOf("asset_gb_wes_qwerty"),
        ),
        LanguageDef(
            id = "wne",
            displayName = "Wanetsi",
            englishName = "Wanetsi",
            script = ScriptId.ARABIC,
            localeTag = "wne",
            layoutIds = listOf("asset_gb_wne_wanetsi"),
        ),
        LanguageDef(
            id = "wry",
            displayName = "Merwari",
            englishName = "Merwari",
            script = ScriptId.DEVANAGARI,
            localeTag = "wry",
            layoutIds = listOf("asset_gb_wry_merwari"),
        ),
        LanguageDef(
            id = "xmm",
            displayName = "Manado Malay",
            englishName = "Manado Malay",
            script = ScriptId.LATIN,
            localeTag = "xmm",
            layoutIds = listOf("asset_gb_xmm_qwerty"),
        ),
        LanguageDef(
            id = "xnr",
            displayName = "Kangri",
            englishName = "Kangri",
            script = ScriptId.DEVANAGARI,
            localeTag = "xnr",
            layoutIds = listOf("asset_gb_xnr_kangri_dynamic"),
        ),
        LanguageDef(
            id = "xog",
            displayName = "Soga",
            englishName = "Soga",
            script = ScriptId.LATIN,
            localeTag = "xog",
            layoutIds = listOf("asset_gb_xog_soga"),
        ),
        LanguageDef(
            id = "xon",
            displayName = "Konkomba",
            englishName = "Konkomba",
            script = ScriptId.LATIN,
            localeTag = "xon",
            layoutIds = listOf("asset_gb_xon_konkomba"),
        ),
        LanguageDef(
            id = "xsr",
            displayName = "Sherpa",
            englishName = "Sherpa",
            script = ScriptId.DEVANAGARI,
            localeTag = "xsr",
            layoutIds = listOf("asset_gb_xsr_sherpa_dynamic"),
        ),
        LanguageDef(
            id = "yaf",
            displayName = "Yaka",
            englishName = "Yaka",
            script = ScriptId.LATIN,
            localeTag = "yaf",
            layoutIds = listOf("asset_gb_yaf_qwerty_with_apostrophe"),
        ),
        LanguageDef(
            id = "yao",
            displayName = "Yao",
            englishName = "Yao",
            script = ScriptId.LATIN,
            localeTag = "yao",
            layoutIds = listOf("asset_gb_yao_yao"),
        ),
        LanguageDef(
            id = "ymm",
            displayName = "Maay Maay",
            englishName = "Maay Maay",
            script = ScriptId.LATIN,
            localeTag = "ymm",
            layoutIds = listOf("asset_gb_ymm_qwerty"),
        ),
        LanguageDef(
            id = "yrl",
            displayName = "Nheengatu",
            englishName = "Nheengatu",
            script = ScriptId.LATIN,
            localeTag = "yrl",
            layoutIds = listOf("asset_gb_yrl_qwerty"),
        ),
        LanguageDef(
            id = "zne",
            displayName = "Zande",
            englishName = "Zande",
            script = ScriptId.LATIN,
            localeTag = "zne",
            layoutIds = listOf("asset_gb_zne_qwerty"),
        ),
        LanguageDef(
            id = "zyb",
            displayName = "Yongbei Zhuang",
            englishName = "Yongbei Zhuang",
            script = ScriptId.LATIN,
            localeTag = "zyb",
            layoutIds = listOf("asset_gb_zyb_qwerty"),
        ),
        LanguageDef(
            id = "zyj",
            displayName = "Youjiang Zhuang",
            englishName = "Youjiang Zhuang",
            script = ScriptId.LATIN,
            localeTag = "zyj",
            layoutIds = listOf("asset_gb_zyj_qwerty"),
        ),
    ) }

    val all: List<LanguageDef> by lazy {
        buildList {
            addAll(chunk0)
            addAll(chunk1)
            addAll(chunk2)
            addAll(chunk3)
        }
    }

    /**
     * Keyboards for a language the app already carries, one language a line:
     * its id, a tab, then its layouts. A table rather than a mapOf so the
     * keyboard's process pays for one string and a short loop at startup, not
     * an initializer ART refuses to compile.
     */
    val extraLayoutIds: Map<String, List<String>> by lazy {
        EXTRA_TABLE.lineSequence().filter { it.isNotEmpty() }.associate { line ->
            val fields = line.split('\t')
            fields[0] to fields.drop(1)
        }
    }

    private const val EXTRA_TABLE: String =
        "ab\tasset_gb_ab_abkhaz\n" +
        "ady\tasset_gb_ady_adyghe\n" +
        "aii\tasset_gb_aii_assyrian\n" +
        "ak\tasset_gb_ak_akan\n" +
        "aln\tasset_gb_aln_albanian\n" +
        "am\tasset_gb_am_amharic\n" +
        "ami\tasset_gb_ami_qwerty_with_apostrophe\n" +
        "an\tasset_gb_an_aragonese\n" +
        "anp\tasset_gb_anp_angika\n" +
        "ar\tasset_gb_ar_arabic_two\n" +
        "as\tasset_gb_as_assamese\n" +
        "ast\tasset_gb_ast_asturian\n" +
        "av\tasset_gb_av_avar\n" +
        "awa\tasset_gb_awa_awadhi_dynamic\n" +
        "ay\tasset_gb_ay_aymara\n" +
        "az\tasset_gb_az_azerbaijani\tasset_gb_az_querty\n" +
        "az-RU\tasset_gb_az_ru_azerbaijani\n" +
        "ba\tasset_gb_ba_bashkir\n" +
        "bal\tasset_gb_bal_balochi\n" +
        "bal-Latn\tasset_gb_bal_latn_balochi_latin\n" +
        "ban-Bali\tasset_gb_ban_bali_balinese\n" +
        "bar\tasset_gb_bar_bavarian\n" +
        "bax\tasset_gb_bax_bamum\n" +
        "be\tasset_gb_be_belarusian_belarus\n" +
        "ber\tasset_gb_ber_tamazight\n" +
        "bft\tasset_gb_bft_balti\n" +
        "bfz\tasset_gb_bfz_mahasu_pahari\n" +
        "bg\tasset_gb_bg_bulgarian\tasset_gb_bg_bulgarian_phonetic\n" +
        "bho\tasset_gb_bho_bhojpuri\n" +
        "bin\tasset_gb_bin_qwerty\n" +
        "blk\tasset_gb_blk_pao_karen\n" +
        "blt\tasset_gb_blt_tai_dam\n" +
        "blt-Latn\tasset_gb_blt_latn_qwerty\n" +
        "bm\tasset_gb_bm_bambara\n" +
        "bm-Nkoo\tasset_gb_bm_nkoo_bambara_nko_dynamic\n" +
        "bn\tasset_gb_bn_bengali_bangladesh\n" +
        "bn-IN\tasset_gb_bn_in_bengali_india\n" +
        "bo\tasset_gb_bo_tibetan\tasset_gb_bo_tibetan_alphabetical\n" +
        "bpy\tasset_gb_bpy_bishnupriya_manipuri\n" +
        "brh\tasset_gb_brh_brahui\n" +
        "brh-Latn\tasset_gb_brh_latn_qwerty\n" +
        "brx\tasset_gb_brx_bodo_devanagari_dynamic\n" +
        "bug\tasset_gb_bug_buginese\n" +
        "bug-Bugi\tasset_gb_bug_bugi_buginese\n" +
        "ca\tasset_gb_ca_spanish\n" +
        "cbk\tasset_gb_cbk_chavacano\n" +
        "ccp\tasset_gb_ccp_chakma_compact\tasset_gb_ccp_chakma\n" +
        "cdo-Latn-030\tasset_gb_cdo_latn_030_eastern_min\n" +
        "ce\tasset_gb_ce_chechen\n" +
        "ch\tasset_gb_ch_chamorro\n" +
        "cho\tasset_gb_cho_choctaw\n" +
        "chr\tasset_gb_chr_cherokee\tasset_gb_chr_cherokee_2\n" +
        "cjm\tasset_gb_cjm_eastern_cham\n" +
        "cld-Syrc\tasset_gb_cld_syrc_chaldean_neo_aramaic\n" +
        "cop-Copt\tasset_gb_cop_copt_coptic\n" +
        "cr\tasset_gb_cr_cree_latin\n" +
        "crh\tasset_gb_crh_turkish_q\tasset_gb_crh_turkish_f\n" +
        "cu\tasset_gb_cu_church_slavonic\tasset_gb_cu_russian_jcuken\n" +
        "cv\tasset_gb_cv_chuvash\n" +
        "cy\tasset_gb_cy_welsh\n" +
        "cyo\tasset_gb_cyo_qwerty_with_apostrophe\n" +
        "dag\tasset_gb_dag_dagbani\n" +
        "dar\tasset_gb_dar_dargwa\n" +
        "de\tasset_gb_de_german\n" +
        "dga\tasset_gb_dga_dagaare\n" +
        "din\tasset_gb_din_dinka\n" +
        "dng\tasset_gb_dng_dungan\n" +
        "doi\tasset_gb_doi_dogri\n" +
        "dty\tasset_gb_dty_dotyali\n" +
        "dv\tasset_gb_dv_dhivehi\n" +
        "dzg\tasset_gb_dzg_qwerty\n" +
        "ee\tasset_gb_ee_ewe\n" +
        "el\tasset_gb_el_greek\n" +
        "en\tasset_gb_en_english_philippines\tasset_gb_en_pcqwerty\n" +
        "et\tasset_gb_et_estonian\n" +
        "eu\tasset_gb_eu_spanish\n" +
        "ext\tasset_gb_ext_spanish\n" +
        "fat\tasset_gb_fat_fantse\n" +
        "ff\tasset_gb_ff_fula\tasset_gb_ff_fula_adlam\n" +
        "fo\tasset_gb_fo_faroese\n" +
        "fon\tasset_gb_fon_fon\n" +
        "fr\tasset_gb_fr_bepo\n" +
        "fub-Latn\tasset_gb_fub_latn_qwerty\n" +
        "fuc\tasset_gb_fuc_pulaar\n" +
        "fur\tasset_gb_fur_friulian\n" +
        "fuv\tasset_gb_fuv_fulfulde\n" +
        "gbm\tasset_gb_gbm_garhwali\n" +
        "gl\tasset_gb_gl_spanish\n" +
        "glk\tasset_gb_glk_gilaki\n" +
        "gn\tasset_gb_gn_spanish\n" +
        "gor\tasset_gb_gor_qwerty_with_apostrophe\n" +
        "gsw\tasset_gb_gsw_alsatian\tasset_gb_gsw_swiss\n" +
        "gu\tasset_gb_gu_gujarati\n" +
        "guc\tasset_gb_guc_wayuu\n" +
        "gur\tasset_gb_gur_farefare\n" +
        "gv\tasset_gb_gv_manx\n" +
        "gwc\tasset_gb_gwc_kalam_kohistani\n" +
        "ha\tasset_gb_ha_hausa\n" +
        "hac\tasset_gb_hac_gorani\n" +
        "hak-Latn-030\tasset_gb_hak_latn_030_hakka\n" +
        "haz\tasset_gb_haz_hazaragi\n" +
        "he\tasset_gb_he_hebrew\tasset_gb_he_hebrew_qwerty\n" +
        "hi\tasset_gb_hi_hindi_10key_a\n" +
        "hi_rom\tasset_gb_hi_rom_qwerty\n" +
        "hil\tasset_gb_hil_hiligaynon\n" +
        "hnd\tasset_gb_hnd_hindko\n" +
        "hnn-Hano\tasset_gb_hnn_hano_hanunuo\n" +
        "hrx\tasset_gb_hrx_portuguese\n" +
        "hu\tasset_gb_hu_hungarian\n" +
        "hy\tasset_gb_hy_armenian_armenia_phonetic\n" +
        "ig\tasset_gb_ig_spanish\n" +
        "igl\tasset_gb_igl_igala\n" +
        "ii\tasset_gb_ii_yi_dynamic\n" +
        "ilo\tasset_gb_ilo_ilocano\n" +
        "inh\tasset_gb_inh_ingush\n" +
        "is\tasset_gb_is_icelandic\n" +
        "iu\tasset_gb_iu_inuktitut_dynamic\n" +
        "iu-Latn\tasset_gb_iu_latn_inuktitut_latin\n" +
        "jbo\tasset_gb_jbo_lojban\n" +
        "jv-Java\tasset_gb_jv_java_javanese\n" +
        "ka\tasset_gb_ka_georgian_khutsuri\n" +
        "kaa\tasset_gb_kaa_karakalpak\n" +
        "kaa-Latn\tasset_gb_kaa_latn_karakalpak\n" +
        "kac\tasset_gb_kac_qwerty\n" +
        "kam\tasset_gb_kam_kamba\n" +
        "kbd\tasset_gb_kbd_kabardian\n" +
        "kbp\tasset_gb_kbp_kabiye\n" +
        "kek\tasset_gb_kek_qwerty_with_apostrophe\n" +
        "khb-Talu\tasset_gb_khb_talu_tai_lue\n" +
        "khw\tasset_gb_khw_khowar\n" +
        "ki\tasset_gb_ki_kikuyu\n" +
        "kiu\tasset_gb_kiu_kirmanjki\tasset_gb_kiu_turkish_q\tasset_gb_kiu_turkish_f\n" +
        "kjg-Laoo\tasset_gb_kjg_laoo_khmu\n" +
        "kjp\tasset_gb_kjp_eastern_pwo\n" +
        "kk\tasset_gb_kk_kazakh\tasset_gb_kk_kazakh_pc\n" +
        "kk-CN\tasset_gb_kk_cn_kazakh_arabic\n" +
        "km\tasset_gb_km_khmer_cambodia_native\n" +
        "kn\tasset_gb_kn_kannada_india\n" +
        "koi\tasset_gb_koi_komi_permyak\n" +
        "kok\tasset_gb_kok_konkani_devanagari\n" +
        "krl\tasset_gb_krl_karelian\tasset_gb_krl_nordic\n" +
        "ks\tasset_gb_ks_kashmiri_arabic\n" +
        "ksh\tasset_gb_ksh_kolsch\n" +
        "ksw\tasset_gb_ksw_sgaw_karen\n" +
        "ku\tasset_gb_ku_kurdish_latin\n" +
        "ku-Arab\tasset_gb_ku_arab_kurdish\tasset_gb_ku_arab_kurdish_2\n" +
        "kum\tasset_gb_kum_kumyk\n" +
        "kv\tasset_gb_kv_komi\n" +
        "kvx\tasset_gb_kvx_parkari_koli\n" +
        "ky\tasset_gb_ky_kyrgyz\n" +
        "kyu\tasset_gb_kyu_kayah_li\n" +
        "la\tasset_gb_la_spanish\n" +
        "lad\tasset_gb_lad_ladino\tasset_gb_lad_turkish_q\n" +
        "lb\tasset_gb_lb_luxembourgish\n" +
        "lbe\tasset_gb_lbe_lak\n" +
        "lep\tasset_gb_lep_lepcha\n" +
        "lez\tasset_gb_lez_lezgian\n" +
        "lg\tasset_gb_lg_luganda\n" +
        "lhu\tasset_gb_lhu_qwerty\n" +
        "li\tasset_gb_li_limburgish\n" +
        "lif-Limb\tasset_gb_lif_limb_limbu\n" +
        "lij\tasset_gb_lij_ligurian\n" +
        "lis\tasset_gb_lis_lisu_lisu\n" +
        "lkt\tasset_gb_lkt_lakota\n" +
        "ln\tasset_gb_ln_lingala\n" +
        "lo\tasset_gb_lo_lao\n" +
        "lrc\tasset_gb_lrc_northern_luri\n" +
        "lzz-Latn\tasset_gb_lzz_latn_laz_latn_dynamic\n" +
        "mad\tasset_gb_mad_madurese\n" +
        "mai\tasset_gb_mai_maithili\n" +
        "mdf\tasset_gb_mdf_moksha\n" +
        "mi\tasset_gb_mi_maori\n" +
        "min\tasset_gb_min_minangkabau\n" +
        "mk\tasset_gb_mk_macedonian\n" +
        "mki\tasset_gb_mki_dhatki\n" +
        "ml\tasset_gb_ml_malayalam_india\n" +
        "mn\tasset_gb_mn_mongolian\n" +
        "mni\tasset_gb_mni_manipuri_meitei\n" +
        "mnw\tasset_gb_mnw_mon\n" +
        "mos\tasset_gb_mos_mossi\n" +
        "mr\tasset_gb_mr_marathi_india\n" +
        "mrj\tasset_gb_mrj_hill_mari\n" +
        "ms-Arab\tasset_gb_ms_arab_malay\n" +
        "mt\tasset_gb_mt_maltese\n" +
        "mve\tasset_gb_mve_marwari_arabic\n" +
        "mvy\tasset_gb_mvy_indus_kohistani\n" +
        "my\tasset_gb_my_burmese\n" +
        "myv\tasset_gb_myv_erzya\n" +
        "mzn\tasset_gb_mzn_mazanderani\n" +
        "nan-Latn\tasset_gb_nan_latn_southern_min\tasset_gb_nan_latn_qwerty_with_hyphen\n" +
        "nds\tasset_gb_nds_low_saxon\n" +
        "ne\tasset_gb_ne_nepali_devanagari_dynamic\tasset_gb_ne_nepali_nepal_romanized\tasset_gb_ne_nepali_nepal_traditional\n" +
        "new\tasset_gb_new_newari\n" +
        "nia\tasset_gb_nia_nias\n" +
        "nnb\tasset_gb_nnb_nande\n" +
        "nso\tasset_gb_nso_northern_sotho\n" +
        "nup\tasset_gb_nup_nupe\n" +
        "nv\tasset_gb_nv_navajo\n" +
        "ny\tasset_gb_ny_nyanja\n" +
        "oj\tasset_gb_oj_ojibwe\n" +
        "olo\tasset_gb_olo_livvi_karelian\n" +
        "om\tasset_gb_om_qwerty_with_apostrophe\n" +
        "or\tasset_gb_or_odia\n" +
        "os\tasset_gb_os_ossetian\n" +
        "pa\tasset_gb_pa_punjabi_gurmukhi\n" +
        "pam\tasset_gb_pam_kapampangan\n" +
        "pap\tasset_gb_pap_qwerty_with_n_with_tilde\n" +
        "pfl\tasset_gb_pfl_palatine_german\n" +
        "phr\tasset_gb_phr_pahari_pothwari\n" +
        "pms\tasset_gb_pms_piedmontese\n" +
        "pnt\tasset_gb_pnt_pontic\n" +
        "ps\tasset_gb_ps_pashto\n" +
        "psi\tasset_gb_psi_pashayi\n" +
        "qu\tasset_gb_qu_quechua\n" +
        "quc\tasset_gb_quc_kiche\n" +
        "qxq\tasset_gb_qxq_qashqai\n" +
        "rej-Rjng\tasset_gb_rej_rjng_rejang\n" +
        "rki\tasset_gb_rki_arakanese\n" +
        "rmy\tasset_gb_rmy_romani\tasset_gb_rmy_romanian\n" +
        "rn\tasset_gb_rn_rundi\n" +
        "rue\tasset_gb_rue_rusyn\n" +
        "rw\tasset_gb_rw_kinyarwanda\n" +
        "sa\tasset_gb_sa_sanskrit\n" +
        "sa-Latn\tasset_gb_sa_latn_qwerty\n" +
        "sah\tasset_gb_sah_sakha\n" +
        "sat\tasset_gb_sat_santali_ol_chiki\n" +
        "saz\tasset_gb_saz_saurashtra\n" +
        "scl\tasset_gb_scl_shina\n" +
        "sd\tasset_gb_sd_sindhi_arabic\n" +
        "sdh\tasset_gb_sdh_southern_kurdish\n" +
        "se\tasset_gb_se_northern_sami\tasset_gb_se_asertt\n" +
        "sg\tasset_gb_sg_sango\n" +
        "sgw-Ethi\tasset_gb_sgw_ethi_sebat_bet_gurage\n" +
        "shn\tasset_gb_shn_shan\n" +
        "si\tasset_gb_si_sinhala\n" +
        "skr\tasset_gb_skr_saraiki\tasset_gb_skr_saraiki_2\n" +
        "so\tasset_gb_so_somali\n" +
        "sq\tasset_gb_sq_albanian\n" +
        "sr\tasset_gb_sr_serbian\n" +
        "srr\tasset_gb_srr_serer\n" +
        "su\tasset_gb_su_sundanese\n" +
        "su-Sund\tasset_gb_su_sund_sundanese\n" +
        "syc\tasset_gb_syc_classical_syriac\n" +
        "syl\tasset_gb_syl_sylheti\n" +
        "syl-Sylo\tasset_gb_syl_sylo_sylheti_syloti_nagri_dynamic\n" +
        "ta\tasset_gb_ta_tamil\tasset_gb_ta_tamil_phonetic\n" +
        "tab\tasset_gb_tab_tabasaran\n" +
        "tcy\tasset_gb_tcy_tulu_india\n" +
        "tdd\tasset_gb_tdd_tai_nuea\n" +
        "te\tasset_gb_te_telugu_india\n" +
        "tet\tasset_gb_tet_tetum\n" +
        "tg\tasset_gb_tg_tajik\n" +
        "th\tasset_gb_th_thai\n" +
        "ti\tasset_gb_ti_tigrinya\n" +
        "tk\tasset_gb_tk_turkmen\n" +
        "tl-Tglg\tasset_gb_tl_tglg_baybayin\n" +
        "tn\tasset_gb_tn_tswana\n" +
        "trw\tasset_gb_trw_torwali\n" +
        "tt\tasset_gb_tt_tatar\n" +
        "tts\tasset_gb_tts_thai\n" +
        "tum\tasset_gb_tum_tumbuka\n" +
        "tyv\tasset_gb_tyv_tuvan\n" +
        "tyz\tasset_gb_tyz_qwerty\n" +
        "uk\tasset_gb_uk_ukrainian\n" +
        "ur\tasset_gb_ur_urdu_pakistan\tasset_gb_ur_urdu_in\n" +
        "uz\tasset_gb_uz_uzbek\n" +
        "vai\tasset_gb_vai_vai\n" +
        "vec\tasset_gb_vec_venetian\n" +
        "vep\tasset_gb_vep_veps\n" +
        "vro\tasset_gb_vro_voro\n" +
        "war\tasset_gb_war_spanish\n" +
        "wo\tasset_gb_wo_wolof\n" +
        "xal\tasset_gb_xal_kalmyk_oirat\n" +
        "xdq\tasset_gb_xdq_kaitag\n" +
        "xmf-Latn\tasset_gb_xmf_latn_mingrelian_latn_dynamic\n" +
        "yi\tasset_gb_yi_yiddish\tasset_gb_yi_yiddish_2\n" +
        "yrk\tasset_gb_yrk_nenets\n" +
        "yua\tasset_gb_yua_qwerty_with_apostrophe\n"
}

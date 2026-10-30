package com.wasimaster.wmkeyboard.core.layout

import android.content.res.AssetManager

/**
 * The long tail of language layouts, shipped as JSON assets rather than Kotlin
 * [BuiltInLayouts].
 *
 * A boot-critical grid — the default-enabled layouts drawn on the first frame —
 * has to be a compiled `val`: it cannot wait for a parse, and a typo in it must
 * fail the build, not ship a blank keyboard. Every other layout can be data.
 * Shipping it as the same [LayoutFile] envelope the export path already writes
 * gets three things at once: it parses off the main thread, a mistake costs one
 * language rather than the build, and each file is user-importable and editable
 * for free.
 *
 * ## Read on demand, never all at once
 *
 * There are over fifteen hundred of these, 43 MB of JSON, and a user has a
 * handful switched on. Parsing the lot at every process start — which is what
 * this object used to do — held every one of them on the heap for the life of
 * the process (the keyboard's process, which the settings app shares) and put
 * seconds of parsing on a slow phone between a cold start and the first
 * settings frame (#296, #313). So [load] reads only the index the build writes
 * beside them (id, name, language — see `generateLayoutIndex` in the app's
 * build script), and [byId] parses one file the first time it is asked for and
 * keeps a bounded number of them after that.
 *
 * Asset layouts are deliberately never in `defaultEnabledIds`, so an index that
 * is not loaded yet on the first frame only means a not-yet-selected language
 * is briefly absent — never a keyboard with nothing to draw.
 */
object AssetLayouts {

    /** Folder under `assets/` holding the layout files. */
    private const val DIR = "layouts"

    /** Stable ids of the shipped asset layouts, for the [LanguageDef]s that group them. */
    const val PT_QWERTY_ID = "asset_pt_qwerty"
    const val UK_JCUKEN_ID = "asset_uk_jcuken"
    const val IT_QWERTY_ID = "asset_it_qwerty"
    const val NL_QWERTY_ID = "asset_nl_qwerty"
    const val PL_QWERTY_ID = "asset_pl_qwerty"
    const val SV_QWERTY_ID = "asset_sv_qwerty"
    const val SR_JCUKEN_ID = "asset_sr_jcuken"
    const val BG_PHONETIC_ID = "asset_bg_phonetic"
    const val KA_QWERTY_ID = "asset_ka_qwerty"
    const val CS_QWERTZ_ID = "asset_cs_qwertz"
    const val SK_QWERTZ_ID = "asset_sk_qwertz"
    const val RO_QWERTY_ID = "asset_ro_qwerty"
    const val HU_QWERTZ_ID = "asset_hu_qwertz"
    const val FI_QWERTY_ID = "asset_fi_qwerty"
    const val DA_QWERTY_ID = "asset_da_qwerty"
    const val NB_QWERTY_ID = "asset_nb_qwerty"
    const val HR_QWERTZ_ID = "asset_hr_qwertz"
    const val FA_STANDARD_ID = "asset_fa_standard"
    /** Persian as Gboard lays it out (#506): ژ on its own key, no shift, harakat on the full stop. */
    const val FA_GBOARD_ID = "asset_fa_gboard"
    const val BE_JCUKEN_ID = "asset_be_jcuken"
    const val ET_QWERTY_ID = "asset_et_qwerty"
    const val LT_QWERTY_ID = "asset_lt_qwerty"
    const val LV_QWERTY_ID = "asset_lv_qwerty"
    const val SL_QWERTZ_ID = "asset_sl_qwertz"

    // --- Multi-language expansion: long-tail asset layouts. ---
    const val UR_PHONETIC_ID = "asset_ur_phonetic"
    const val PS_PASHTO_ID = "asset_ps_pashto"
    const val SD_SINDHI_ID = "asset_sd_sindhi"
    const val UG_UYGHUR_ID = "asset_ug_uyghur"
    const val CKB_SORANI_ID = "asset_ckb_sorani"
    const val HY_PHONETIC_ID = "asset_hy_phonetic"
    const val HY_EASTERN_ID = "asset_hy_eastern"
    const val MK_CYRILLIC_ID = "asset_mk_macedonian"
    const val KK_CYRILLIC_ID = "asset_kk_kazakh"
    const val KY_CYRILLIC_ID = "asset_ky_kyrgyz"
    const val TG_CYRILLIC_ID = "asset_tg_tajik"
    const val MN_CYRILLIC_ID = "asset_mn_mongolian"
    const val TT_CYRILLIC_ID = "asset_tt_tatar"
    const val BA_CYRILLIC_ID = "asset_ba_bashkir"
    const val CV_CYRILLIC_ID = "asset_cv_chuvash"
    const val CE_CYRILLIC_ID = "asset_ce_chechen"
    const val OS_CYRILLIC_ID = "asset_os_ossetian"
    const val SAH_CYRILLIC_ID = "asset_sah_yakut"
    const val MR_INSCRIPT_ID = "asset_mr_inscript"
    const val NE_INSCRIPT_ID = "asset_ne_inscript"
    const val SA_INSCRIPT_ID = "asset_sa_inscript"
    const val TA_TAMIL99_ID = "asset_ta_tamil99"
    const val SI_WIJESEKARA_ID = "asset_si_wijesekara"
    const val TE_INSCRIPT_ID = "asset_te_inscript"
    const val KN_INSCRIPT_ID = "asset_kn_inscript"
    const val ML_INSCRIPT_ID = "asset_ml_inscript"
    const val GU_INSCRIPT_ID = "asset_gu_inscript"
    const val PA_INSCRIPT_ID = "asset_pa_gurmukhi_inscript"
    const val OR_INSCRIPT_ID = "asset_or_odia_inscript"
    const val CA_CATALAN_ID = "asset_ca_catalan"
    const val GL_GALICIAN_ID = "asset_gl_galician"
    const val EU_BASQUE_ID = "asset_eu_basque"
    const val OC_OCCITAN_ID = "asset_oc_occitan"
    const val BR_BRETON_ID = "asset_br_breton"
    const val CO_CORSICAN_ID = "asset_co_corsican"
    const val LA_LATIN_ID = "asset_la_latin"
    const val LB_LUXEMBOURGISH_ID = "asset_lb_luxembourgish"
    const val FY_FRISIAN_ID = "asset_fy_frisian"
    const val FO_FAROESE_ID = "asset_fo_faroese"
    const val CY_WELSH_ID = "asset_cy_welsh"
    const val GA_IRISH_ID = "asset_ga_irish"
    const val GD_GAELIC_ID = "asset_gd_gaelic"
    const val IS_ICELANDIC_ID = "asset_is_icelandic"
    const val SQ_ALBANIAN_ID = "asset_sq_albanian"
    const val MT_MALTESE_ID = "asset_mt_maltese"
    const val EO_ESPERANTO_ID = "asset_eo_esperanto"
    const val AF_AFRIKAANS_ID = "asset_af_afrikaans"
    const val TR_TURKISH_Q_ID = "asset_tr_turkishq"
    const val AZ_LATIN_ID = "asset_az_azlatin"
    const val UZ_LATIN_ID = "asset_uz_uzlatin"
    const val TK_LATIN_ID = "asset_tk_tklatin"
    const val KU_KURMANJI_ID = "asset_ku_kurmanji"
    const val ID_QWERTY_ID = "asset_id_indonesian"
    const val MS_QWERTY_ID = "asset_ms_malay"
    const val TL_QWERTY_ID = "asset_tl_filipino"
    const val CEB_QWERTY_ID = "asset_ceb_cebuano"
    const val JV_QWERTY_ID = "asset_jv_javanese"
    const val SU_QWERTY_ID = "asset_su_sundanese"
    const val MI_QWERTY_ID = "asset_mi_maori"
    const val HAW_QWERTY_ID = "asset_haw_hawaiian"
    const val MG_QWERTY_ID = "asset_mg_malagasy"
    const val SM_QWERTY_ID = "asset_sm_samoan"
    const val FJ_QWERTY_ID = "asset_fj_fijian"
    const val TO_QWERTY_ID = "asset_to_tongan"
    const val SW_QWERTY_ID = "asset_sw_sw_qwerty"
    const val ZU_QWERTY_ID = "asset_zu_zu_qwerty"
    const val XH_QWERTY_ID = "asset_xh_xh_qwerty"
    const val YO_QWERTY_ID = "asset_yo_yo_qwerty"
    const val IG_QWERTY_ID = "asset_ig_ig_qwerty"
    const val HA_QWERTY_ID = "asset_ha_ha_qwerty"
    const val SO_QWERTY_ID = "asset_so_so_qwerty"
    const val RW_QWERTY_ID = "asset_rw_rw_qwerty"
    const val LN_QWERTY_ID = "asset_ln_ln_qwerty"
    const val NY_QWERTY_ID = "asset_ny_ny_qwerty"
    const val SN_QWERTY_ID = "asset_sn_sn_qwerty"
    const val ST_QWERTY_ID = "asset_st_st_qwerty"
    const val TN_QWERTY_ID = "asset_tn_tn_qwerty"
    const val WO_QWERTY_ID = "asset_wo_wo_qwerty"
    const val VI_QWERTY_ID = "asset_vi_vietnamese"
    const val HT_QWERTY_ID = "asset_ht_haitian"
    const val QU_QWERTY_ID = "asset_qu_quechua"
    const val GN_QWERTY_ID = "asset_gn_guarani"
    const val TH_KEDMANEE_ID = "asset_th_kedmanee"
    const val LO_LAO_ID = "asset_lo_lao"
    const val KM_NIDA_ID = "asset_km_nida"
    const val MY_MYANMAR3_ID = "asset_my_myanmar3"
    const val RU_PHONETIC_ID = "asset_ru_phonetic"
    const val FR_BEPO_ID = "asset_fr_bepo"
    const val ES_LATAM_ID = "asset_es_latam"
    const val DE_SWISS_ID = "asset_de_swiss"
    const val UK_PHONETIC_ID = "asset_uk_phonetic"

    const val UDM_CYRILLIC_ID = "asset_udm_cyrillic"
    const val KV_CYRILLIC_ID = "asset_kv_cyrillic"
    const val XAL_CYRILLIC_ID = "asset_xal_cyrillic"
    const val TYV_CYRILLIC_ID = "asset_tyv_cyrillic"
    const val BUA_CYRILLIC_ID = "asset_bua_cyrillic"
    const val MYV_CYRILLIC_ID = "asset_myv_cyrillic"
    const val CHM_CYRILLIC_ID = "asset_chm_cyrillic"
    const val ADY_CYRILLIC_ID = "asset_ady_cyrillic"
    const val KBD_CYRILLIC_ID = "asset_kbd_cyrillic"
    const val AB_CYRILLIC_ID = "asset_ab_cyrillic"
    const val AV_CYRILLIC_ID = "asset_av_cyrillic"
    const val LEZ_CYRILLIC_ID = "asset_lez_cyrillic"
    const val KOK_INSCRIPT_ID = "asset_kok_inscript"
    const val MAI_INSCRIPT_ID = "asset_mai_inscript"
    const val BRX_INSCRIPT_ID = "asset_brx_inscript"
    const val DOI_INSCRIPT_ID = "asset_doi_inscript"
    const val BHO_INSCRIPT_ID = "asset_bho_inscript"
    const val AM_ETHIOPIC_ID = "asset_am_ethiopic"
    const val TI_ETHIOPIC_ID = "asset_ti_ethiopic"
    const val RM_QWERTY_ID = "asset_rm_qwerty"
    const val WA_QWERTY_ID = "asset_wa_qwerty"
    const val SCN_QWERTY_ID = "asset_scn_qwerty"
    const val VEC_QWERTY_ID = "asset_vec_qwerty"
    const val LIJ_QWERTY_ID = "asset_lij_qwerty"
    const val NAP_QWERTY_ID = "asset_nap_qwerty"
    const val FUR_QWERTY_ID = "asset_fur_qwerty"
    const val CSB_QWERTY_ID = "asset_csb_qwerty"
    const val HSB_QWERTZ_ID = "asset_hsb_qwertz"
    const val DSB_QWERTZ_ID = "asset_dsb_qwertz"
    const val GV_QWERTY_ID = "asset_gv_qwerty"
    const val KW_QWERTY_ID = "asset_kw_qwerty"
    const val RUP_QWERTY_ID = "asset_rup_qwerty"
    const val CRH_QWERTY_ID = "asset_crh_qwerty"
    const val OM_QWERTY_ID = "asset_om_qwerty"
    const val NSO_QWERTY_ID = "asset_nso_qwerty"
    const val TS_QWERTY_ID = "asset_ts_qwerty"
    const val VE_QWERTY_ID = "asset_ve_qwerty"
    const val SS_QWERTY_ID = "asset_ss_qwerty"
    const val ND_QWERTY_ID = "asset_nd_qwerty"
    const val LG_QWERTY_ID = "asset_lg_qwerty"
    const val KI_QWERTY_ID = "asset_ki_qwerty"
    const val AK_QWERTY_ID = "asset_ak_qwerty"
    const val EE_QWERTY_ID = "asset_ee_qwerty"
    const val BM_QWERTY_ID = "asset_bm_qwerty"
    const val AY_QWERTY_ID = "asset_ay_qwerty"
    const val TY_QWERTY_ID = "asset_ty_qwerty"
    const val BI_QWERTY_ID = "asset_bi_qwerty"
    const val TET_QWERTY_ID = "asset_tet_qwerty"
    const val IA_QWERTY_ID = "asset_ia_qwerty"
    const val JBO_QWERTY_ID = "asset_jbo_qwerty"
    const val TLH_QWERTY_ID = "asset_tlh_qwerty"
    const val TLH_PIQAD_ID = "asset_tlh_piqad"
    const val DV_THAANA_ID = "asset_dv_thaana"
    const val SE_QWERTY_ID = "asset_se_qwerty"
    const val SMN_QWERTY_ID = "asset_smn_qwerty"
    const val SMS_QWERTY_ID = "asset_sms_qwerty"
    const val SC_QWERTY_ID = "asset_sc_qwerty"
    const val PMS_QWERTY_ID = "asset_pms_qwerty"
    const val LLD_QWERTY_ID = "asset_lld_qwerty"
    const val NRF_QWERTY_ID = "asset_nrf_qwerty"
    const val HAC_STANDARD_ID = "asset_hac_standard"
    const val YI_STANDARD_ID = "asset_yi_standard"
    const val GRC_POLYTONIC_ID = "asset_grc_polytonic"
    const val ZGH_TIFINAGH_ID = "asset_zgh_tifinagh"
    const val CHR_CHEROKEE_ID = "asset_chr_cherokee"
    const val NQO_NKO_ID = "asset_nqo_nko"
    const val IU_SYLLABICS_ID = "asset_iu_syllabics"
    const val SYC_SYRIAC_ID = "asset_syc_syriac"
    const val BO_TIBETAN_ID = "asset_bo_tibetan"

    // --- Bengali-script reuse: same alphabet/keymap family as Bengali itself. ---
    const val AS_BENGALI_ID = "asset_as_bengali"
    const val BPY_BENGALI_ID = "asset_bpy_bengali"
    const val SYL_BENGALI_ID = "asset_syl_bengali"

    // --- Romanized variants: plain QWERTY, dictionary-only distinction. ---
    const val AR_ROM_ID = "asset_ar_rom"
    const val BN_ROM_ID = "asset_bn_rom"
    const val GU_ROM_ID = "asset_gu_rom"
    const val HI_ROM_ID = "asset_hi_rom"
    const val KN_ROM_ID = "asset_kn_rom"
    const val ML_ROM_ID = "asset_ml_rom"
    const val MR_ROM_ID = "asset_mr_rom"
    const val NE_ROM_ID = "asset_ne_rom"
    const val PA_ROM_ID = "asset_pa_rom"
    const val RU_ROM_ID = "asset_ru_rom"
    const val SI_ROM_ID = "asset_si_rom"
    const val TA_ROM_ID = "asset_ta_rom"
    const val TE_ROM_ID = "asset_te_rom"
    const val UR_ROM_ID = "asset_ur_rom"
    const val KOK_ROM_ID = "asset_kok_rom"

    // --- Composer-driven input methods (Vietnamese Telex/VNI, Japanese romaji,
    // Chinese pinyin): plain QWERTY grids whose "composer" field does the work. ---
    /** International Phonetic Alphabet: a phonetic-notation layout, not a language. */
    const val IPA_ID = "asset_ipa"

    // --- Notation layouts: like IPA, notations offered as pseudo-languages. ---
    /** Western musical notation symbols, drawn with the Noto Music face. */
    const val MUSIC_ID = "asset_music"

    /** Six-key chorded braille: dot keys feed the service's chord engine. */
    const val BRAILLE_CHORD_ID = "asset_braille_chord"

    /** Morse code: dot/dash keys feed the service's timing engine. */
    const val MORSE_ID = "asset_morse"

    const val VI_TELEX_ID = "asset_vi_telex"
    const val VI_VNI_ID = "asset_vi_vni"
    const val JA_ROMAJI_ID = "asset_ja_romaji"
    const val JA_FLICK_ID = "asset_ja_flick"
    const val JA_KANA_JIS_ID = "asset_ja_kana_jis"
    const val JA_GODAN_ID = "asset_ja_godan"
    const val JA_50KEYS_ID = "asset_ja_50keys"
    const val ZH_PINYIN_ID = "asset_zh_pinyin"
    const val ZH_PINYIN_CLEARFLOW_ID = "asset_zh_pinyin_clearflow"
    const val ZH_PINYIN_T9_ID = "asset_zh_pinyin_t9"
    const val ZH_ZHUYIN_ID = "asset_zh_zhuyin"

    /** Samsung's 12-key Zhuyin pad: ten keys of bopomofo, typed as digits (`T9ZhuyinComposer`). */
    const val ZH_ZHUYIN_T9_ID = "asset_sam_zh_zhuyin_t9"
    const val ZH_CANGJIE_ID = "asset_zh_cangjie"
    const val ZH_CANGJIE_QUICK_ID = "asset_zh_cangjie_quick"
    const val ZH_WUBI_ID = "asset_zh_wubi"
    const val YUE_JYUTPING_ID = "asset_yue_jyutping"
    const val YUE_YALE_ID = "asset_yue_yale"
    const val YUE_CANTONESE_PINYIN_ID = "asset_yue_cantonese_pinyin"
    const val ZH_STROKE_ID = "asset_zh_stroke"

    /** Samsung's 12-key stroke pad: the same `StrokeComposer`, with 通 as the wildcard stroke. */
    const val ZH_STROKE_PAD_ID = "asset_sam_zh_stroke_pad"

    // --- Handwriting: a writing canvas over one bottom row (issue #557). The
    // flag on the layout does the work; the grid is only ?123, globe, space,
    // delete and enter. ---
    const val JA_HANDWRITING_ID = "asset_ja_handwriting"
    const val ZH_HANDWRITING_ID = "asset_zh_handwriting"
    const val KO_HANDWRITING_ID = "asset_ko_handwriting"

    // --- Fancy Text: one plain QWERTY grid (the 𝔣𝔞𝔫𝔠𝔶 𝕦𝕟𝕚𝕔𝕠𝕕𝕖 trick). The
    // styled glyphs come from FancyStyles at draw and commit time, keyed by
    // the selected style — the layout data itself stays plain a–z, so the
    // compiled-layout caches never see a style change. The old per-style
    // asset_fancy_* ids are collapsed onto this one at read time; see
    // canonicalLayoutId. ---
    const val FANCY_ID = "asset_fancy"

    // --- Language expansion: 140 new languages across Latin/Cyrillic/
    // Devanagari/Arabic/reused-script/conlang families + Ol Chiki (Santali) +
    // Meetei Mayek (Manipuri). ---
    const val AA_ID = "asset_aa"
    const val ACE_ID = "asset_ace"
    const val AMI_ID = "asset_ami"
    const val AN_ID = "asset_an"
    const val ANN_ID = "asset_ann"
    const val AST_ID = "asset_ast"
    const val ATJ_ID = "asset_atj"
    const val BAN_ID = "asset_ban"
    const val BAR_ID = "asset_bar"
    const val SGS_ID = "asset_sgs"
    const val BBC_ID = "asset_bbc"
    const val BCL_ID = "asset_bcl"
    const val BEW_ID = "asset_bew"
    const val BJN_ID = "asset_bjn"
    const val BTM_ID = "asset_btm"
    const val BUG_ID = "asset_bug"
    const val CBK_ID = "asset_cbk"
    const val CH_ID = "asset_ch"
    const val CHY_ID = "asset_chy"
    const val CR_ID = "asset_cr"
    const val DAG_ID = "asset_dag"
    const val DGA_ID = "asset_dga"
    const val DIQ_ID = "asset_diq"
    const val DIN_ID = "asset_din"
    const val DTP_ID = "asset_dtp"
    const val EML_ID = "asset_eml"
    const val EXT_ID = "asset_ext"
    const val FAT_ID = "asset_fat"
    const val FF_ID = "asset_ff"
    const val VRO_ID = "asset_vro"
    const val FON_ID = "asset_fon"
    const val FRP_ID = "asset_frp"
    const val FRR_ID = "asset_frr"
    const val GAG_ID = "asset_gag"
    const val GCR_ID = "asset_gcr"
    const val GOR_ID = "asset_gor"
    const val GPE_ID = "asset_gpe"
    const val GSW_ID = "asset_gsw"
    const val GUC_ID = "asset_guc"
    const val GUW_ID = "asset_guw"
    const val GUR_ID = "asset_gur"
    const val HIF_ID = "asset_hif"
    const val IBA_ID = "asset_iba"
    const val IK_ID = "asset_ik"
    const val ILO_ID = "asset_ilo"
    const val JAM_ID = "asset_jam"
    const val KAB_ID = "asset_kab"
    const val KAJ_ID = "asset_kaj"
    const val KBP_ID = "asset_kbp"
    const val KCG_ID = "asset_kcg"
    const val KG_ID = "asset_kg"
    const val KL_ID = "asset_kl"
    const val KNC_ID = "asset_knc"
    const val KSH_ID = "asset_ksh"
    const val KUS_ID = "asset_kus"
    const val LAD_ID = "asset_lad"
    const val LI_ID = "asset_li"
    const val LTG_ID = "asset_ltg"
    const val MAD_ID = "asset_mad"
    const val MIN_ID = "asset_min"
    const val MOS_ID = "asset_mos"
    const val MWL_ID = "asset_mwl"
    const val NAH_ID = "asset_nah"
    const val NDS_ID = "asset_nds"
    const val NIA_ID = "asset_nia"
    const val NN_ID = "asset_nn"
    const val NR_ID = "asset_nr"
    const val NUP_ID = "asset_nup"
    const val NV_ID = "asset_nv"
    const val OLO_ID = "asset_olo"
    const val PAG_ID = "asset_pag"
    const val PAM_ID = "asset_pam"
    const val PAP_ID = "asset_pap"
    const val PCD_ID = "asset_pcd"
    const val PCM_ID = "asset_pcm"
    const val PDC_ID = "asset_pdc"
    const val PFL_ID = "asset_pfl"
    const val PPL_ID = "asset_ppl"
    const val PWN_ID = "asset_pwn"
    const val RMY_ID = "asset_rmy"
    const val SCO_ID = "asset_sco"
    const val SG_ID = "asset_sg"
    const val SRN_ID = "asset_srn"
    const val STQ_ID = "asset_stq"
    const val SZL_ID = "asset_szl"
    const val SZY_ID = "asset_szy"
    const val TAY_ID = "asset_tay"
    const val TDD_ID = "asset_tdd"
    const val TLY_ID = "asset_tly"
    const val TRV_ID = "asset_trv"
    const val TUM_ID = "asset_tum"
    const val VEP_ID = "asset_vep"
    const val VLS_ID = "asset_vls"
    const val WAR_ID = "asset_war"
    const val ZA_ID = "asset_za"
    const val ZEA_ID = "asset_zea"
    const val ALT_ID = "asset_alt"
    const val INH_ID = "asset_inh"
    const val KAA_ID = "asset_kaa"
    const val KOI_ID = "asset_koi"
    const val KRC_ID = "asset_krc"
    const val LBE_ID = "asset_lbe"
    const val MDF_ID = "asset_mdf"
    const val MRJ_ID = "asset_mrj"
    const val RSK_ID = "asset_rsk"
    const val RUE_ID = "asset_rue"
    const val ANP_ID = "asset_anp"
    const val AWA_ID = "asset_awa"
    const val DTY_ID = "asset_dty"
    const val NEW_ID = "asset_new"
    const val RAJ_ID = "asset_raj"
    const val ARY_ID = "asset_ary"
    const val ARZ_ID = "asset_arz"
    const val AZB_ID = "asset_azb"
    const val GLK_ID = "asset_glk"
    const val MZN_ID = "asset_mzn"
    const val KS_ID = "asset_ks"
    const val PNB_ID = "asset_pnb"
    const val SKR_ID = "asset_skr"
    const val BLK_ID = "asset_blk"
    const val MNW_ID = "asset_mnw"
    const val RKI_ID = "asset_rki"
    const val SHN_ID = "asset_shn"
    const val HYW_ID = "asset_hyw"
    const val PNT_ID = "asset_pnt"
    const val SHI_ID = "asset_shi"
    const val SHI_LATN_ID = "asset_shi_latn"
    const val TIG_ID = "asset_tig"
    const val TCY_ID = "asset_tcy"
    const val XMF_ID = "asset_xmf"
    const val DZ_ID = "asset_dz"
    const val AVK_ID = "asset_avk"
    const val IE_ID = "asset_ie"
    const val IO_ID = "asset_io"
    const val LFN_ID = "asset_lfn"
    const val NOV_ID = "asset_nov"
    const val QYA_ID = "asset_qya"
    const val TOK_ID = "asset_tok"
    const val VO_ID = "asset_vo"
    const val SAT_ID = "asset_sat"
    const val MNI_ID = "asset_mni"

    /** Ho in Warang Citi, U+118A0..118FF: the one shipped grid outside the BMP that we wrote ourselves. */
    const val HOC_WARANG_CITI_ID = "asset_hoc_warang_citi"

    // --- Second keymaps for languages that already ship one: national standards
    // (InScript, BDS, TS 2117, Remington), ergonomic alternatives and regional
    // variants. Each is a different arrangement of a script already supported,
    // not a new script. ---
    const val AR_HIJAI_ID = "asset_ar_hijai"
    const val AS_INSCRIPT_ID = "asset_as_inscript"
    const val BEM_QWERTY_ID = "asset_bem_qwerty"
    const val BG_BDS_ID = "asset_bg_bds"
    const val BN_BAISHAKHI_ID = "asset_bn_baishakhi"
    const val BN_BORNONA_ID = "asset_bn_bornona"
    const val BN_INSCRIPT_ID = "asset_bn_inscript"
    const val BN_MAYABI_ID = "asset_bn_mayabi"
    /** Samsung Keyboard's Bangla grid: the alphabet in order, vowel keys typing their kar after a consonant. */
    const val BN_SAMSUNG_ID = "asset_bn_samsung"
    /** KhiproTeam's Shörolipi (#803): Bangla in 26 Latin letters, ṛ ḍ ö ŋ ë ṭ in place of q w f z x v. */
    const val BN_ROM_SHOROLIPI_ID = "asset_bn_rom_shorolipi"
    const val BO_STANDARD_ID = "asset_bo_standard"
    const val CKB_STANDARD_ID = "asset_ckb_standard"
    const val DAR_URAKHI_ID = "asset_dar_urakhi"
    const val DE_NEO2_ID = "asset_de_neo2"
    const val DV_PHONETIC_ID = "asset_dv_phonetic"
    const val DZ_STANDARD_ID = "asset_dz_standard"
    const val EN_COLEMAK_DH_ID = "asset_en_colemak_dh"
    const val EN_NALMY_ID = "asset_en_nalmy"
    const val EN_SANGALINE_ID = "asset_en_sangaline"
    const val EN_QWERTZ_ID = "asset_en_qwertz"
    const val FR_CANADIAN_ID = "asset_fr_canadian"
    const val FR_SWISS_ID = "asset_fr_swiss"
    const val GAA_QWERTY_ID = "asset_gaa_qwerty"
    const val HI_REMINGTON_GAIL_ID = "asset_hi_remington_gail"
    const val ISV_ID = "asset_isv"
    const val KA_LEGACY_ID = "asset_ka_legacy"
    const val KN_KPRAO_ID = "asset_kn_kprao"

    /**
     * The two three-set (세벌식) Korean layouts. Unlike the built-in two-set
     * grid these emit *conjoining* jamo (U+1100 block) — a positional keyboard
     * has to, because it distinguishes initial ᄀ from final ᆨ and those are
     * different code points — and `HangulComposer` composes both blocks.
     */
    const val KO_SEBEOLSIK_390_ID = "asset_ko_sebeolsik_390"
    const val KO_SEBEOLSIK_FINAL_ID = "asset_ko_sebeolsik_final"

    /**
     * 천지인 (Cheonjiin), the Korean 12-key pad (discussion #372): vowels drawn
     * from the strokes ㅣ ㆍ ㅡ by `CheonjiinComposer`, consonants picked by
     * tapping their key again (`Key.multitap`).
     */
    const val KO_CHEONJIIN_ID = "asset_ko_cheonjiin"

    /**
     * 나랏글 (Naratgeul), the other Korean 12-key pad (issue #599): six basic
     * consonants and four vowel keys, with 획추가 and 쌍자음 building every
     * other jamo from them, and ㅐ ㅔ typed as ㅏ ㅓ and ㅣ (`NaratgeulComposer`).
     */
    const val KO_NARATGEUL_ID = "asset_ko_naratgeul"

    /** Samsung's Naratgeul with the consonants in the middle columns and punctuation down the left. */
    const val KO_NARATGEUL_CENTER_ID = "asset_sam_ko_naratgeul_center"

    /** Samsung's 천지인 플러스: Cheonjiin's strokes, with every consonant on a key of its own. */
    const val KO_CHEONJIIN_PLUS_ID = "asset_sam_ko_cheonjiin_plus"

    /** 베가 (Vega): a cycle of letters on every key, vowels included (`VegaComposer`). */
    const val KO_VEGA_ID = "asset_sam_ko_vega"

    /** Vega with the letters in the middle columns and punctuation down the left. */
    const val KO_VEGA_CENTER_ID = "asset_sam_ko_vega_center"

    /** 모아키 (Moakey): a consonant key dragged towards its vowel (`MoakeyComposer`, `MoakeyDrag`). */
    const val KO_MOAKEY_ID = "asset_sam_ko_moakey"

    /** Moakey with ㅣ ㅡ ㆍ keys, for a second thumb to add the vowel after a tapped consonant. */
    const val KO_MOAKEY_TWOHAND_ID = "asset_sam_ko_moakey_twohand"
    const val ML_INSCRIPT_ENHANCED_ID = "asset_ml_inscript_enhanced"
    const val MNI_INSCRIPT_ID = "asset_mni_inscript"
    const val MNS_ID = "asset_mns"
    const val NE_TRADITIONAL_ID = "asset_ne_traditional"
    const val OR_PHONETIC_ID = "asset_or_phonetic"
    const val PA_JHELUM_ID = "asset_pa_jhelum"
    const val RU_DIKTOR_ID = "asset_ru_diktor"
    const val SAT_INSCRIPT_ID = "asset_sat_inscript"
    const val SD_STANDARD_ID = "asset_sd_standard"
    const val SI_PHONETIC_ID = "asset_si_phonetic"
    const val SR_LATIN_ID = "asset_sr_latin"
    const val TA_INSCRIPT_ID = "asset_ta_inscript"
    const val TH_MANOONCHAI_ID = "asset_th_manoonchai"
    const val TH_PATTACHOTE_ID = "asset_th_pattachote"
    const val TR_TURKISHF_ID = "asset_tr_turkishf"
    const val UDM_EXTENDED_ID = "asset_udm_extended"
    const val UR_NLA_ID = "asset_ur_nla"

    // --- T9 keypads (issue #332): the English builtin_t9 grid with each
    // language's own letter groups, the way that language's phones printed
    // them. The letters a language adds ride on the key of their base letter
    // (ä with abc, ł with jkl), so the decoder reads them as that keystroke. ---
    const val DE_T9_ID = "asset_de_t9"
    const val FR_T9_ID = "asset_fr_t9"
    const val ES_T9_ID = "asset_es_t9"
    const val PT_T9_ID = "asset_pt_t9"
    const val IT_T9_ID = "asset_it_t9"
    const val NL_T9_ID = "asset_nl_t9"
    const val PL_T9_ID = "asset_pl_t9"
    const val TR_T9_ID = "asset_tr_t9"
    const val SV_T9_ID = "asset_sv_t9"
    const val DA_T9_ID = "asset_da_t9"
    const val NB_T9_ID = "asset_nb_t9"
    const val FI_T9_ID = "asset_fi_t9"
    const val CS_T9_ID = "asset_cs_t9"
    const val SK_T9_ID = "asset_sk_t9"
    const val RO_T9_ID = "asset_ro_t9"
    const val HU_T9_ID = "asset_hu_t9"
    const val HR_T9_ID = "asset_hr_t9"
    const val ID_T9_ID = "asset_id_t9"
    const val MS_T9_ID = "asset_ms_t9"
    const val RU_T9_ID = "asset_ru_t9"
    const val UK_T9_ID = "asset_uk_t9"
    const val BG_T9_ID = "asset_bg_t9"
    const val EL_T9_ID = "asset_el_t9"
    const val HE_T9_ID = "asset_he_t9"
    const val AR_T9_ID = "asset_ar_t9"
    const val FA_T9_ID = "asset_fa_t9"
    const val SL_T9_ID = "asset_sl_t9"
    const val LT_T9_ID = "asset_lt_t9"
    const val LV_T9_ID = "asset_lv_t9"
    const val ET_T9_ID = "asset_et_t9"
    const val IS_T9_ID = "asset_is_t9"
    const val CA_T9_ID = "asset_ca_t9"
    const val GL_T9_ID = "asset_gl_t9"
    const val EU_T9_ID = "asset_eu_t9"
    const val SQ_T9_ID = "asset_sq_t9"
    const val AF_T9_ID = "asset_af_t9"
    const val SW_T9_ID = "asset_sw_t9"
    const val TL_T9_ID = "asset_tl_t9"
    const val AZ_T9_ID = "asset_az_t9"
    const val CY_T9_ID = "asset_cy_t9"
    const val GA_T9_ID = "asset_ga_t9"
    const val MT_T9_ID = "asset_mt_t9"
    const val SR_T9_ID = "asset_sr_t9"
    const val MK_T9_ID = "asset_mk_t9"
    const val BE_T9_ID = "asset_be_t9"
    const val KK_T9_ID = "asset_kk_t9"
    const val HY_T9_ID = "asset_hy_t9"
    const val KA_T9_ID = "asset_ka_t9"
    const val UR_T9_ID = "asset_ur_t9"
    const val TK_T9_ID = "asset_tk_t9"
    const val HT_T9_ID = "asset_ht_t9"
    const val MG_T9_ID = "asset_mg_t9"
    const val SO_T9_ID = "asset_so_t9"
    const val ZU_T9_ID = "asset_zu_t9"
    const val XH_T9_ID = "asset_xh_t9"
    const val IG_T9_ID = "asset_ig_t9"
    const val HA_T9_ID = "asset_ha_t9"
    const val MI_T9_ID = "asset_mi_t9"
    const val EO_T9_ID = "asset_eo_t9"
    const val LA_T9_ID = "asset_la_t9"
    const val LB_T9_ID = "asset_lb_t9"
    const val FY_T9_ID = "asset_fy_t9"
    const val FO_T9_ID = "asset_fo_t9"
    const val OC_T9_ID = "asset_oc_t9"
    const val BR_T9_ID = "asset_br_t9"
    const val CO_T9_ID = "asset_co_t9"
    const val KY_T9_ID = "asset_ky_t9"
    const val TG_T9_ID = "asset_tg_t9"
    const val MN_T9_ID = "asset_mn_t9"
    const val TT_T9_ID = "asset_tt_t9"
    const val BA_T9_ID = "asset_ba_t9"
    const val PS_T9_ID = "asset_ps_t9"
    const val CKB_T9_ID = "asset_ckb_t9"
    const val UG_T9_ID = "asset_ug_t9"
    const val AB_T9_ID = "asset_ab_t9"
    const val HYW_T9_ID = "asset_hyw_t9"
    const val GAG_T9_ID = "asset_gag_t9"
    const val NN_T9_ID = "asset_nn_t9"
    const val RM_T9_ID = "asset_rm_t9"
    const val RMY_T9_ID = "asset_rmy_t9"
    const val HSB_T9_ID = "asset_hsb_t9"
    const val DSB_T9_ID = "asset_dsb_t9"
    const val DIQ_T9_ID = "asset_diq_t9"
    const val FUR_T9_ID = "asset_fur_t9"
    const val KL_T9_ID = "asset_kl_t9"
    const val CSB_T9_ID = "asset_csb_t9"
    const val LLD_T9_ID = "asset_lld_t9"
    const val LI_T9_ID = "asset_li_t9"
    const val RUP_T9_ID = "asset_rup_t9"
    const val MWL_T9_ID = "asset_mwl_t9"
    const val PMS_T9_ID = "asset_pms_t9"
    const val RUE_T9_ID = "asset_rue_t9"
    const val RSK_T9_ID = "asset_rsk_t9"
    const val GD_T9_ID = "asset_gd_t9"
    const val SCN_T9_ID = "asset_scn_t9"
    const val WA_T9_ID = "asset_wa_t9"
    const val PCD_T9_ID = "asset_pcd_t9"
    const val BAR_T9_ID = "asset_bar_t9"
    const val GSW_T9_ID = "asset_gsw_t9"
    const val KSH_T9_ID = "asset_ksh_t9"
    const val PDC_T9_ID = "asset_pdc_t9"
    const val PFL_T9_ID = "asset_pfl_t9"
    const val NDS_T9_ID = "asset_nds_t9"
    const val FRR_T9_ID = "asset_frr_t9"
    const val STQ_T9_ID = "asset_stq_t9"
    const val VLS_T9_ID = "asset_vls_t9"
    const val ZEA_T9_ID = "asset_zea_t9"
    const val FRP_T9_ID = "asset_frp_t9"
    const val NRF_T9_ID = "asset_nrf_t9"
    const val GCR_T9_ID = "asset_gcr_t9"
    const val AST_T9_ID = "asset_ast_t9"
    const val AN_T9_ID = "asset_an_t9"
    const val EXT_T9_ID = "asset_ext_t9"
    const val LAD_T9_ID = "asset_lad_t9"
    const val CBK_T9_ID = "asset_cbk_t9"
    const val QU_T9_ID = "asset_qu_t9"
    const val AY_T9_ID = "asset_ay_t9"
    const val PAP_T9_ID = "asset_pap_t9"
    const val TET_T9_ID = "asset_tet_t9"
    const val EML_T9_ID = "asset_eml_t9"
    const val VEC_T9_ID = "asset_vec_t9"
    const val LIJ_T9_ID = "asset_lij_t9"
    const val SC_T9_ID = "asset_sc_t9"
    const val SE_T9_ID = "asset_se_t9"
    const val VRO_T9_ID = "asset_vro_t9"
    const val VEP_T9_ID = "asset_vep_t9"
    const val LTG_T9_ID = "asset_ltg_t9"
    const val SGS_T9_ID = "asset_sgs_t9"
    const val CRH_T9_ID = "asset_crh_t9"
    const val ADY_T9_ID = "asset_ady_t9"
    const val ALT_T9_ID = "asset_alt_t9"
    const val AV_T9_ID = "asset_av_t9"
    const val BUA_T9_ID = "asset_bua_t9"
    const val CE_T9_ID = "asset_ce_t9"
    const val CHM_T9_ID = "asset_chm_t9"
    const val CV_T9_ID = "asset_cv_t9"
    const val INH_T9_ID = "asset_inh_t9"
    const val KAA_T9_ID = "asset_kaa_t9"
    const val KBD_T9_ID = "asset_kbd_t9"
    const val KOI_T9_ID = "asset_koi_t9"
    const val KRC_T9_ID = "asset_krc_t9"
    const val KV_T9_ID = "asset_kv_t9"
    const val LBE_T9_ID = "asset_lbe_t9"
    const val LEZ_T9_ID = "asset_lez_t9"
    const val MDF_T9_ID = "asset_mdf_t9"
    const val MRJ_T9_ID = "asset_mrj_t9"
    const val MYV_T9_ID = "asset_myv_t9"
    const val OS_T9_ID = "asset_os_t9"
    const val SAH_T9_ID = "asset_sah_t9"
    const val TYV_T9_ID = "asset_tyv_t9"
    const val UDM_T9_ID = "asset_udm_t9"
    const val XAL_T9_ID = "asset_xal_t9"
    const val AZB_T9_ID = "asset_azb_t9"
    const val GLK_T9_ID = "asset_glk_t9"
    const val MZN_T9_ID = "asset_mzn_t9"
    const val ARY_T9_ID = "asset_ary_t9"
    const val SD_T9_ID = "asset_sd_t9"
    const val SKR_T9_ID = "asset_skr_t9"
    const val PNB_T9_ID = "asset_pnb_t9"
    const val XMF_T9_ID = "asset_xmf_t9"
    const val ACE_T9_ID = "asset_ace_t9"
    const val AK_T9_ID = "asset_ak_t9"
    const val AMI_T9_ID = "asset_ami_t9"
    const val AR_ROM_T9_ID = "asset_ar_rom_t9"
    const val ATJ_T9_ID = "asset_atj_t9"
    const val AVK_T9_ID = "asset_avk_t9"
    const val BAN_T9_ID = "asset_ban_t9"
    const val BBC_T9_ID = "asset_bbc_t9"
    const val BCL_T9_ID = "asset_bcl_t9"
    const val BEW_T9_ID = "asset_bew_t9"
    const val BI_T9_ID = "asset_bi_t9"
    const val BJN_T9_ID = "asset_bjn_t9"
    const val BM_T9_ID = "asset_bm_t9"
    const val BN_ROM_T9_ID = "asset_bn_rom_t9"
    const val BTM_T9_ID = "asset_btm_t9"
    const val BUG_T9_ID = "asset_bug_t9"
    const val CEB_T9_ID = "asset_ceb_t9"
    const val CH_T9_ID = "asset_ch_t9"
    const val CHY_T9_ID = "asset_chy_t9"
    const val DAG_T9_ID = "asset_dag_t9"
    const val DGA_T9_ID = "asset_dga_t9"
    const val DTP_T9_ID = "asset_dtp_t9"
    const val EE_T9_ID = "asset_ee_t9"
    const val FAT_T9_ID = "asset_fat_t9"
    const val FF_T9_ID = "asset_ff_t9"
    const val FJ_T9_ID = "asset_fj_t9"
    const val FON_T9_ID = "asset_fon_t9"
    const val GOR_T9_ID = "asset_gor_t9"
    const val GPE_T9_ID = "asset_gpe_t9"
    const val GU_ROM_T9_ID = "asset_gu_rom_t9"
    const val GUC_T9_ID = "asset_guc_t9"
    const val GUR_T9_ID = "asset_gur_t9"
    const val GUW_T9_ID = "asset_guw_t9"
    const val GV_T9_ID = "asset_gv_t9"
    const val HAW_T9_ID = "asset_haw_t9"
    const val HI_ROM_T9_ID = "asset_hi_rom_t9"
    const val HIF_T9_ID = "asset_hif_t9"
    const val IA_T9_ID = "asset_ia_t9"
    const val IBA_T9_ID = "asset_iba_t9"
    const val IE_T9_ID = "asset_ie_t9"
    const val IK_T9_ID = "asset_ik_t9"
    const val ILO_T9_ID = "asset_ilo_t9"
    const val IO_T9_ID = "asset_io_t9"
    const val JAM_T9_ID = "asset_jam_t9"
    const val JV_T9_ID = "asset_jv_t9"
    const val KAB_T9_ID = "asset_kab_t9"
    const val KAJ_T9_ID = "asset_kaj_t9"
    const val KBP_T9_ID = "asset_kbp_t9"
    const val KG_T9_ID = "asset_kg_t9"
    const val KI_T9_ID = "asset_ki_t9"
    const val KN_ROM_T9_ID = "asset_kn_rom_t9"
    const val KNC_T9_ID = "asset_knc_t9"
    const val KUS_T9_ID = "asset_kus_t9"
    const val KW_T9_ID = "asset_kw_t9"
    const val LFN_T9_ID = "asset_lfn_t9"
    const val LG_T9_ID = "asset_lg_t9"
    const val LN_T9_ID = "asset_ln_t9"
    const val MAD_T9_ID = "asset_mad_t9"
    const val MIN_T9_ID = "asset_min_t9"
    const val ML_ROM_T9_ID = "asset_ml_rom_t9"
    const val MOS_T9_ID = "asset_mos_t9"
    const val MR_ROM_T9_ID = "asset_mr_rom_t9"
    const val NAH_T9_ID = "asset_nah_t9"
    const val ND_T9_ID = "asset_nd_t9"
    const val NE_ROM_T9_ID = "asset_ne_rom_t9"
    const val NIA_T9_ID = "asset_nia_t9"
    const val NOV_T9_ID = "asset_nov_t9"
    const val NR_T9_ID = "asset_nr_t9"
    const val NSO_T9_ID = "asset_nso_t9"
    const val NUP_T9_ID = "asset_nup_t9"
    const val NY_T9_ID = "asset_ny_t9"
    const val OM_T9_ID = "asset_om_t9"
    const val PA_ROM_T9_ID = "asset_pa_rom_t9"
    const val PAG_T9_ID = "asset_pag_t9"
    const val PCM_T9_ID = "asset_pcm_t9"
    const val PPL_T9_ID = "asset_ppl_t9"
    const val PWN_T9_ID = "asset_pwn_t9"
    const val QYA_T9_ID = "asset_qya_t9"
    const val RU_ROM_T9_ID = "asset_ru_rom_t9"
    const val SCO_T9_ID = "asset_sco_t9"
    const val SG_T9_ID = "asset_sg_t9"
    const val SI_ROM_T9_ID = "asset_si_rom_t9"
    const val SM_T9_ID = "asset_sm_t9"
    const val SN_T9_ID = "asset_sn_t9"
    const val SRN_T9_ID = "asset_srn_t9"
    const val SS_T9_ID = "asset_ss_t9"
    const val ST_T9_ID = "asset_st_t9"
    const val SU_T9_ID = "asset_su_t9"
    const val SZL_T9_ID = "asset_szl_t9"
    const val SZY_T9_ID = "asset_szy_t9"
    const val TA_ROM_T9_ID = "asset_ta_rom_t9"
    const val TAY_T9_ID = "asset_tay_t9"
    const val TE_ROM_T9_ID = "asset_te_rom_t9"
    const val TN_T9_ID = "asset_tn_t9"
    const val TO_T9_ID = "asset_to_t9"
    const val TOK_T9_ID = "asset_tok_t9"
    const val TRV_T9_ID = "asset_trv_t9"
    const val TS_T9_ID = "asset_ts_t9"
    const val TUM_T9_ID = "asset_tum_t9"
    const val TY_T9_ID = "asset_ty_t9"
    const val UR_ROM_T9_ID = "asset_ur_rom_t9"
    const val KOK_ROM_T9_ID = "asset_kok_rom_t9"
    const val UZ_T9_ID = "asset_uz_t9"
    const val VE_T9_ID = "asset_ve_t9"
    const val VO_T9_ID = "asset_vo_t9"
    const val WAR_T9_ID = "asset_war_t9"
    const val WO_T9_ID = "asset_wo_t9"
    const val ZA_T9_ID = "asset_za_t9"
    const val ZGH_T9_ID = "asset_zgh_t9"

    // Scripts spelled with combining marks: the Indian-market plan and the
    // keypads that follow it, Thai, Lao, Cherokee and tone-marked Latin (#332).
    const val HI_T9_ID = "asset_hi_t9"
    const val MR_T9_ID = "asset_mr_t9"
    const val NE_T9_ID = "asset_ne_t9"
    const val SA_T9_ID = "asset_sa_t9"
    const val MAI_T9_ID = "asset_mai_t9"
    const val BHO_T9_ID = "asset_bho_t9"
    const val AWA_T9_ID = "asset_awa_t9"
    const val ANP_T9_ID = "asset_anp_t9"
    const val BRX_T9_ID = "asset_brx_t9"
    const val DOI_T9_ID = "asset_doi_t9"
    const val DTY_T9_ID = "asset_dty_t9"
    const val NEW_T9_ID = "asset_new_t9"
    const val BN_T9_ID = "asset_bn_t9"
    const val AS_T9_ID = "asset_as_t9"
    const val BPY_T9_ID = "asset_bpy_t9"
    const val TA_T9_ID = "asset_ta_t9"
    const val SYL_T9_ID = "asset_syl_t9"
    const val PA_T9_ID = "asset_pa_t9"
    const val GU_T9_ID = "asset_gu_t9"
    const val OR_T9_ID = "asset_or_t9"
    const val TE_T9_ID = "asset_te_t9"
    const val KN_T9_ID = "asset_kn_t9"
    const val TCY_T9_ID = "asset_tcy_t9"
    const val ML_T9_ID = "asset_ml_t9"
    const val SI_T9_ID = "asset_si_t9"
    const val SAT_T9_ID = "asset_sat_t9"
    const val MY_T9_ID = "asset_my_t9"
    const val RKI_T9_ID = "asset_rki_t9"
    const val KM_T9_ID = "asset_km_t9"
    const val TH_T9_ID = "asset_th_t9"
    const val LO_T9_ID = "asset_lo_t9"
    const val CHR_T9_ID = "asset_chr_t9"
    const val VI_T9_ID = "asset_vi_t9"
    const val YO_T9_ID = "asset_yo_t9"
    const val GN_T9_ID = "asset_gn_t9"
    const val DIN_T9_ID = "asset_din_t9"
    const val KCG_T9_ID = "asset_kcg_t9"
    const val ANN_T9_ID = "asset_ann_t9"
    const val TLH_T9_ID = "asset_tlh_t9"
    const val YI_T9_ID = "asset_yi_t9"
    const val PNT_T9_ID = "asset_pnt_t9"

    // Languages whose word list was in another script until it was rebuilt:
    // Kurmanji (Turkish table), Konkani (Indian-market plan), Tachelhit in
    // Tifinagh and in Latin, Talysh (Azerbaijani table).
    const val KU_T9_ID = "asset_ku_t9"
    const val KOK_T9_ID = "asset_kok_t9"
    const val SHI_T9_ID = "asset_shi_t9"
    const val SHI_LATN_T9_ID = "asset_shi_latn_t9"
    const val TLY_T9_ID = "asset_tly_t9"

    // --- Parity with HeliBoard, FlorisBoard and FUTO Keyboard: every layout
    // those ship that had no counterpart here. Converted from their files with
    // the importers in this package, then given this app's shift, delete and
    // bottom rows. Hebrew SI-1452, ClearFlow and KASROZ are written by hand. ---
    const val ABQ_CYRILLIC_ID = "asset_abq_cyrillic"
    const val AGX_CYRILLIC_ID = "asset_agx_cyrillic"
    const val AK_LETTERS_ID = "asset_ak_letters"
    const val ALT_LETTER_ROW_ID = "asset_alt_letter_row"
    const val AR_LETTERS_ID = "asset_ar_letters"
    const val AR_LULUA_ID = "asset_ar_lulua"
    const val BA_LETTER_ROW_ID = "asset_ba_letter_row"
    const val BE_LATIN_ID = "asset_be_latin"
    const val BE_LATIN_ROW_ID = "asset_be_latin_row"
    const val BEM_LETTERS_ID = "asset_bem_letters"
    const val BG_DVORAK_ID = "asset_bg_dvorak"
    const val BN_AKKHOR_ID = "asset_bn_akkhor"
    const val BN_OLD_ID = "asset_bn_old"
    const val BN_UNIJOY_ID = "asset_bn_unijoy"
    const val CHM_LETTER_ROW_ID = "asset_chm_letter_row"
    const val CKB_ALT_ID = "asset_ckb_alt"
    const val CKB_FOUR_ROWS_ID = "asset_ckb_four_rows"
    const val CKB_PHONETIC_ID = "asset_ckb_phonetic"
    const val CV_LETTER_ROW_ID = "asset_cv_letter_row"
    const val DAG_LETTERS_ID = "asset_dag_letters"
    const val DE_BONE_ID = "asset_de_bone"
    const val DE_DVORAK_ID = "asset_de_dvorak"
    const val EE_LETTERS_ID = "asset_ee_letters"
    const val EN_CLEARFLOW_ID = "asset_en_clearflow"
    const val EN_COLEMAK_DH_ANSI_ID = "asset_en_colemak_dh_ansi"
    const val EN_GBOARD_PLUS_ID = "asset_en_gboard_plus"
    const val EN_GBOARD_PLUS_SWIPE_SAFE_ID = "asset_en_gboard_plus_swipe_safe"
    const val EN_KASROZ_ID = "asset_en_kasroz"
    const val EN_SHAVIAN_ID = "asset_en_shavian"
    const val EN_SHAVIAN_TWO_LAYER_ID = "asset_en_shavian_two_layer"
    const val ENF_CYRILLIC_ID = "asset_enf_cyrillic"
    const val ENH_CYRILLIC_ID = "asset_enh_cyrillic"
    const val EO_LETTERS_ID = "asset_eo_letters"
    const val ES_DVORAK_ID = "asset_es_dvorak"
    const val GAG_LETTER_ROW_ID = "asset_gag_letter_row"
    const val GU_ALPHABETIC_ID = "asset_gu_alphabetic"
    const val HA_LETTERS_ID = "asset_ha_letters"
    const val HE_GBOARD_PLUS_ID = "asset_he_gboard_plus"
    const val HE_GBOARD_PLUS_SWIPE_SAFE_ID = "asset_he_gboard_plus_swipe_safe"
    const val HE_SI1452_ID = "asset_he_si1452"
    const val HI_COMPACT_ID = "asset_hi_compact"
    /** Devanagari on the keys, four pages of them, as Gboard lays it out (#498). */
    const val HI_DEVANAGARI_ID = "asset_hi_devanagari"
    const val HI_PHONETIC_KEYS_ID = "asset_hi_phonetic_keys"
    const val HOC_WARANG_CITI_ALT_ID = "asset_hoc_warang_citi_alt"
    const val HR_LETTERS_ID = "asset_hr_letters"
    const val HU_ACCENT_ROW_ID = "asset_hu_accent_row"
    const val HY_PHONETIC_ALT_ID = "asset_hy_phonetic_alt"
    const val IPA_QWERTY_ID = "asset_ipa_qwerty"
    const val IPA_QWERTY_G_ID = "asset_ipa_qwerty_g"
    const val IT_SWISS_ID = "asset_it_swiss"
    const val IZH_LATIN_ID = "asset_izh_latin"
    const val KA_STANDARD_ID = "asset_ka_standard"
    const val KAB_AZERTY_ID = "asset_kab_azerty"
    const val KI_LETTERS_ID = "asset_ki_letters"
    const val KK_LATIN_ID = "asset_kk_latin"
    const val KK_LETTER_ROW_ID = "asset_kk_letter_row"
    const val KM_FOUR_ROWS_ID = "asset_km_four_rows"
    const val KN_EXTENDED_ID = "asset_kn_extended"
    const val KO_DANMOEUM_ID = "asset_ko_danmoeum"
    const val KO_PHONETIC_ID = "asset_ko_phonetic"
    const val KRL_LATIN_ID = "asset_krl_latin"
    const val KS_LATIN_ID = "asset_ks_latin"
    const val KV_LETTER_ROW_ID = "asset_kv_letter_row"
    const val LG_LETTERS_ID = "asset_lg_letters"
    const val LKT_LATIN_ID = "asset_lkt_latin"
    const val LT_LETTER_ROW_ID = "asset_lt_letter_row"
    const val LUD_LATIN_ID = "asset_lud_latin"
    const val ML_ALPHABETIC_ID = "asset_ml_alphabetic"
    const val MNS_LETTER_ROW_ID = "asset_mns_letter_row"
    const val MR_ALPHABETIC_ID = "asset_mr_alphabetic"
    const val MRJ_LETTER_ROW_ID = "asset_mrj_letter_row"
    const val MY_LETTER_ROW_ID = "asset_my_letter_row"
    const val NEW_NEWA_ROMANIZED_ID = "asset_new_newa_romanized"
    const val NEW_NEWA_TRADITIONAL_ID = "asset_new_newa_traditional"
    const val NIO_CYRILLIC_ID = "asset_nio_cyrillic"
    const val PA_ALPHABETIC_ID = "asset_pa_alphabetic"
    const val RO_LETTERS_ID = "asset_ro_letters"
    const val RU_STUDENT_ID = "asset_ru_student"
    const val RU_YAVERT_ID = "asset_ru_yavert"
    const val RU_YAZHERT_ID = "asset_ru_yazhert"
    const val RUE_LETTERS_ID = "asset_rue_letters"
    const val RUE_PHONETIC_ID = "asset_rue_phonetic"
    const val RUT_CYRILLIC_ID = "asset_rut_cyrillic"
    const val SAH_LETTER_ROW_ID = "asset_sah_letter_row"
    const val SE_LETTER_ROW_ID = "asset_se_letter_row"
    const val SJE_LATIN_ID = "asset_sje_latin"
    const val SJU_LATIN_ID = "asset_sju_latin"
    const val SL_LETTERS_ID = "asset_sl_letters"
    const val SL_QWERTY_ID = "asset_sl_qwerty"
    const val SMA_LATIN_ID = "asset_sma_latin"
    const val SMJ_LATIN_ID = "asset_smj_latin"
    const val SMN_LETTER_ROW_ID = "asset_smn_letter_row"
    const val SR_LATIN_LETTERS_ID = "asset_sr_latin_letters"
    const val SV_DVORAK_ID = "asset_sv_dvorak"
    const val TA_ALPHABETIC_ID = "asset_ta_alphabetic"
    const val TAB_CYRILLIC_ID = "asset_tab_cyrillic"
    const val TG_LETTER_ROW_ID = "asset_tg_letter_row"
    const val TKR_CYRILLIC_ID = "asset_tkr_cyrillic"
    const val TOK_COMPACT_ID = "asset_tok_compact"
    const val TT_LETTER_ROW_ID = "asset_tt_letter_row"
    const val TYV_LETTER_ROW_ID = "asset_tyv_letter_row"
    const val UG_FULL_ID = "asset_ug_full"
    const val UG_PHONETIC_ID = "asset_ug_phonetic"
    const val UR_PHONETIC_FOUR_ROWS_ID = "asset_ur_phonetic_four_rows"
    const val VEP_LETTER_ROW_ID = "asset_vep_letter_row"
    const val VOT_LATIN_ID = "asset_vot_latin"
    const val XDQ_KAITAG_ID = "asset_xdq_kaitag"
    const val XDQ_KAITAG_4ROW_ID = "asset_xdq_kaitag_4row"
    const val YRK_CYRILLIC_ID = "asset_yrk_cyrillic"
    const val ZGH_LATIN_AZERTY_ID = "asset_zgh_latin_azerty"
    const val ZGH_LATIN_QWERTY_ID = "asset_zgh_latin_qwerty"
    const val ZGH_LATIN_QWERTZ_ID = "asset_zgh_latin_qwertz"
    const val ZGH_TIFINAGH_ALT_ID = "asset_zgh_tifinagh_alt"
    const val ZGH_TIFINAGH_AZERTY_ID = "asset_zgh_tifinagh_azerty"

    /**
     * One shipped layout as the index describes it, without its grid. [name]
     * is empty when the index was missing and the file has not been read.
     */
    class Entry(
        val id: String,
        val name: String,
        val langId: String,
        /** The Keyman keyboard whose rules the layout runs, when it has one. */
        val keyman: KeymanBinding? = null,
        /**
         * Whether the grid is a desktop keyboard's, number row and all, rather
         * than one drawn for a phone. The build measures it (see
         * `generateLayoutIndex`), so asking costs no parse.
         */
        val desktop: Boolean = false,
    )

    @Volatile private var assets: AssetManager? = null
    @Volatile private var entries: List<Entry> = emptyList()
    @Volatile private var entryById: Map<String, Entry> = emptyMap()
    @Volatile private var loaded = false

    /** A parsed layout and what holding on to it is estimated to cost. */
    private class Parsed(val spec: LayoutSpec, val weightBytes: Long)

    /**
     * Parsed layouts, most recently used last. Bounded because the settings
     * app can walk through a great many of them (every language's preview
     * cards), and the keyboard needs only the few that are switched on: those
     * are asked for on every field focus, so as the most recently used they
     * never age out.
     *
     * Bounded by estimated **bytes**, not by entry count. A count was the wrong
     * unit by three orders of magnitude at the top of the range: these files
     * are a couple of kilobytes each for nearly every layout, but the converted
     * Ethiopic keyboards run from 500 KB to 2.2 MB of JSON apiece and there are
     * a dozen of them, so the old ceiling of 48 entries permitted well over a
     * hundred megabytes of parsed keys — in the keyboard's process, which the
     * settings app shares, and which is the process that has to survive on a
     * low-memory phone. Scrolling one language's layout cards was enough.
     *
     * The weight is the source length times [PARSED_WEIGHT_FACTOR] rather than
     * a real measurement, because there is no cheap way to size an object graph
     * on Android and the source length is already in hand at the only place
     * that matters. It is a proportionality, not an accounting: what it has to
     * get right is that the Amharic grid counts for a thousand times the French
     * one, and it does.
     */
    private val parsed = LinkedHashMap<String, Parsed>(16, 0.75f, true)

    /** The sum of [Parsed.weightBytes] over [parsed]. Guarded by `parsed`. */
    private var parsedWeight = 0L

    /**
     * Evicts least-recently-used entries until the estimate is back inside the
     * budget, never touching [keep] — the entry the caller is about to return,
     * which must survive even when it is larger than the whole budget by
     * itself. Must be called holding `parsed`.
     */
    private fun trimParsed(keep: String) {
        if (parsedWeight <= parsedBudget) return
        // Over the entries, and reading the weight off the entry rather than
        // through `parsed[id]`: this map is access-ordered, so a `get` is a
        // structural change to it and bumps modCount — it would throw
        // ConcurrentModificationException on the next step of this very loop.
        val stale = parsed.entries.iterator()
        while (parsedWeight > parsedBudget && stale.hasNext()) {
            val entry = stale.next()
            if (entry.key == keep) continue
            parsedWeight -= entry.value.weightBytes
            stale.remove()
        }
    }

    /**
     * Bumped once, when [load] publishes the index. Callers that cache
     * anything derived from the shipped set key on it, so a cache built during
     * the window before the index is read — the first frames after a cold
     * start — is discarded rather than serving a list that is missing every
     * asset layout for the rest of the process's life.
     */
    @Volatile
    var generation: Int = 0
        private set

    /** Every shipped asset layout, in file order, as the index names it. Empty before [load]. */
    val index: List<Entry> get() = entries

    /** Whether [id] names a shipped asset layout. No parse. */
    fun isShipped(id: String): Boolean = id in entryById

    /** [id]'s index entry, when the index was read and names it. No parse. */
    fun entry(id: String): Entry? = entryById[id]?.takeIf { it.name.isNotEmpty() }

    /**
     * [id]'s display name, from the index — no parse, unless the index was
     * missing. Null for an id this build does not ship.
     */
    fun nameOf(id: String): String? {
        val entry = entryById[id] ?: return null
        return entry.name.ifEmpty { byId(id)?.name.orEmpty() }
    }

    /**
     * The shipped asset layout with this id, or null. The [BuiltInLayouts.byId]
     * counterpart.
     *
     * Parses the file on first use. That is one small file for nearly every
     * layout, but it is still I/O, so the paths that can run before the first
     * frame warm the ones they will need off the main thread ([warm]).
     */
    fun byId(id: String): LayoutSpec? {
        if (id !in entryById) return null
        synchronized(parsed) { parsed[id] }?.let { return it.spec }
        val manager = assets ?: return null
        val loaded = runCatching {
            val stem = id.removePrefix(ID_PREFIX)
            val text = KeymanLayoutPack.read(stem, SUFFIX)
                ?: manager.open("$DIR/$stem$SUFFIX").use { it.readBytes().decodeToString() }
            LayoutFile.decode(text)?.layout?.let {
                // Most of these files came from keyboards that put no digits on
                // the letters; give them QWERTY's corner hints (DigitHints).
                Parsed(it.withDigitHints(), text.length.toLong() * PARSED_WEIGHT_FACTOR)
            }
        }.getOrNull() ?: return null
        // Two threads racing the same miss both parse; the first one in wins,
        // so every caller ends up holding the same instance.
        return synchronized(parsed) {
            val winner = parsed.getOrPut(id) {
                parsedWeight += loaded.weightBytes
                loaded
            }
            trimParsed(keep = id)
            winner.spec
        }
    }

    /** Drops the parsed grids that came from a pack, which a new pack has just replaced. */
    internal fun forgetPacked() {
        synchronized(parsed) {
            val stale = parsed.entries.iterator()
            while (stale.hasNext()) {
                val entry = stale.next()
                if (entry.key.startsWith(KeymanLayoutPack.LAYOUT_ID_PREFIX)) {
                    parsedWeight -= entry.value.weightBytes
                    stale.remove()
                }
            }
        }
    }

    /** Parses [ids] now, on the calling thread, so a later [byId] is a lookup. */
    fun warm(ids: Iterable<String>) {
        for (id in ids) if (isShipped(id)) byId(id)
    }

    /**
     * Reads the index of the shipped layouts. Idempotent and cheap — one small
     * asset — but still I/O, so call it off the main thread. Falls back to
     * listing the folder when the index is missing (a build that skipped the
     * generator), in which case names come from the files as they are parsed.
     */
    fun load(assets: AssetManager) {
        if (loaded) return
        this.assets = assets
        KeymanLayoutPack.load(assets)
        val fromIndex = runCatching {
            assets.open(INDEX).bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size < 3 || parts[0].isEmpty()) {
                        null
                    } else {
                        val keyman = parts.getOrNull(3)?.takeIf { it.isNotEmpty() }
                            ?.let { KeymanBinding(it, parts.getOrNull(4).orEmpty()) }
                        Entry(parts[0], parts[1], parts[2], keyman, desktop = parts.getOrNull(5) == "1")
                    }
                }.toList()
            }
        }.getOrNull()
        val loadedEntries = fromIndex ?: runCatching { assets.list(DIR)?.asList() }.getOrNull().orEmpty()
            .filter { it.endsWith(SUFFIX) }
            .sorted()
            .map { name ->
                val id = ID_PREFIX + name.removeSuffix(SUFFIX)
                Entry(id, "", "")
            }
        // Map before list, as readers of each expect the other to be there.
        entryById = loadedEntries.associateBy { it.id }
        entries = loadedEntries
        generation++
        loaded = true
    }

    /** Every file under `assets/layouts` is this prefix plus its file stem. */
    const val ID_PREFIX = "asset_"

    /**
     * The build-generated index: `id<TAB>name<TAB>langId<TAB>keymanId<TAB>keymanVersion<TAB>desktop`
     * per line, the Keyman pair empty for a layout with no Keyman rules and
     * `desktop` either `1` or empty.
     */
    private const val INDEX = "layouts-index.tsv"

    /**
     * How much heap a parsed layout is assumed to cost per byte of its source.
     * A `Key` carries a label, an output string, a role, a width and usually a
     * long-press list, so the object graph runs several times its JSON; four is
     * a deliberate under-estimate, since over-estimating would evict layouts
     * the keyboard is actually using.
     */
    private const val PARSED_WEIGHT_FACTOR = 4

    private const val PARSED_MIN_BUDGET = 4L * 1024 * 1024
    private const val PARSED_MAX_BUDGET = 16L * 1024 * 1024

    /** A share of the heap, clamped — the same shape as every other cache here. */
    private val parsedBudget: Long =
        (Runtime.getRuntime().maxMemory() / 16).coerceIn(PARSED_MIN_BUDGET, PARSED_MAX_BUDGET)

    private val SUFFIX = ".${LayoutFile.FILE_EXTENSION}"
}

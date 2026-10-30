package com.wasimaster.wmkeyboard.core.script

/**
 * The languages with a handwriting pad of their own, which
 * tools/gboard-layouts/handwriting.py writes. Generated; do not hand-edit.
 *
 * One line of text rather than a collection literal because the registry reads
 * it in the keyboard's process at every start ([LanguageRegistry]).
 */
object HandwritingLanguages {

    private val ids: Set<String> by lazy {
        (
        "aa ab abs ace ady af am an anp anw ar as av awa az ba bah bal bar bcq be ber bew bfy bfz bg bgc bgx " +
        "bgz bhb bho bi bjj bjn bn bo bom brx bs bto btz bua bzc ca ce ceb cgg ch cjk ckb co cps crs cs cv cy " +
        "cyo da dar de dnj doi drs drt dsb el en eo es et eu ext fa fan fi fj fo fr fy ga gax gay gbm gcr gd " +
        "gdx gl gos gpe gsw gu gv gyn haq haw he hi hil hmn hne hni ho hoj hr hrx ht hu hy id igb ilo inh is " +
        "it ium jam jax jbo jv ka kaa kbd kde kfy kge kha kj kk kl km kmb kn kok kru ksh ktu ku kv kw ky la " +
        "lad laj lb lbe led lez lld lmn lo lon lt luy lv mad mag mai mas max mdh mel meo mfb mfp mg mi min mk " +
        "ml mn mqy mr mrw ms msi mt mtr mui mup mww my myx nah nap nb ndc ne new ng nga niq nl nn noe nr nso " +
        "ny nym nyo oc olo om or os pa pag pam pap pcc pcd pcm pko pl pms pmy pov prk ps pse pt qu quc rcf rm " +
        "rn ro ru rwr sa sah sc sck sco sd sdc sg sgc sgj sgs si sk skg sl sm sn so sq sr ss su suk sv sw swv " +
        "sxu ta tab tdx te tet tg th ti tk tl tn tpi tr trf ts tsg tt tum tuv tyv udm ug uk ur uz vel vep vi " +
        "vkt wa wbr wry xal xh xmm xnr ymm yue za zu zyj"
        ).split(' ').toHashSet()
    }

    /** The pad of the language [languageId], or nothing. */
    fun layoutIds(languageId: String): List<String> =
        if (languageId in ids) listOf("asset_hw_$languageId") else emptyList()
}

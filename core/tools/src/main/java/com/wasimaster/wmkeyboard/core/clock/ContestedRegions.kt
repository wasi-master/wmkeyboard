package com.wasimaster.wmkeyboard.core.clock

/**
 * The country a zone is listed under, for zones whose country depends on who
 * is asking.
 *
 * The tz database files `Asia/Jerusalem` under Israel. In most Muslim-majority
 * countries, and in countries that side with Palestine, the city is
 * Palestine's, so the world clock follows the phone's region: the first rung
 * of `DeviceLocales`' ladder in `:core:language` (SIM, then network, then
 * time zone, then locale). Everywhere else the tz answer stands.
 */
object ContestedRegions {

    private const val JERUSALEM = "Asia/Jerusalem"

    /**
     * Members of the Organisation of Islamic Cooperation, plus the countries
     * outside it whose governments side openly with Palestine.
     */
    internal val PALESTINE_VIEW: Set<String> = setOf(
        // OIC
        "AF", "AL", "DZ", "AZ", "BH", "BD", "BJ", "BN", "BF", "CM", "TD", "KM", "CI", "DJ", "EG",
        "GA", "GM", "GN", "GW", "GY", "ID", "IR", "IQ", "JO", "KZ", "KW", "KG", "LB", "LY", "MY",
        "MV", "ML", "MR", "MA", "MZ", "NE", "NG", "OM", "PK", "PS", "QA", "SA", "SN", "SL", "SO",
        "SD", "SR", "SY", "TJ", "TG", "TN", "TR", "TM", "UG", "AE", "UZ", "YE",
        // Muslim-majority, outside the OIC
        "BA", "EH",
        // Openly with Palestine
        "ZA", "NA", "IE", "ES", "NO", "SI", "IS", "CU", "VE", "BO", "NI", "BZ", "CO", "CL", "KP",
    )

    /**
     * The ISO region code to show for [zoneId]: [tzRegion] unless the zone is
     * contested and [viewerRegion] sees it otherwise.
     */
    fun regionCode(zoneId: String, tzRegion: String?, viewerRegion: String?): String? =
        if (zoneId == JERUSALEM && viewerRegion != null && viewerRegion.uppercase() in PALESTINE_VIEW) "PS" else tzRegion
}

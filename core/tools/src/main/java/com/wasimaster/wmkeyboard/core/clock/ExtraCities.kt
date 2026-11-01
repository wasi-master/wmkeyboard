package com.wasimaster.wmkeyboard.core.clock

/**
 * Big cities the time zone database has no zone named after (#970).
 *
 * The tz database names a zone after one city and stops there, so Beijing
 * shares Shanghai's zone without a name of its own, Mumbai and Delhi live
 * under Kolkata, and San Francisco under Los Angeles. A search for any of them
 * used to find nothing. Each is listed here with its zone and its own
 * coordinates, so its card gets its own sunrise and sunset rather than the
 * namesake city's.
 *
 * [also] holds other spellings a search should find it by ("Bangalore",
 * "Makkah"). Names are in English: Android has no localised name for a city
 * the tz database does not carry.
 */
internal class ExtraCity(
    val name: String,
    val zoneId: String,
    val latitude: Double,
    val longitude: Double,
    val also: String = "",
)

/** The names of the cities that live under [zoneId], for a search over tz ids. */
fun extraCityNames(zoneId: String): String =
    EXTRA_CITIES.filter { it.zoneId == zoneId }.joinToString(" ") { "${it.name} ${it.also}" }

internal val EXTRA_CITIES: List<ExtraCity> = listOf(
    ExtraCity("Beijing", "Asia/Shanghai", 39.90, 116.40, "Peking"),
    ExtraCity("Shenzhen", "Asia/Shanghai", 22.54, 114.06),
    ExtraCity("Guangzhou", "Asia/Shanghai", 23.13, 113.26, "Canton"),
    ExtraCity("Chengdu", "Asia/Shanghai", 30.57, 104.07),
    ExtraCity("Wuhan", "Asia/Shanghai", 30.59, 114.31),
    ExtraCity("Hangzhou", "Asia/Shanghai", 30.27, 120.16),
    ExtraCity("Xi'an", "Asia/Shanghai", 34.34, 108.94, "Xian"),
    ExtraCity("Chongqing", "Asia/Shanghai", 29.56, 106.55),
    ExtraCity("New Delhi", "Asia/Kolkata", 28.61, 77.21, "Delhi"),
    ExtraCity("Mumbai", "Asia/Kolkata", 19.08, 72.88, "Bombay"),
    ExtraCity("Bengaluru", "Asia/Kolkata", 12.97, 77.59, "Bangalore"),
    ExtraCity("Chennai", "Asia/Kolkata", 13.08, 80.27, "Madras"),
    ExtraCity("Hyderabad", "Asia/Kolkata", 17.39, 78.49),
    ExtraCity("Pune", "Asia/Kolkata", 18.52, 73.86),
    ExtraCity("Ahmedabad", "Asia/Kolkata", 23.02, 72.57),
    ExtraCity("Islamabad", "Asia/Karachi", 33.68, 73.05),
    ExtraCity("Lahore", "Asia/Karachi", 31.55, 74.34),
    ExtraCity("Chattogram", "Asia/Dhaka", 22.36, 91.78, "Chittagong"),
    ExtraCity("Hanoi", "Asia/Ho_Chi_Minh", 21.03, 105.85),
    ExtraCity("Osaka", "Asia/Tokyo", 34.69, 135.50),
    ExtraCity("Kyoto", "Asia/Tokyo", 35.01, 135.77),
    ExtraCity("Sapporo", "Asia/Tokyo", 43.06, 141.35),
    ExtraCity("Busan", "Asia/Seoul", 35.18, 129.08, "Pusan"),
    ExtraCity("Abu Dhabi", "Asia/Dubai", 24.45, 54.38),
    ExtraCity("Mecca", "Asia/Riyadh", 21.39, 39.86, "Makkah"),
    ExtraCity("Medina", "Asia/Riyadh", 24.47, 39.61, "Madinah"),
    ExtraCity("Jeddah", "Asia/Riyadh", 21.49, 39.19, "Jiddah"),
    ExtraCity("Tel Aviv", "Asia/Jerusalem", 32.09, 34.78),
    ExtraCity("Ankara", "Europe/Istanbul", 39.93, 32.86),
    ExtraCity("Saint Petersburg", "Europe/Moscow", 59.93, 30.34, "St Petersburg"),
    ExtraCity("Barcelona", "Europe/Madrid", 41.39, 2.17),
    ExtraCity("Munich", "Europe/Berlin", 48.14, 11.58, "München"),
    ExtraCity("Frankfurt", "Europe/Berlin", 50.11, 8.68),
    ExtraCity("Hamburg", "Europe/Berlin", 53.55, 9.99),
    ExtraCity("Milan", "Europe/Rome", 45.46, 9.19, "Milano"),
    ExtraCity("Geneva", "Europe/Zurich", 46.20, 6.14, "Genève"),
    ExtraCity("Manchester", "Europe/London", 53.48, -2.24),
    ExtraCity("Edinburgh", "Europe/London", 55.95, -3.19),
    ExtraCity("Alexandria", "Africa/Cairo", 31.20, 29.92),
    ExtraCity("Cape Town", "Africa/Johannesburg", -33.92, 18.42),
    ExtraCity("Abuja", "Africa/Lagos", 9.08, 7.40),
    ExtraCity("Washington", "America/New_York", 38.91, -77.04, "DC"),
    ExtraCity("Boston", "America/New_York", 42.36, -71.06),
    ExtraCity("Philadelphia", "America/New_York", 39.95, -75.17),
    ExtraCity("Miami", "America/New_York", 25.76, -80.19),
    ExtraCity("Atlanta", "America/New_York", 33.75, -84.39),
    ExtraCity("Houston", "America/Chicago", 29.76, -95.37),
    ExtraCity("Dallas", "America/Chicago", 32.78, -96.80),
    ExtraCity("Austin", "America/Chicago", 30.27, -97.74),
    ExtraCity("San Francisco", "America/Los_Angeles", 37.77, -122.42),
    ExtraCity("Seattle", "America/Los_Angeles", 47.61, -122.33),
    ExtraCity("San Diego", "America/Los_Angeles", 32.72, -117.16),
    ExtraCity("Las Vegas", "America/Los_Angeles", 36.17, -115.14),
    ExtraCity("Ottawa", "America/Toronto", 45.42, -75.70),
    ExtraCity("Montreal", "America/Toronto", 45.50, -73.57, "Montréal"),
    ExtraCity("Rio de Janeiro", "America/Sao_Paulo", -22.91, -43.17, "Rio"),
    ExtraCity("Brasília", "America/Sao_Paulo", -15.79, -47.88),
    ExtraCity("Canberra", "Australia/Sydney", -35.28, 149.13),
    ExtraCity("Wellington", "Pacific/Auckland", -41.29, 174.78),
)

package com.wasimaster.wmkeyboard.core.tools

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.tools.R

/**
 * The constants page of the calculator tool (#1574): maths, physics and
 * chemistry constants, grouped by topic. Physical values are the CODATA 2022
 * recommended ones (exact where the SI defines them, which [Constant.exact]
 * marks); the astronomy group takes the IAU's nominal values, and the
 * everyday group rounded textbook figures. Values stay text, as published,
 * so nothing is lost to a round trip through a Double before it is shown.
 */
object MathConstants {

    enum class Group(@StringRes val nameRes: Int) {
        MATHS(R.string.core_tools_math_group_maths),
        UNIVERSAL(R.string.core_tools_math_group_universal),
        ELECTROMAGNETIC(R.string.core_tools_math_group_electromagnetic),
        ATOMIC(R.string.core_tools_math_group_atomic),
        CHEMISTRY(R.string.core_tools_math_group_chemistry),
        ASTRONOMY(R.string.core_tools_math_group_astronomy),
        REFERENCE(R.string.core_tools_math_group_reference),
    }

    class Constant(
        val id: String,
        val group: Group,
        /** How the constant is written: `h`, `N_A`, `ε₀`. */
        val symbol: String,
        @StringRes val nameRes: Int,
        /** The value as published, in plain or e notation: `6.62607015e-34`. */
        val value: String,
        /** The SI unit, or "" for a pure number. */
        val unit: String,
        /** Fixed by definition (the SI's defining constants and what follows from them). */
        val exact: Boolean = false,
    ) {
        val number: Double get() = value.toDouble()
    }

    val all: List<Constant> = listOf(
        Constant("pi", Group.MATHS, "π", R.string.core_tools_math_const_pi, "3.14159265358979", ""),
        Constant("e", Group.MATHS, "e", R.string.core_tools_math_const_e, "2.71828182845905", ""),
        Constant("phi", Group.MATHS, "φ", R.string.core_tools_math_const_phi, "1.61803398874989", ""),
        Constant("sqrt2", Group.MATHS, "√2", R.string.core_tools_math_const_sqrt2, "1.41421356237310", ""),
        Constant("sqrt3", Group.MATHS, "√3", R.string.core_tools_math_const_sqrt3, "1.73205080756888", ""),
        Constant("ln2", Group.MATHS, "ln 2", R.string.core_tools_math_const_ln2, "0.693147180559945", ""),
        Constant("ln10", Group.MATHS, "ln 10", R.string.core_tools_math_const_ln10, "2.30258509299405", ""),
        Constant("euler_gamma", Group.MATHS, "γ", R.string.core_tools_math_const_euler_gamma, "0.577215664901533", ""),
        Constant("catalan", Group.MATHS, "G", R.string.core_tools_math_const_catalan, "0.915965594177219", ""),
        Constant("apery", Group.MATHS, "ζ(3)", R.string.core_tools_math_const_apery, "1.20205690315959", ""),
        Constant("feigenbaum", Group.MATHS, "δ", R.string.core_tools_math_const_feigenbaum, "4.66920160910299", ""),
        Constant("degree", Group.MATHS, "1°", R.string.core_tools_math_const_degree, "0.0174532925199433", "rad"),
        Constant("c", Group.UNIVERSAL, "c", R.string.core_tools_math_const_c, "299792458", "m/s", exact = true),
        Constant("G", Group.UNIVERSAL, "G", R.string.core_tools_math_const_g, "6.67430e-11", "m³/(kg·s²)"),
        Constant("h", Group.UNIVERSAL, "h", R.string.core_tools_math_const_h, "6.62607015e-34", "J·s", exact = true),
        Constant("hbar", Group.UNIVERSAL, "ħ", R.string.core_tools_math_const_hbar, "1.054571817e-34", "J·s", exact = true),
        Constant("g0", Group.UNIVERSAL, "g₀", R.string.core_tools_math_const_g0, "9.80665", "m/s²", exact = true),
        Constant("planck_length", Group.UNIVERSAL, "lₚ", R.string.core_tools_math_const_planck_length, "1.616255e-35", "m"),
        Constant("planck_time", Group.UNIVERSAL, "tₚ", R.string.core_tools_math_const_planck_time, "5.391247e-44", "s"),
        Constant("e_charge", Group.ELECTROMAGNETIC, "e", R.string.core_tools_math_const_e_charge, "1.602176634e-19", "C", exact = true),
        Constant("eps0", Group.ELECTROMAGNETIC, "ε₀", R.string.core_tools_math_const_eps0, "8.8541878188e-12", "F/m"),
        Constant("mu0", Group.ELECTROMAGNETIC, "μ₀", R.string.core_tools_math_const_mu0, "1.25663706127e-6", "N/A²"),
        Constant("coulomb_k", Group.ELECTROMAGNETIC, "k", R.string.core_tools_math_const_coulomb_k, "8.9875517862e9", "N·m²/C²"),
        Constant("z0", Group.ELECTROMAGNETIC, "Z₀", R.string.core_tools_math_const_z0, "376.730313412", "Ω"),
        Constant("flux_quantum", Group.ELECTROMAGNETIC, "Φ₀", R.string.core_tools_math_const_flux_quantum, "2.067833848e-15", "Wb", exact = true),
        Constant("conductance_quantum", Group.ELECTROMAGNETIC, "G₀", R.string.core_tools_math_const_conductance_quantum, "7.748091729e-5", "S", exact = true),
        Constant("josephson", Group.ELECTROMAGNETIC, "K_J", R.string.core_tools_math_const_josephson, "4.835978484e14", "Hz/V", exact = true),
        Constant("von_klitzing", Group.ELECTROMAGNETIC, "R_K", R.string.core_tools_math_const_von_klitzing, "25812.80745", "Ω", exact = true),
        Constant("bohr_magneton", Group.ELECTROMAGNETIC, "μ_B", R.string.core_tools_math_const_bohr_magneton, "9.2740100657e-24", "J/T"),
        Constant("nuclear_magneton", Group.ELECTROMAGNETIC, "μ_N", R.string.core_tools_math_const_nuclear_magneton, "5.0507837393e-27", "J/T"),
        Constant("m_e", Group.ATOMIC, "mₑ", R.string.core_tools_math_const_m_e, "9.1093837139e-31", "kg"),
        Constant("m_p", Group.ATOMIC, "mₚ", R.string.core_tools_math_const_m_p, "1.67262192595e-27", "kg"),
        Constant("m_n", Group.ATOMIC, "mₙ", R.string.core_tools_math_const_m_n, "1.67492750056e-27", "kg"),
        Constant("u", Group.ATOMIC, "u", R.string.core_tools_math_const_u, "1.66053906892e-27", "kg"),
        Constant("m_e_energy", Group.ATOMIC, "mₑc²", R.string.core_tools_math_const_m_e_energy, "0.51099895069", "MeV"),
        Constant("m_p_energy", Group.ATOMIC, "mₚc²", R.string.core_tools_math_const_m_p_energy, "938.27208943", "MeV"),
        Constant("proton_electron_ratio", Group.ATOMIC, "mₚ/mₑ", R.string.core_tools_math_const_proton_electron_ratio, "1836.152673426", ""),
        Constant("alpha", Group.ATOMIC, "α", R.string.core_tools_math_const_alpha, "7.2973525643e-3", ""),
        Constant("alpha_inverse", Group.ATOMIC, "α⁻¹", R.string.core_tools_math_const_alpha_inverse, "137.035999177", ""),
        Constant("rydberg", Group.ATOMIC, "R∞", R.string.core_tools_math_const_rydberg, "10973731.568157", "m⁻¹"),
        Constant("bohr_radius", Group.ATOMIC, "a₀", R.string.core_tools_math_const_bohr_radius, "5.29177210544e-11", "m"),
        Constant("electron_radius", Group.ATOMIC, "rₑ", R.string.core_tools_math_const_electron_radius, "2.8179403205e-15", "m"),
        Constant("compton", Group.ATOMIC, "λ_C", R.string.core_tools_math_const_compton, "2.42631023538e-12", "m"),
        Constant("hartree", Group.ATOMIC, "E_h", R.string.core_tools_math_const_hartree, "4.3597447222060e-18", "J"),
        Constant("electronvolt", Group.ATOMIC, "eV", R.string.core_tools_math_const_electronvolt, "1.602176634e-19", "J", exact = true),
        Constant("avogadro", Group.CHEMISTRY, "N_A", R.string.core_tools_math_const_avogadro, "6.02214076e23", "mol⁻¹", exact = true),
        Constant("boltzmann", Group.CHEMISTRY, "k_B", R.string.core_tools_math_const_boltzmann, "1.380649e-23", "J/K", exact = true),
        Constant("gas_constant", Group.CHEMISTRY, "R", R.string.core_tools_math_const_gas_constant, "8.314462618", "J/(mol·K)", exact = true),
        Constant("gas_constant_atm", Group.CHEMISTRY, "R", R.string.core_tools_math_const_gas_constant_atm, "0.08205736608", "L·atm/(mol·K)", exact = true),
        Constant("faraday", Group.CHEMISTRY, "F", R.string.core_tools_math_const_faraday, "96485.33212", "C/mol", exact = true),
        Constant("molar_volume_atm", Group.CHEMISTRY, "V_m", R.string.core_tools_math_const_molar_volume_atm, "22.41396954", "L/mol", exact = true),
        Constant("molar_volume_bar", Group.CHEMISTRY, "V_m", R.string.core_tools_math_const_molar_volume_bar, "22.71095464", "L/mol", exact = true),
        Constant("atm", Group.CHEMISTRY, "atm", R.string.core_tools_math_const_atm, "101325", "Pa", exact = true),
        Constant("standard_pressure", Group.CHEMISTRY, "p°", R.string.core_tools_math_const_standard_pressure, "100000", "Pa", exact = true),
        Constant("zero_celsius", Group.CHEMISTRY, "T₀", R.string.core_tools_math_const_zero_celsius, "273.15", "K", exact = true),
        Constant("loschmidt", Group.CHEMISTRY, "n₀", R.string.core_tools_math_const_loschmidt, "2.686780111e25", "m⁻³", exact = true),
        Constant("molar_mass_constant", Group.CHEMISTRY, "M_u", R.string.core_tools_math_const_molar_mass_constant, "1.00000000105e-3", "kg/mol"),
        Constant("stefan_boltzmann", Group.CHEMISTRY, "σ", R.string.core_tools_math_const_stefan_boltzmann, "5.670374419e-8", "W/(m²·K⁴)", exact = true),
        Constant("wien", Group.CHEMISTRY, "b", R.string.core_tools_math_const_wien, "2.897771955e-3", "m·K", exact = true),
        Constant("second_radiation", Group.CHEMISTRY, "c₂", R.string.core_tools_math_const_second_radiation, "1.438776877e-2", "m·K", exact = true),
        Constant("calorie", Group.CHEMISTRY, "cal", R.string.core_tools_math_const_calorie, "4.184", "J", exact = true),
        Constant("au", Group.ASTRONOMY, "au", R.string.core_tools_math_const_au, "149597870700", "m", exact = true),
        Constant("light_year", Group.ASTRONOMY, "ly", R.string.core_tools_math_const_light_year, "9.4607304725808e15", "m", exact = true),
        Constant("parsec", Group.ASTRONOMY, "pc", R.string.core_tools_math_const_parsec, "3.0856775814913673e16", "m"),
        Constant("earth_mass", Group.ASTRONOMY, "M⊕", R.string.core_tools_math_const_earth_mass, "5.9722e24", "kg"),
        Constant("earth_radius", Group.ASTRONOMY, "R⊕", R.string.core_tools_math_const_earth_radius, "6.3781e6", "m"),
        Constant("moon_mass", Group.ASTRONOMY, "M☾", R.string.core_tools_math_const_moon_mass, "7.342e22", "kg"),
        Constant("moon_distance", Group.ASTRONOMY, "d☾", R.string.core_tools_math_const_moon_distance, "3.844e8", "m"),
        Constant("sun_mass", Group.ASTRONOMY, "M☉", R.string.core_tools_math_const_sun_mass, "1.98847e30", "kg"),
        Constant("sun_radius", Group.ASTRONOMY, "R☉", R.string.core_tools_math_const_sun_radius, "6.957e8", "m"),
        Constant("solar_constant", Group.ASTRONOMY, "S", R.string.core_tools_math_const_solar_constant, "1361", "W/m²"),
        Constant("sound_air", Group.REFERENCE, "v", R.string.core_tools_math_const_sound_air, "343", "m/s"),
        Constant("water_density", Group.REFERENCE, "ρ", R.string.core_tools_math_const_water_density, "999.97", "kg/m³"),
        Constant("air_density", Group.REFERENCE, "ρ", R.string.core_tools_math_const_air_density, "1.204", "kg/m³"),
        Constant("water_heat", Group.REFERENCE, "c", R.string.core_tools_math_const_water_heat, "4184", "J/(kg·K)"),
        Constant("water_fusion", Group.REFERENCE, "L_f", R.string.core_tools_math_const_water_fusion, "3.34e5", "J/kg"),
        Constant("water_vaporisation", Group.REFERENCE, "L_v", R.string.core_tools_math_const_water_vaporisation, "2.26e6", "J/kg"),
        Constant("water_index", Group.REFERENCE, "n", R.string.core_tools_math_const_water_index, "1.333", ""),
        Constant("water_kw", Group.REFERENCE, "K_w", R.string.core_tools_math_const_water_kw, "1.0e-14", ""),
    )

    fun inGroup(group: Group): List<Constant> = all.filter { it.group == group }

    private const val SUPERSCRIPTS = "⁰¹²³⁴⁵⁶⁷⁸⁹"

    /** `6.62607015e-34` as it is printed: `6.62607015 × 10⁻³⁴`. A plain value is left alone. */
    fun pretty(value: String): String {
        val e = value.indexOfFirst { it == 'e' || it == 'E' }
        if (e < 0) return value
        val mantissa = value.substring(0, e)
        val exponent = value.substring(e + 1).removePrefix("+")
        val raised = exponent.map { c ->
            when {
                c == '-' -> '⁻'
                c.isDigit() -> SUPERSCRIPTS[c - '0']
                else -> c
            }
        }.joinToString("")
        return if (mantissa == "1") "10$raised" else "$mantissa × 10$raised"
    }

    /** What a tap types into the app: the value as printed, and its unit. */
    fun asText(constant: Constant): String =
        if (constant.unit.isEmpty()) pretty(constant.value) else "${pretty(constant.value)} ${constant.unit}"

    /**
     * What "use in the calculator" types at the expression's caret: the value
     * in brackets, so it multiplies as one number after a digit or a bracket.
     */
    fun forCalculator(constant: Constant): String = "(${constant.value})"
}

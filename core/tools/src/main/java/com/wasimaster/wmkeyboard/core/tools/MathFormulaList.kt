package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.tools.MathFormulas.Formula
import com.wasimaster.wmkeyboard.core.tools.MathFormulas.Quantity
import com.wasimaster.wmkeyboard.core.tools.MathFormulas.Topic
import com.wasimaster.wmkeyboard.tools.R

/**
 * Every formula the formulas page offers (#1574), by topic. In each, the
 * first quantity is what [Formula.rhs] works out; [MathFormulas.solve] finds
 * any of the others numerically. Angles are in degrees throughout.
 */
internal val FORMULA_LIST: List<Formula> = listOf(
    Formula(
        "quadratic_discriminant", Topic.ALGEBRA, R.string.core_tools_math_formula_quadratic_discriminant,
        "{D} = {b}² − 4·{a}·{c}", "b^2-4*a*c",
        listOf(
            Quantity("D", "D", R.string.core_tools_math_q_discriminant, ""),
            Quantity("a", "a", R.string.core_tools_math_q_coef_a, ""),
            Quantity("b", "b", R.string.core_tools_math_q_coef_b, ""),
            Quantity("c", "c", R.string.core_tools_math_q_coef_c, ""),
        ),
    ),
    Formula(
        "quadratic_root_plus", Topic.ALGEBRA, R.string.core_tools_math_formula_quadratic_root_plus,
        "{x} = (−{b} + √({b}² − 4·{a}·{c})) / (2·{a})", "(-b+sqrt(b^2-4*a*c))/(2*a)",
        listOf(
            Quantity("x", "x₁", R.string.core_tools_math_q_root, ""),
            Quantity("a", "a", R.string.core_tools_math_q_coef_a, ""),
            Quantity("b", "b", R.string.core_tools_math_q_coef_b, ""),
            Quantity("c", "c", R.string.core_tools_math_q_coef_c, ""),
        ),
    ),
    Formula(
        "quadratic_root_minus", Topic.ALGEBRA, R.string.core_tools_math_formula_quadratic_root_minus,
        "{x} = (−{b} − √({b}² − 4·{a}·{c})) / (2·{a})", "(-b-sqrt(b^2-4*a*c))/(2*a)",
        listOf(
            Quantity("x", "x₂", R.string.core_tools_math_q_root, ""),
            Quantity("a", "a", R.string.core_tools_math_q_coef_a, ""),
            Quantity("b", "b", R.string.core_tools_math_q_coef_b, ""),
            Quantity("c", "c", R.string.core_tools_math_q_coef_c, ""),
        ),
    ),
    Formula(
        "percent_change", Topic.ALGEBRA, R.string.core_tools_math_formula_percent_change,
        "{p} = ({B} − {A}) / {A} × 100", "(B-A)/A*100",
        listOf(
            Quantity("p", "Δ%", R.string.core_tools_math_q_percent_change, "%"),
            Quantity("A", "A", R.string.core_tools_math_q_old_value, ""),
            Quantity("B", "B", R.string.core_tools_math_q_new_value, ""),
        ),
    ),
    Formula(
        "percent_of", Topic.ALGEBRA, R.string.core_tools_math_formula_percent_of,
        "{x} = {p} / 100 × {w}", "p/100*w",
        listOf(
            Quantity("x", "x", R.string.core_tools_math_q_part, ""),
            Quantity("p", "p", R.string.core_tools_math_q_percent, "%"),
            Quantity("w", "w", R.string.core_tools_math_q_whole, ""),
        ),
    ),
    Formula(
        "simple_interest", Topic.ALGEBRA, R.string.core_tools_math_formula_simple_interest,
        "{I} = {P}·{r}·{t} / 100", "P*r*t/100",
        listOf(
            Quantity("I", "I", R.string.core_tools_math_q_interest, ""),
            Quantity("P", "P", R.string.core_tools_math_q_principal, ""),
            Quantity("r", "r", R.string.core_tools_math_q_rate_year, "%"),
            Quantity("t", "t", R.string.core_tools_math_q_years, ""),
        ),
    ),
    Formula(
        "compound_interest", Topic.ALGEBRA, R.string.core_tools_math_formula_compound_interest,
        "{A} = {P}·(1 + {r}/(100·{n}))^({n}·{t})", "P*(1+r/(100*n))^(n*t)",
        listOf(
            Quantity("A", "A", R.string.core_tools_math_q_amount, ""),
            Quantity("P", "P", R.string.core_tools_math_q_principal, ""),
            Quantity("r", "r", R.string.core_tools_math_q_rate_year, "%"),
            Quantity("n", "n", R.string.core_tools_math_q_periods_year, "", "12"),
            Quantity("t", "t", R.string.core_tools_math_q_years, ""),
        ),
    ),
    Formula(
        "arithmetic_term", Topic.ALGEBRA, R.string.core_tools_math_formula_arithmetic_term,
        "{an} = {a} + ({n} − 1)·{d}", "a+(n-1)*d",
        listOf(
            Quantity("an", "aₙ", R.string.core_tools_math_q_nth_term, ""),
            Quantity("a", "a₁", R.string.core_tools_math_q_first_term, ""),
            Quantity("n", "n", R.string.core_tools_math_q_term_number, ""),
            Quantity("d", "d", R.string.core_tools_math_q_difference, ""),
        ),
    ),
    Formula(
        "arithmetic_sum", Topic.ALGEBRA, R.string.core_tools_math_formula_arithmetic_sum,
        "{S} = {n}/2·(2·{a} + ({n} − 1)·{d})", "n/2*(2*a+(n-1)*d)",
        listOf(
            Quantity("S", "Sₙ", R.string.core_tools_math_q_sum, ""),
            Quantity("n", "n", R.string.core_tools_math_q_term_number, ""),
            Quantity("a", "a₁", R.string.core_tools_math_q_first_term, ""),
            Quantity("d", "d", R.string.core_tools_math_q_difference, ""),
        ),
    ),
    Formula(
        "geometric_term", Topic.ALGEBRA, R.string.core_tools_math_formula_geometric_term,
        "{an} = {a}·{r}^({n} − 1)", "a*r^(n-1)",
        listOf(
            Quantity("an", "aₙ", R.string.core_tools_math_q_nth_term, ""),
            Quantity("a", "a₁", R.string.core_tools_math_q_first_term, ""),
            Quantity("r", "r", R.string.core_tools_math_q_ratio, ""),
            Quantity("n", "n", R.string.core_tools_math_q_term_number, ""),
        ),
    ),
    Formula(
        "geometric_sum", Topic.ALGEBRA, R.string.core_tools_math_formula_geometric_sum,
        "{S} = {a}·(1 − {r}^{n}) / (1 − {r})", "a*(1-r^n)/(1-r)",
        listOf(
            Quantity("S", "Sₙ", R.string.core_tools_math_q_sum, ""),
            Quantity("a", "a₁", R.string.core_tools_math_q_first_term, ""),
            Quantity("r", "r", R.string.core_tools_math_q_ratio, ""),
            Quantity("n", "n", R.string.core_tools_math_q_term_number, ""),
        ),
    ),
    Formula(
        "distance_points", Topic.ALGEBRA, R.string.core_tools_math_formula_distance_points,
        "{d} = √(({xb} − {xa})² + ({yb} − {ya})²)", "sqrt((xb-xa)^2+(yb-ya)^2)",
        listOf(
            Quantity("d", "d", R.string.core_tools_math_q_distance, ""),
            Quantity("xa", "x₁", R.string.core_tools_math_q_x1, ""),
            Quantity("ya", "y₁", R.string.core_tools_math_q_y1, ""),
            Quantity("xb", "x₂", R.string.core_tools_math_q_x2, ""),
            Quantity("yb", "y₂", R.string.core_tools_math_q_y2, ""),
        ),
    ),
    Formula(
        "slope", Topic.ALGEBRA, R.string.core_tools_math_formula_slope,
        "{m} = ({yb} − {ya}) / ({xb} − {xa})", "(yb-ya)/(xb-xa)",
        listOf(
            Quantity("m", "m", R.string.core_tools_math_q_slope, ""),
            Quantity("xa", "x₁", R.string.core_tools_math_q_x1, ""),
            Quantity("ya", "y₁", R.string.core_tools_math_q_y1, ""),
            Quantity("xb", "x₂", R.string.core_tools_math_q_x2, ""),
            Quantity("yb", "y₂", R.string.core_tools_math_q_y2, ""),
        ),
    ),
    Formula(
        "circle_area", Topic.GEOMETRY, R.string.core_tools_math_formula_circle_area,
        "{A} = π·{r}²", "pi*r^2",
        listOf(
            Quantity("A", "A", R.string.core_tools_math_q_area, ""),
            Quantity("r", "r", R.string.core_tools_math_q_radius, ""),
        ),
    ),
    Formula(
        "circle_circumference", Topic.GEOMETRY, R.string.core_tools_math_formula_circle_circumference,
        "{C} = 2·π·{r}", "2*pi*r",
        listOf(
            Quantity("C", "C", R.string.core_tools_math_q_circumference, ""),
            Quantity("r", "r", R.string.core_tools_math_q_radius, ""),
        ),
    ),
    Formula(
        "rectangle_area", Topic.GEOMETRY, R.string.core_tools_math_formula_rectangle_area,
        "{A} = {l}·{w}", "l*w",
        listOf(
            Quantity("A", "A", R.string.core_tools_math_q_area, ""),
            Quantity("l", "l", R.string.core_tools_math_q_length, ""),
            Quantity("w", "w", R.string.core_tools_math_q_width, ""),
        ),
    ),
    Formula(
        "triangle_area", Topic.GEOMETRY, R.string.core_tools_math_formula_triangle_area,
        "{A} = ½·{b}·{h}", "b*h/2",
        listOf(
            Quantity("A", "A", R.string.core_tools_math_q_area, ""),
            Quantity("b", "b", R.string.core_tools_math_q_base, ""),
            Quantity("h", "h", R.string.core_tools_math_q_height, ""),
        ),
    ),
    Formula(
        "trapezoid_area", Topic.GEOMETRY, R.string.core_tools_math_formula_trapezoid_area,
        "{A} = ½·({a} + {b})·{h}", "(a+b)/2*h",
        listOf(
            Quantity("A", "A", R.string.core_tools_math_q_area, ""),
            Quantity("a", "a", R.string.core_tools_math_q_side_a, ""),
            Quantity("b", "b", R.string.core_tools_math_q_side_b, ""),
            Quantity("h", "h", R.string.core_tools_math_q_height, ""),
        ),
    ),
    Formula(
        "pythagoras", Topic.GEOMETRY, R.string.core_tools_math_formula_pythagoras,
        "{c} = √({a}² + {b}²)", "sqrt(a^2+b^2)",
        listOf(
            Quantity("c", "c", R.string.core_tools_math_q_hypotenuse, ""),
            Quantity("a", "a", R.string.core_tools_math_q_side_a, ""),
            Quantity("b", "b", R.string.core_tools_math_q_side_b, ""),
        ),
    ),
    Formula(
        "sector_area", Topic.GEOMETRY, R.string.core_tools_math_formula_sector_area,
        "{A} = π·{r}²·{t} / 360", "pi*r^2*t/360",
        listOf(
            Quantity("A", "A", R.string.core_tools_math_q_area, ""),
            Quantity("r", "r", R.string.core_tools_math_q_radius, ""),
            Quantity("t", "θ", R.string.core_tools_math_q_angle, "°"),
        ),
    ),
    Formula(
        "arc_length", Topic.GEOMETRY, R.string.core_tools_math_formula_arc_length,
        "{s} = π·{r}·{t} / 180", "pi*r*t/180",
        listOf(
            Quantity("s", "s", R.string.core_tools_math_q_arc, ""),
            Quantity("r", "r", R.string.core_tools_math_q_radius, ""),
            Quantity("t", "θ", R.string.core_tools_math_q_angle, "°"),
        ),
    ),
    Formula(
        "polygon_angles", Topic.GEOMETRY, R.string.core_tools_math_formula_polygon_angles,
        "{S} = ({n} − 2)·180", "(n-2)*180",
        listOf(
            Quantity("S", "S", R.string.core_tools_math_q_angle_sum, "°"),
            Quantity("n", "n", R.string.core_tools_math_q_sides, ""),
        ),
    ),
    Formula(
        "cuboid_volume", Topic.GEOMETRY, R.string.core_tools_math_formula_cuboid_volume,
        "{V} = {l}·{w}·{h}", "l*w*h",
        listOf(
            Quantity("V", "V", R.string.core_tools_math_q_volume, ""),
            Quantity("l", "l", R.string.core_tools_math_q_length, ""),
            Quantity("w", "w", R.string.core_tools_math_q_width, ""),
            Quantity("h", "h", R.string.core_tools_math_q_height, ""),
        ),
    ),
    Formula(
        "cylinder_volume", Topic.GEOMETRY, R.string.core_tools_math_formula_cylinder_volume,
        "{V} = π·{r}²·{h}", "pi*r^2*h",
        listOf(
            Quantity("V", "V", R.string.core_tools_math_q_volume, ""),
            Quantity("r", "r", R.string.core_tools_math_q_radius, ""),
            Quantity("h", "h", R.string.core_tools_math_q_height, ""),
        ),
    ),
    Formula(
        "cone_volume", Topic.GEOMETRY, R.string.core_tools_math_formula_cone_volume,
        "{V} = ⅓·π·{r}²·{h}", "pi*r^2*h/3",
        listOf(
            Quantity("V", "V", R.string.core_tools_math_q_volume, ""),
            Quantity("r", "r", R.string.core_tools_math_q_radius, ""),
            Quantity("h", "h", R.string.core_tools_math_q_height, ""),
        ),
    ),
    Formula(
        "sphere_volume", Topic.GEOMETRY, R.string.core_tools_math_formula_sphere_volume,
        "{V} = 4/3·π·{r}³", "4/3*pi*r^3",
        listOf(
            Quantity("V", "V", R.string.core_tools_math_q_volume, ""),
            Quantity("r", "r", R.string.core_tools_math_q_radius, ""),
        ),
    ),
    Formula(
        "sphere_surface", Topic.GEOMETRY, R.string.core_tools_math_formula_sphere_surface,
        "{A} = 4·π·{r}²", "4*pi*r^2",
        listOf(
            Quantity("A", "A", R.string.core_tools_math_q_surface, ""),
            Quantity("r", "r", R.string.core_tools_math_q_radius, ""),
        ),
    ),
    Formula(
        "cylinder_surface", Topic.GEOMETRY, R.string.core_tools_math_formula_cylinder_surface,
        "{A} = 2·π·{r}·({r} + {h})", "2*pi*r*(r+h)",
        listOf(
            Quantity("A", "A", R.string.core_tools_math_q_surface, ""),
            Quantity("r", "r", R.string.core_tools_math_q_radius, ""),
            Quantity("h", "h", R.string.core_tools_math_q_height, ""),
        ),
    ),
    Formula(
        "right_sine", Topic.TRIGONOMETRY, R.string.core_tools_math_formula_right_sine,
        "{a} = {c}·sin {t}", "c*sin(t)",
        listOf(
            Quantity("a", "a", R.string.core_tools_math_q_opposite, ""),
            Quantity("c", "c", R.string.core_tools_math_q_hypotenuse, ""),
            Quantity("t", "θ", R.string.core_tools_math_q_angle, "°"),
        ),
    ),
    Formula(
        "right_cosine", Topic.TRIGONOMETRY, R.string.core_tools_math_formula_right_cosine,
        "{b} = {c}·cos {t}", "c*cos(t)",
        listOf(
            Quantity("b", "b", R.string.core_tools_math_q_adjacent, ""),
            Quantity("c", "c", R.string.core_tools_math_q_hypotenuse, ""),
            Quantity("t", "θ", R.string.core_tools_math_q_angle, "°"),
        ),
    ),
    Formula(
        "right_tangent", Topic.TRIGONOMETRY, R.string.core_tools_math_formula_right_tangent,
        "{a} = {b}·tan {t}", "b*tan(t)",
        listOf(
            Quantity("a", "a", R.string.core_tools_math_q_opposite, ""),
            Quantity("b", "b", R.string.core_tools_math_q_adjacent, ""),
            Quantity("t", "θ", R.string.core_tools_math_q_angle, "°"),
        ),
    ),
    Formula(
        "law_of_sines", Topic.TRIGONOMETRY, R.string.core_tools_math_formula_law_of_sines,
        "{a} = {b}·sin {A} / sin {B}", "b*sin(A)/sin(B)",
        listOf(
            Quantity("a", "a", R.string.core_tools_math_q_side_a, ""),
            Quantity("b", "b", R.string.core_tools_math_q_side_b, ""),
            Quantity("A", "A", R.string.core_tools_math_q_angle_a, "°"),
            Quantity("B", "B", R.string.core_tools_math_q_angle_b, "°"),
        ),
    ),
    Formula(
        "law_of_cosines", Topic.TRIGONOMETRY, R.string.core_tools_math_formula_law_of_cosines,
        "{c} = √({a}² + {b}² − 2·{a}·{b}·cos {C})", "sqrt(a^2+b^2-2*a*b*cos(C))",
        listOf(
            Quantity("c", "c", R.string.core_tools_math_q_side_c, ""),
            Quantity("a", "a", R.string.core_tools_math_q_side_a, ""),
            Quantity("b", "b", R.string.core_tools_math_q_side_b, ""),
            Quantity("C", "C", R.string.core_tools_math_q_angle_c, "°"),
        ),
    ),
    Formula(
        "triangle_area_sas", Topic.TRIGONOMETRY, R.string.core_tools_math_formula_triangle_area_sas,
        "{S} = ½·{a}·{b}·sin {C}", "a*b*sin(C)/2",
        listOf(
            Quantity("S", "A", R.string.core_tools_math_q_area, ""),
            Quantity("a", "a", R.string.core_tools_math_q_side_a, ""),
            Quantity("b", "b", R.string.core_tools_math_q_side_b, ""),
            Quantity("C", "C", R.string.core_tools_math_q_angle_c, "°"),
        ),
    ),
    Formula(
        "degrees_to_radians", Topic.TRIGONOMETRY, R.string.core_tools_math_formula_degrees_to_radians,
        "{r} = {d}·π / 180", "d*pi/180",
        listOf(
            Quantity("r", "rad", R.string.core_tools_math_q_radians, "rad"),
            Quantity("d", "deg", R.string.core_tools_math_q_degrees, "°"),
        ),
    ),
    Formula(
        "velocity_time", Topic.MECHANICS, R.string.core_tools_math_formula_velocity_time,
        "{v} = {u} + {a}·{t}", "u+a*t",
        listOf(
            Quantity("v", "v", R.string.core_tools_math_q_final_velocity, "m/s"),
            Quantity("u", "u", R.string.core_tools_math_q_initial_velocity, "m/s"),
            Quantity("a", "a", R.string.core_tools_math_q_acceleration, "m/s²"),
            Quantity("t", "t", R.string.core_tools_math_q_time, "s"),
        ),
    ),
    Formula(
        "displacement_time", Topic.MECHANICS, R.string.core_tools_math_formula_displacement_time,
        "{s} = {u}·{t} + ½·{a}·{t}²", "u*t+a*t^2/2",
        listOf(
            Quantity("s", "s", R.string.core_tools_math_q_displacement, "m"),
            Quantity("u", "u", R.string.core_tools_math_q_initial_velocity, "m/s"),
            Quantity("a", "a", R.string.core_tools_math_q_acceleration, "m/s²"),
            Quantity("t", "t", R.string.core_tools_math_q_time, "s"),
        ),
    ),
    Formula(
        "velocity_displacement", Topic.MECHANICS, R.string.core_tools_math_formula_velocity_displacement,
        "{v}² = {u}² + 2·{a}·{s}", "sqrt(u^2+2*a*s)",
        listOf(
            Quantity("v", "v", R.string.core_tools_math_q_final_velocity, "m/s"),
            Quantity("u", "u", R.string.core_tools_math_q_initial_velocity, "m/s"),
            Quantity("a", "a", R.string.core_tools_math_q_acceleration, "m/s²"),
            Quantity("s", "s", R.string.core_tools_math_q_displacement, "m"),
        ),
    ),
    Formula(
        "average_velocity", Topic.MECHANICS, R.string.core_tools_math_formula_average_velocity,
        "{s} = ({u} + {v})/2·{t}", "(u+v)/2*t",
        listOf(
            Quantity("s", "s", R.string.core_tools_math_q_displacement, "m"),
            Quantity("u", "u", R.string.core_tools_math_q_initial_velocity, "m/s"),
            Quantity("v", "v", R.string.core_tools_math_q_final_velocity, "m/s"),
            Quantity("t", "t", R.string.core_tools_math_q_time, "s"),
        ),
    ),
    Formula(
        "speed", Topic.MECHANICS, R.string.core_tools_math_formula_speed,
        "{v} = {d} / {t}", "d/t",
        listOf(
            Quantity("v", "v", R.string.core_tools_math_q_speed, "m/s"),
            Quantity("d", "d", R.string.core_tools_math_q_distance, "m"),
            Quantity("t", "t", R.string.core_tools_math_q_time, "s"),
        ),
    ),
    Formula(
        "newton_second", Topic.MECHANICS, R.string.core_tools_math_formula_newton_second,
        "{F} = {m}·{a}", "m*a",
        listOf(
            Quantity("F", "F", R.string.core_tools_math_q_force, "N"),
            Quantity("m", "m", R.string.core_tools_math_q_mass, "kg"),
            Quantity("a", "a", R.string.core_tools_math_q_acceleration, "m/s²"),
        ),
    ),
    Formula(
        "weight", Topic.MECHANICS, R.string.core_tools_math_formula_weight,
        "{W} = {m}·{g}", "m*g",
        listOf(
            Quantity("W", "W", R.string.core_tools_math_q_weight, "N"),
            Quantity("m", "m", R.string.core_tools_math_q_mass, "kg"),
            Quantity("g", "g", R.string.core_tools_math_q_gravity, "m/s²", "9.80665"),
        ),
    ),
    Formula(
        "momentum", Topic.MECHANICS, R.string.core_tools_math_formula_momentum,
        "{p} = {m}·{v}", "m*v",
        listOf(
            Quantity("p", "p", R.string.core_tools_math_q_momentum, "kg·m/s"),
            Quantity("m", "m", R.string.core_tools_math_q_mass, "kg"),
            Quantity("v", "v", R.string.core_tools_math_q_velocity, "m/s"),
        ),
    ),
    Formula(
        "impulse", Topic.MECHANICS, R.string.core_tools_math_formula_impulse,
        "{J} = {F}·{t}", "F*t",
        listOf(
            Quantity("J", "J", R.string.core_tools_math_q_impulse, "N·s"),
            Quantity("F", "F", R.string.core_tools_math_q_force, "N"),
            Quantity("t", "t", R.string.core_tools_math_q_time, "s"),
        ),
    ),
    Formula(
        "kinetic_energy", Topic.MECHANICS, R.string.core_tools_math_formula_kinetic_energy,
        "{E} = ½·{m}·{v}²", "m*v^2/2",
        listOf(
            Quantity("E", "Eₖ", R.string.core_tools_math_q_kinetic_energy, "J"),
            Quantity("m", "m", R.string.core_tools_math_q_mass, "kg"),
            Quantity("v", "v", R.string.core_tools_math_q_velocity, "m/s"),
        ),
    ),
    Formula(
        "potential_energy", Topic.MECHANICS, R.string.core_tools_math_formula_potential_energy,
        "{E} = {m}·{g}·{h}", "m*g*h",
        listOf(
            Quantity("E", "Eₚ", R.string.core_tools_math_q_potential_energy, "J"),
            Quantity("m", "m", R.string.core_tools_math_q_mass, "kg"),
            Quantity("g", "g", R.string.core_tools_math_q_gravity, "m/s²", "9.80665"),
            Quantity("h", "h", R.string.core_tools_math_q_height, "m"),
        ),
    ),
    Formula(
        "work", Topic.MECHANICS, R.string.core_tools_math_formula_work,
        "{W} = {F}·{d}·cos {t}", "F*d*cos(t)",
        listOf(
            Quantity("W", "W", R.string.core_tools_math_q_work, "J"),
            Quantity("F", "F", R.string.core_tools_math_q_force, "N"),
            Quantity("d", "d", R.string.core_tools_math_q_distance, "m"),
            Quantity("t", "θ", R.string.core_tools_math_q_angle, "°", "0"),
        ),
    ),
    Formula(
        "power", Topic.MECHANICS, R.string.core_tools_math_formula_power,
        "{P} = {W} / {t}", "W/t",
        listOf(
            Quantity("P", "P", R.string.core_tools_math_q_power, "W"),
            Quantity("W", "W", R.string.core_tools_math_q_work, "J"),
            Quantity("t", "t", R.string.core_tools_math_q_time, "s"),
        ),
    ),
    Formula(
        "pressure", Topic.MECHANICS, R.string.core_tools_math_formula_pressure,
        "{p} = {F} / {A}", "F/A",
        listOf(
            Quantity("p", "p", R.string.core_tools_math_q_pressure, "Pa"),
            Quantity("F", "F", R.string.core_tools_math_q_force, "N"),
            Quantity("A", "A", R.string.core_tools_math_q_area, "m²"),
        ),
    ),
    Formula(
        "density", Topic.MECHANICS, R.string.core_tools_math_formula_density,
        "{rho} = {m} / {V}", "m/V",
        listOf(
            Quantity("rho", "ρ", R.string.core_tools_math_q_density, "kg/m³"),
            Quantity("m", "m", R.string.core_tools_math_q_mass, "kg"),
            Quantity("V", "V", R.string.core_tools_math_q_volume, "m³"),
        ),
    ),
    Formula(
        "hydrostatic_pressure", Topic.MECHANICS, R.string.core_tools_math_formula_hydrostatic_pressure,
        "{p} = {rho}·{g}·{h}", "rho*g*h",
        listOf(
            Quantity("p", "p", R.string.core_tools_math_q_pressure, "Pa"),
            Quantity("rho", "ρ", R.string.core_tools_math_q_density, "kg/m³", "1000"),
            Quantity("g", "g", R.string.core_tools_math_q_gravity, "m/s²", "9.80665"),
            Quantity("h", "h", R.string.core_tools_math_q_depth, "m"),
        ),
    ),
    Formula(
        "centripetal_force", Topic.MECHANICS, R.string.core_tools_math_formula_centripetal_force,
        "{F} = {m}·{v}² / {r}", "m*v^2/r",
        listOf(
            Quantity("F", "F", R.string.core_tools_math_q_force, "N"),
            Quantity("m", "m", R.string.core_tools_math_q_mass, "kg"),
            Quantity("v", "v", R.string.core_tools_math_q_speed, "m/s"),
            Quantity("r", "r", R.string.core_tools_math_q_radius, "m"),
        ),
    ),
    Formula(
        "gravitation", Topic.MECHANICS, R.string.core_tools_math_formula_gravitation,
        "{F} = {G}·{ma}·{mb} / {r}²", "G*ma*mb/r^2",
        listOf(
            Quantity("F", "F", R.string.core_tools_math_q_force, "N"),
            Quantity("ma", "m₁", R.string.core_tools_math_q_mass_1, "kg"),
            Quantity("mb", "m₂", R.string.core_tools_math_q_mass_2, "kg"),
            Quantity("r", "r", R.string.core_tools_math_q_distance, "m"),
            Quantity("G", "G", R.string.core_tools_math_q_grav_constant, "m³/(kg·s²)", "6.67430e-11"),
        ),
    ),
    Formula(
        "hooke", Topic.MECHANICS, R.string.core_tools_math_formula_hooke,
        "{F} = {k}·{x}", "k*x",
        listOf(
            Quantity("F", "F", R.string.core_tools_math_q_force, "N"),
            Quantity("k", "k", R.string.core_tools_math_q_spring_constant, "N/m"),
            Quantity("x", "x", R.string.core_tools_math_q_extension, "m"),
        ),
    ),
    Formula(
        "spring_energy", Topic.MECHANICS, R.string.core_tools_math_formula_spring_energy,
        "{E} = ½·{k}·{x}²", "k*x^2/2",
        listOf(
            Quantity("E", "E", R.string.core_tools_math_q_energy, "J"),
            Quantity("k", "k", R.string.core_tools_math_q_spring_constant, "N/m"),
            Quantity("x", "x", R.string.core_tools_math_q_extension, "m"),
        ),
    ),
    Formula(
        "pendulum", Topic.MECHANICS, R.string.core_tools_math_formula_pendulum,
        "{T} = 2·π·√({L} / {g})", "2*pi*sqrt(L/g)",
        listOf(
            Quantity("T", "T", R.string.core_tools_math_q_period, "s"),
            Quantity("L", "L", R.string.core_tools_math_q_pendulum_length, "m"),
            Quantity("g", "g", R.string.core_tools_math_q_gravity, "m/s²", "9.80665"),
        ),
    ),
    Formula(
        "projectile_range", Topic.MECHANICS, R.string.core_tools_math_formula_projectile_range,
        "{R} = {v}²·sin(2·{t}) / {g}", "v^2*sin(2*t)/g",
        listOf(
            Quantity("R", "R", R.string.core_tools_math_q_range, "m"),
            Quantity("v", "v", R.string.core_tools_math_q_initial_velocity, "m/s"),
            Quantity("t", "θ", R.string.core_tools_math_q_launch_angle, "°"),
            Quantity("g", "g", R.string.core_tools_math_q_gravity, "m/s²", "9.80665"),
        ),
    ),
    Formula(
        "torque", Topic.MECHANICS, R.string.core_tools_math_formula_torque,
        "{T} = {F}·{r}·sin {t}", "F*r*sin(t)",
        listOf(
            Quantity("T", "τ", R.string.core_tools_math_q_torque, "N·m"),
            Quantity("F", "F", R.string.core_tools_math_q_force, "N"),
            Quantity("r", "r", R.string.core_tools_math_q_distance, "m"),
            Quantity("t", "θ", R.string.core_tools_math_q_angle, "°", "90"),
        ),
    ),
    Formula(
        "ohm", Topic.ELECTRICITY, R.string.core_tools_math_formula_ohm,
        "{V} = {I}·{R}", "I*R",
        listOf(
            Quantity("V", "V", R.string.core_tools_math_q_voltage, "V"),
            Quantity("I", "I", R.string.core_tools_math_q_current, "A"),
            Quantity("R", "R", R.string.core_tools_math_q_resistance, "Ω"),
        ),
    ),
    Formula(
        "power_vi", Topic.ELECTRICITY, R.string.core_tools_math_formula_power_vi,
        "{P} = {V}·{I}", "V*I",
        listOf(
            Quantity("P", "P", R.string.core_tools_math_q_power, "W"),
            Quantity("V", "V", R.string.core_tools_math_q_voltage, "V"),
            Quantity("I", "I", R.string.core_tools_math_q_current, "A"),
        ),
    ),
    Formula(
        "power_ir", Topic.ELECTRICITY, R.string.core_tools_math_formula_power_ir,
        "{P} = {I}²·{R}", "I^2*R",
        listOf(
            Quantity("P", "P", R.string.core_tools_math_q_power, "W"),
            Quantity("I", "I", R.string.core_tools_math_q_current, "A"),
            Quantity("R", "R", R.string.core_tools_math_q_resistance, "Ω"),
        ),
    ),
    Formula(
        "power_vr", Topic.ELECTRICITY, R.string.core_tools_math_formula_power_vr,
        "{P} = {V}² / {R}", "V^2/R",
        listOf(
            Quantity("P", "P", R.string.core_tools_math_q_power, "W"),
            Quantity("V", "V", R.string.core_tools_math_q_voltage, "V"),
            Quantity("R", "R", R.string.core_tools_math_q_resistance, "Ω"),
        ),
    ),
    Formula(
        "electric_energy", Topic.ELECTRICITY, R.string.core_tools_math_formula_electric_energy,
        "{E} = {P}·{t}", "P*t",
        listOf(
            Quantity("E", "E", R.string.core_tools_math_q_energy, "J"),
            Quantity("P", "P", R.string.core_tools_math_q_power, "W"),
            Quantity("t", "t", R.string.core_tools_math_q_time, "s"),
        ),
    ),
    Formula(
        "charge_current", Topic.ELECTRICITY, R.string.core_tools_math_formula_charge_current,
        "{Q} = {I}·{t}", "I*t",
        listOf(
            Quantity("Q", "Q", R.string.core_tools_math_q_charge, "C"),
            Quantity("I", "I", R.string.core_tools_math_q_current, "A"),
            Quantity("t", "t", R.string.core_tools_math_q_time, "s"),
        ),
    ),
    Formula(
        "series_resistance", Topic.ELECTRICITY, R.string.core_tools_math_formula_series_resistance,
        "{R} = {Ra} + {Rb} + {Rc}", "Ra+Rb+Rc",
        listOf(
            Quantity("R", "R", R.string.core_tools_math_q_total_resistance, "Ω"),
            Quantity("Ra", "R₁", R.string.core_tools_math_q_resistor_1, "Ω"),
            Quantity("Rb", "R₂", R.string.core_tools_math_q_resistor_2, "Ω"),
            Quantity("Rc", "R₃", R.string.core_tools_math_q_resistor_3, "Ω", "0"),
        ),
    ),
    Formula(
        "parallel_resistance", Topic.ELECTRICITY, R.string.core_tools_math_formula_parallel_resistance,
        "1/{R} = 1/{Ra} + 1/{Rb}", "1/(1/Ra+1/Rb)",
        listOf(
            Quantity("R", "R", R.string.core_tools_math_q_total_resistance, "Ω"),
            Quantity("Ra", "R₁", R.string.core_tools_math_q_resistor_1, "Ω"),
            Quantity("Rb", "R₂", R.string.core_tools_math_q_resistor_2, "Ω"),
        ),
    ),
    Formula(
        "coulomb", Topic.ELECTRICITY, R.string.core_tools_math_formula_coulomb,
        "{F} = {k}·{qa}·{qb} / {r}²", "k*qa*qb/r^2",
        listOf(
            Quantity("F", "F", R.string.core_tools_math_q_force, "N"),
            Quantity("qa", "q₁", R.string.core_tools_math_q_charge_1, "C"),
            Quantity("qb", "q₂", R.string.core_tools_math_q_charge_2, "C"),
            Quantity("r", "r", R.string.core_tools_math_q_distance, "m"),
            Quantity("k", "k", R.string.core_tools_math_q_coulomb_constant, "N·m²/C²", "8.9875517862e9"),
        ),
    ),
    Formula(
        "electric_field", Topic.ELECTRICITY, R.string.core_tools_math_formula_electric_field,
        "{E} = {F} / {q}", "F/q",
        listOf(
            Quantity("E", "E", R.string.core_tools_math_q_field, "N/C"),
            Quantity("F", "F", R.string.core_tools_math_q_force, "N"),
            Quantity("q", "q", R.string.core_tools_math_q_charge, "C"),
        ),
    ),
    Formula(
        "capacitor_charge", Topic.ELECTRICITY, R.string.core_tools_math_formula_capacitor_charge,
        "{Q} = {C}·{V}", "C*V",
        listOf(
            Quantity("Q", "Q", R.string.core_tools_math_q_charge, "C"),
            Quantity("C", "C", R.string.core_tools_math_q_capacitance, "F"),
            Quantity("V", "V", R.string.core_tools_math_q_voltage, "V"),
        ),
    ),
    Formula(
        "capacitor_energy", Topic.ELECTRICITY, R.string.core_tools_math_formula_capacitor_energy,
        "{E} = ½·{C}·{V}²", "C*V^2/2",
        listOf(
            Quantity("E", "E", R.string.core_tools_math_q_energy, "J"),
            Quantity("C", "C", R.string.core_tools_math_q_capacitance, "F"),
            Quantity("V", "V", R.string.core_tools_math_q_voltage, "V"),
        ),
    ),
    Formula(
        "rc_time", Topic.ELECTRICITY, R.string.core_tools_math_formula_rc_time,
        "{T} = {R}·{C}", "R*C",
        listOf(
            Quantity("T", "τ", R.string.core_tools_math_q_time_constant, "s"),
            Quantity("R", "R", R.string.core_tools_math_q_resistance, "Ω"),
            Quantity("C", "C", R.string.core_tools_math_q_capacitance, "F"),
        ),
    ),
    Formula(
        "resistivity", Topic.ELECTRICITY, R.string.core_tools_math_formula_resistivity,
        "{R} = {rho}·{L} / {A}", "rho*L/A",
        listOf(
            Quantity("R", "R", R.string.core_tools_math_q_resistance, "Ω"),
            Quantity("rho", "ρ", R.string.core_tools_math_q_resistivity, "Ω·m"),
            Quantity("L", "L", R.string.core_tools_math_q_length, "m"),
            Quantity("A", "A", R.string.core_tools_math_q_cross_section, "m²"),
        ),
    ),
    Formula(
        "wave_speed", Topic.WAVES, R.string.core_tools_math_formula_wave_speed,
        "{v} = {f}·{lam}", "f*lam",
        listOf(
            Quantity("v", "v", R.string.core_tools_math_q_wave_speed, "m/s"),
            Quantity("f", "f", R.string.core_tools_math_q_frequency, "Hz"),
            Quantity("lam", "λ", R.string.core_tools_math_q_wavelength, "m"),
        ),
    ),
    Formula(
        "period_frequency", Topic.WAVES, R.string.core_tools_math_formula_period_frequency,
        "{T} = 1 / {f}", "1/f",
        listOf(
            Quantity("T", "T", R.string.core_tools_math_q_period, "s"),
            Quantity("f", "f", R.string.core_tools_math_q_frequency, "Hz"),
        ),
    ),
    Formula(
        "photon_frequency", Topic.WAVES, R.string.core_tools_math_formula_photon_frequency,
        "{E} = {h}·{f}", "h*f",
        listOf(
            Quantity("E", "E", R.string.core_tools_math_q_energy, "J"),
            Quantity("f", "f", R.string.core_tools_math_q_frequency, "Hz"),
            Quantity("h", "h", R.string.core_tools_math_q_planck, "J·s", "6.62607015e-34"),
        ),
    ),
    Formula(
        "photon_wavelength", Topic.WAVES, R.string.core_tools_math_formula_photon_wavelength,
        "{E} = {h}·{c} / {lam}", "h*c/lam",
        listOf(
            Quantity("E", "E", R.string.core_tools_math_q_energy, "J"),
            Quantity("lam", "λ", R.string.core_tools_math_q_wavelength, "m"),
            Quantity("h", "h", R.string.core_tools_math_q_planck, "J·s", "6.62607015e-34"),
            Quantity("c", "c", R.string.core_tools_math_q_light_speed, "m/s", "299792458"),
        ),
    ),
    Formula(
        "de_broglie", Topic.WAVES, R.string.core_tools_math_formula_de_broglie,
        "{lam} = {h} / ({m}·{v})", "h/(m*v)",
        listOf(
            Quantity("lam", "λ", R.string.core_tools_math_q_wavelength, "m"),
            Quantity("m", "m", R.string.core_tools_math_q_mass, "kg"),
            Quantity("v", "v", R.string.core_tools_math_q_velocity, "m/s"),
            Quantity("h", "h", R.string.core_tools_math_q_planck, "J·s", "6.62607015e-34"),
        ),
    ),
    Formula(
        "snell", Topic.WAVES, R.string.core_tools_math_formula_snell,
        "{na}·sin {ta} = {nb}·sin {tb}", "asin(na*sin(ta)/nb)",
        listOf(
            Quantity("tb", "θ₂", R.string.core_tools_math_q_angle_2, "°"),
            Quantity("na", "n₁", R.string.core_tools_math_q_index_1, "", "1"),
            Quantity("ta", "θ₁", R.string.core_tools_math_q_angle_1, "°"),
            Quantity("nb", "n₂", R.string.core_tools_math_q_index_2, "", "1.333"),
        ),
    ),
    Formula(
        "thin_lens", Topic.WAVES, R.string.core_tools_math_formula_thin_lens,
        "1/{f} = 1/{u} + 1/{v}", "1/(1/u+1/v)",
        listOf(
            Quantity("f", "f", R.string.core_tools_math_q_focal_length, "m"),
            Quantity("u", "u", R.string.core_tools_math_q_object_distance, "m"),
            Quantity("v", "v", R.string.core_tools_math_q_image_distance, "m"),
        ),
    ),
    Formula(
        "magnification", Topic.WAVES, R.string.core_tools_math_formula_magnification,
        "{M} = {v} / {u}", "v/u",
        listOf(
            Quantity("M", "M", R.string.core_tools_math_q_magnification, ""),
            Quantity("v", "v", R.string.core_tools_math_q_image_distance, "m"),
            Quantity("u", "u", R.string.core_tools_math_q_object_distance, "m"),
        ),
    ),
    Formula(
        "specific_heat", Topic.HEAT, R.string.core_tools_math_formula_specific_heat,
        "{Q} = {m}·{c}·{dT}", "m*c*dT",
        listOf(
            Quantity("Q", "Q", R.string.core_tools_math_q_heat, "J"),
            Quantity("m", "m", R.string.core_tools_math_q_mass, "kg"),
            Quantity("c", "c", R.string.core_tools_math_q_specific_heat, "J/(kg·K)", "4184"),
            Quantity("dT", "ΔT", R.string.core_tools_math_q_temp_change, "K"),
        ),
    ),
    Formula(
        "latent_heat", Topic.HEAT, R.string.core_tools_math_formula_latent_heat,
        "{Q} = {m}·{L}", "m*L",
        listOf(
            Quantity("Q", "Q", R.string.core_tools_math_q_heat, "J"),
            Quantity("m", "m", R.string.core_tools_math_q_mass, "kg"),
            Quantity("L", "L", R.string.core_tools_math_q_latent_heat, "J/kg"),
        ),
    ),
    Formula(
        "celsius_kelvin", Topic.HEAT, R.string.core_tools_math_formula_celsius_kelvin,
        "{T} = {tc} + 273.15", "tc+273.15",
        listOf(
            Quantity("T", "T", R.string.core_tools_math_q_kelvin, "K"),
            Quantity("tc", "θ", R.string.core_tools_math_q_celsius, "°C"),
        ),
    ),
    Formula(
        "celsius_fahrenheit", Topic.HEAT, R.string.core_tools_math_formula_celsius_fahrenheit,
        "{tf} = {tc}·9/5 + 32", "tc*9/5+32",
        listOf(
            Quantity("tf", "θF", R.string.core_tools_math_q_fahrenheit, "°F"),
            Quantity("tc", "θC", R.string.core_tools_math_q_celsius, "°C"),
        ),
    ),
    Formula(
        "thermal_expansion", Topic.HEAT, R.string.core_tools_math_formula_thermal_expansion,
        "{dL} = {al}·{L}·{dT}", "al*L*dT",
        listOf(
            Quantity("dL", "ΔL", R.string.core_tools_math_q_length_change, "m"),
            Quantity("al", "α", R.string.core_tools_math_q_expansion, "1/K"),
            Quantity("L", "L", R.string.core_tools_math_q_length, "m"),
            Quantity("dT", "ΔT", R.string.core_tools_math_q_temp_change, "K"),
        ),
    ),
    Formula(
        "ideal_gas", Topic.HEAT, R.string.core_tools_math_formula_ideal_gas,
        "{p}·{V} = {n}·{R}·{T}", "n*R*T/V",
        listOf(
            Quantity("p", "p", R.string.core_tools_math_q_pressure, "Pa"),
            Quantity("V", "V", R.string.core_tools_math_q_volume, "m³"),
            Quantity("n", "n", R.string.core_tools_math_q_amount_substance, "mol"),
            Quantity("T", "T", R.string.core_tools_math_q_temperature, "K"),
            Quantity("R", "R", R.string.core_tools_math_q_gas_constant, "J/(mol·K)", "8.314462618"),
        ),
    ),
    Formula(
        "efficiency", Topic.HEAT, R.string.core_tools_math_formula_efficiency,
        "{eta} = {W} / {Q} × 100", "W/Q*100",
        listOf(
            Quantity("eta", "η", R.string.core_tools_math_q_efficiency, "%"),
            Quantity("W", "W", R.string.core_tools_math_q_work, "J"),
            Quantity("Q", "Q", R.string.core_tools_math_q_heat_in, "J"),
        ),
    ),
    Formula(
        "carnot", Topic.HEAT, R.string.core_tools_math_formula_carnot,
        "{eta} = (1 − {Tc} / {Th}) × 100", "(1-Tc/Th)*100",
        listOf(
            Quantity("eta", "η", R.string.core_tools_math_q_efficiency, "%"),
            Quantity("Tc", "T_c", R.string.core_tools_math_q_cold_temp, "K"),
            Quantity("Th", "T_h", R.string.core_tools_math_q_hot_temp, "K"),
        ),
    ),
    Formula(
        "stefan_boltzmann", Topic.HEAT, R.string.core_tools_math_formula_stefan_boltzmann,
        "{P} = {eps}·{sig}·{A}·{T}⁴", "eps*sig*A*T^4",
        listOf(
            Quantity("P", "P", R.string.core_tools_math_q_power, "W"),
            Quantity("A", "A", R.string.core_tools_math_q_area, "m²"),
            Quantity("T", "T", R.string.core_tools_math_q_temperature, "K"),
            Quantity("eps", "ε", R.string.core_tools_math_q_emissivity, "", "1"),
            Quantity("sig", "σ", R.string.core_tools_math_q_stefan, "W/(m²·K⁴)", "5.670374419e-8"),
        ),
    ),
    Formula(
        "conduction", Topic.HEAT, R.string.core_tools_math_formula_conduction,
        "{P} = {k}·{A}·{dT} / {d}", "k*A*dT/d",
        listOf(
            Quantity("P", "P", R.string.core_tools_math_q_power, "W"),
            Quantity("k", "k", R.string.core_tools_math_q_conductivity, "W/(m·K)"),
            Quantity("A", "A", R.string.core_tools_math_q_area, "m²"),
            Quantity("dT", "ΔT", R.string.core_tools_math_q_temp_change, "K"),
            Quantity("d", "d", R.string.core_tools_math_q_thickness, "m"),
        ),
    ),
    Formula(
        "moles_mass", Topic.CHEMISTRY, R.string.core_tools_math_formula_moles_mass,
        "{n} = {m} / {M}", "m/M",
        listOf(
            Quantity("n", "n", R.string.core_tools_math_q_moles, "mol"),
            Quantity("m", "m", R.string.core_tools_math_q_mass, "g"),
            Quantity("M", "M", R.string.core_tools_math_q_molar_mass, "g/mol"),
        ),
    ),
    Formula(
        "particles", Topic.CHEMISTRY, R.string.core_tools_math_formula_particles,
        "{N} = {n}·{NA}", "n*NA",
        listOf(
            Quantity("N", "N", R.string.core_tools_math_q_particles, ""),
            Quantity("n", "n", R.string.core_tools_math_q_moles, "mol"),
            Quantity("NA", "N_A", R.string.core_tools_math_q_avogadro, "mol⁻¹", "6.02214076e23"),
        ),
    ),
    Formula(
        "molarity", Topic.CHEMISTRY, R.string.core_tools_math_formula_molarity,
        "{c} = {n} / {V}", "n/V",
        listOf(
            Quantity("c", "c", R.string.core_tools_math_q_concentration, "mol/L"),
            Quantity("n", "n", R.string.core_tools_math_q_moles, "mol"),
            Quantity("V", "V", R.string.core_tools_math_q_solution_volume, "L"),
        ),
    ),
    Formula(
        "dilution", Topic.CHEMISTRY, R.string.core_tools_math_formula_dilution,
        "{ca}·{va} = {cb}·{vb}", "ca*va/vb",
        listOf(
            Quantity("cb", "c₂", R.string.core_tools_math_q_conc_2, "mol/L"),
            Quantity("ca", "c₁", R.string.core_tools_math_q_conc_1, "mol/L"),
            Quantity("va", "V₁", R.string.core_tools_math_q_volume_1, "L"),
            Quantity("vb", "V₂", R.string.core_tools_math_q_volume_2, "L"),
        ),
    ),
    Formula(
        "mass_percent", Topic.CHEMISTRY, R.string.core_tools_math_formula_mass_percent,
        "{w} = {ms} / {mt} × 100", "ms/mt*100",
        listOf(
            Quantity("w", "w", R.string.core_tools_math_q_mass_percent, "%"),
            Quantity("ms", "m_solute", R.string.core_tools_math_q_solute_mass, "g"),
            Quantity("mt", "m_solution", R.string.core_tools_math_q_solution_mass, "g"),
        ),
    ),
    Formula(
        "gas_law_atm", Topic.CHEMISTRY, R.string.core_tools_math_formula_gas_law_atm,
        "{p}·{V} = {n}·{R}·{T}", "n*R*T/V",
        listOf(
            Quantity("p", "p", R.string.core_tools_math_q_pressure, "atm"),
            Quantity("V", "V", R.string.core_tools_math_q_volume, "L"),
            Quantity("n", "n", R.string.core_tools_math_q_moles, "mol"),
            Quantity("T", "T", R.string.core_tools_math_q_temperature, "K"),
            Quantity("R", "R", R.string.core_tools_math_q_gas_constant, "L·atm/(mol·K)", "0.08205736608"),
        ),
    ),
    Formula(
        "boyle", Topic.CHEMISTRY, R.string.core_tools_math_formula_boyle,
        "{pa}·{va} = {pb}·{vb}", "pa*va/vb",
        listOf(
            Quantity("pb", "p₂", R.string.core_tools_math_q_pressure_2, "atm"),
            Quantity("pa", "p₁", R.string.core_tools_math_q_pressure_1, "atm"),
            Quantity("va", "V₁", R.string.core_tools_math_q_volume_1, "L"),
            Quantity("vb", "V₂", R.string.core_tools_math_q_volume_2, "L"),
        ),
    ),
    Formula(
        "charles", Topic.CHEMISTRY, R.string.core_tools_math_formula_charles,
        "{va} / {ta} = {vb} / {tb}", "va*tb/ta",
        listOf(
            Quantity("vb", "V₂", R.string.core_tools_math_q_volume_2, "L"),
            Quantity("va", "V₁", R.string.core_tools_math_q_volume_1, "L"),
            Quantity("ta", "T₁", R.string.core_tools_math_q_temp_1, "K"),
            Quantity("tb", "T₂", R.string.core_tools_math_q_temp_2, "K"),
        ),
    ),
    Formula(
        "gay_lussac", Topic.CHEMISTRY, R.string.core_tools_math_formula_gay_lussac,
        "{pa} / {ta} = {pb} / {tb}", "pa*tb/ta",
        listOf(
            Quantity("pb", "p₂", R.string.core_tools_math_q_pressure_2, "atm"),
            Quantity("pa", "p₁", R.string.core_tools_math_q_pressure_1, "atm"),
            Quantity("ta", "T₁", R.string.core_tools_math_q_temp_1, "K"),
            Quantity("tb", "T₂", R.string.core_tools_math_q_temp_2, "K"),
        ),
    ),
    Formula(
        "combined_gas", Topic.CHEMISTRY, R.string.core_tools_math_formula_combined_gas,
        "{pa}·{va} / {ta} = {pb}·{vb} / {tb}", "pa*va*tb/(ta*pb)",
        listOf(
            Quantity("vb", "V₂", R.string.core_tools_math_q_volume_2, "L"),
            Quantity("pa", "p₁", R.string.core_tools_math_q_pressure_1, "atm"),
            Quantity("va", "V₁", R.string.core_tools_math_q_volume_1, "L"),
            Quantity("ta", "T₁", R.string.core_tools_math_q_temp_1, "K"),
            Quantity("pb", "p₂", R.string.core_tools_math_q_pressure_2, "atm"),
            Quantity("tb", "T₂", R.string.core_tools_math_q_temp_2, "K"),
        ),
    ),
    Formula(
        "molar_volume", Topic.CHEMISTRY, R.string.core_tools_math_formula_molar_volume,
        "{V} = {n}·{Vm}", "n*Vm",
        listOf(
            Quantity("V", "V", R.string.core_tools_math_q_volume, "L"),
            Quantity("n", "n", R.string.core_tools_math_q_moles, "mol"),
            Quantity("Vm", "V_m", R.string.core_tools_math_q_molar_volume, "L/mol", "22.41396954"),
        ),
    ),
    Formula(
        "ph", Topic.CHEMISTRY, R.string.core_tools_math_formula_ph,
        "{pH} = −log {H}", "-log(H)",
        listOf(
            Quantity("pH", "pH", R.string.core_tools_math_q_ph, ""),
            Quantity("H", "[H⁺]", R.string.core_tools_math_q_hydrogen, "mol/L"),
        ),
    ),
    Formula(
        "poh", Topic.CHEMISTRY, R.string.core_tools_math_formula_poh,
        "{pOH} = 14 − {pH}", "14-pH",
        listOf(
            Quantity("pOH", "pOH", R.string.core_tools_math_q_poh, ""),
            Quantity("pH", "pH", R.string.core_tools_math_q_ph, ""),
        ),
    ),
    Formula(
        "henderson", Topic.CHEMISTRY, R.string.core_tools_math_formula_henderson,
        "{pH} = {pKa} + log({Am} / {HA})", "pKa+log(Am/HA)",
        listOf(
            Quantity("pH", "pH", R.string.core_tools_math_q_ph, ""),
            Quantity("pKa", "pKa", R.string.core_tools_math_q_pka, ""),
            Quantity("Am", "[A⁻]", R.string.core_tools_math_q_base_conc, "mol/L"),
            Quantity("HA", "[HA]", R.string.core_tools_math_q_acid_conc, "mol/L"),
        ),
    ),
    Formula(
        "gibbs", Topic.CHEMISTRY, R.string.core_tools_math_formula_gibbs,
        "{dG} = {dH} − {T}·{dS}", "dH-T*dS",
        listOf(
            Quantity("dG", "ΔG", R.string.core_tools_math_q_gibbs, "kJ/mol"),
            Quantity("dH", "ΔH", R.string.core_tools_math_q_enthalpy, "kJ/mol"),
            Quantity("T", "T", R.string.core_tools_math_q_temperature, "K"),
            Quantity("dS", "ΔS", R.string.core_tools_math_q_entropy, "kJ/(mol·K)"),
        ),
    ),
    Formula(
        "percent_yield", Topic.CHEMISTRY, R.string.core_tools_math_formula_percent_yield,
        "{y} = {a} / {t} × 100", "a/t*100",
        listOf(
            Quantity("y", "yield", R.string.core_tools_math_q_yield, "%"),
            Quantity("a", "actual", R.string.core_tools_math_q_actual, "g"),
            Quantity("t", "theoretical", R.string.core_tools_math_q_theoretical, "g"),
        ),
    ),
    Formula(
        "arrhenius", Topic.CHEMISTRY, R.string.core_tools_math_formula_arrhenius,
        "{k} = {A}·e^(−{Ea} / ({R}·{T}))", "A*exp(-Ea/(R*T))",
        listOf(
            Quantity("k", "k", R.string.core_tools_math_q_rate_constant, ""),
            Quantity("A", "A", R.string.core_tools_math_q_pre_exponential, ""),
            Quantity("Ea", "Eₐ", R.string.core_tools_math_q_activation, "J/mol"),
            Quantity("T", "T", R.string.core_tools_math_q_temperature, "K"),
            Quantity("R", "R", R.string.core_tools_math_q_gas_constant, "J/(mol·K)", "8.314462618"),
        ),
    ),
)

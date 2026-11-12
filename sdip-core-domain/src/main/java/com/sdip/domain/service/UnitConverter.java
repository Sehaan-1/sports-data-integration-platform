package com.sdip.domain.service;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Ratio-based unit conversion into the canonical units of the metric
 * dictionary. Unknown conversions are rejected loudly (no silent mis-scaling).
 */
@Component
public class UnitConverter {

    private record Key(String from, String to) {
    }

    private static final Map<Key, Double> FACTORS = new HashMap<>();

    static {
        register("km/h", "m/s", 1.0 / 3.6);
        register("m/s", "km/h", 3.6);
        register("km", "m", 1000.0);
        register("mi", "m", 1609.344);
        register("yd", "m", 0.9144);
        register("lb", "kg", 0.45359237);
        register("kg", "lb", 2.20462262);
        register("s", "min", 1.0 / 60.0);
        register("min", "s", 60.0);
        register("h", "min", 60.0);
        register("lbf", "n", 4.448221615260);
    }

    private static void register(String from, String to, double factor) {
        FACTORS.put(new Key(norm(from), norm(to)), factor);
    }

    private static String norm(String unit) {
        return unit == null ? "" : unit.trim().toLowerCase();
    }

    /** Convert {@code value} from {@code fromUnit} to {@code toUnit}. */
    public static double convert(double value, String fromUnit, String toUnit) {
        if (norm(fromUnit).equals(norm(toUnit)) || fromUnit == null || toUnit == null) {
            return value;
        }
        Double factor = FACTORS.get(new Key(norm(fromUnit), norm(toUnit)));
        if (factor == null) {
            throw new IllegalArgumentException(
                    "No registered unit conversion: '" + fromUnit + "' -> '" + toUnit + "'");
        }
        return value * factor;
    }

    public static boolean canConvert(String fromUnit, String toUnit) {
        return norm(fromUnit).equals(norm(toUnit)) || FACTORS.containsKey(new Key(norm(fromUnit), norm(toUnit)));
    }
}

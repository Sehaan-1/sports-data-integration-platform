package com.sdip.domain.link;

import java.text.Normalizer;
import java.util.Locale;

/** Name/string normalization helpers shared by matching and column mapping. */
public final class Names {

    private Names() {
    }

    /** Lower-case, strip diacritics, collapse whitespace. */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** Normalization used for vendor column headers / aliases. */
    public static String normalizeHeader(String header) {
        if (header == null) {
            return "";
        }
        String h = Normalizer.normalize(header, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        h = h.toLowerCase(Locale.ROOT)
                .replace('’', '\'')
                .replaceAll("\\s+", " ")
                .trim();
        return h;
    }

    /** Header with any parenthesised unit annotation removed: "Max Vel (km/h)" → "max vel". */
    public static String stripUnitAnnotation(String normalizedHeader) {
        return normalizedHeader.replaceAll("\\s*\\([^)]*\\)", "").trim();
    }
}

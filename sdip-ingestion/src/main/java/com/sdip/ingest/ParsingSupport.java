package com.sdip.ingest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Locale;

/** Lenient parsers for vendor-produced dates and numbers. */
public final class ParsingSupport {

    private ParsingSupport() {
    }

    private static final DateTimeFormatter FLEX_DATE = new DateTimeFormatterBuilder()
            .appendPattern("[yyyy-MM-dd][dd/MM/yyyy][MM/dd/yyyy][dd-MM-yyyy][yyyy/MM/dd]")
            .toFormatter(Locale.ROOT);

    private static final DateTimeFormatter FLEX_DATE_TIME = new DateTimeFormatterBuilder()
            .appendPattern("[yyyy-MM-dd'T'HH:mm:ss][yyyy-MM-dd'T'HH:mm][yyyy-MM-dd HH:mm:ss][yyyy-MM-dd HH:mm]")
            .optionalStart().appendPattern("X").optionalEnd()
            .toFormatter(Locale.ROOT);

    /** Parse a date or datetime into an Instant (UTC). Dates map to start-of-day. */
    public static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new RowViolationException("missing timestamp value");
        }
        String v = raw.trim();
        try {
            if (v.length() <= 10 && !v.contains("T") && !v.contains(":")) {
                return LocalDate.parse(v, FLEX_DATE).atStartOfDay().toInstant(ZoneOffset.UTC);
            }
            if (v.endsWith("Z") || v.endsWith("z")) {
                v = v.substring(0, v.length() - 1);
            }
            return LocalDateTime.parse(v, FLEX_DATE_TIME).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            throw new RowViolationException("unparseable timestamp: '" + v + "'");
        }
    }

    public static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new RowViolationException("missing date value");
        }
        String v = raw.trim();
        try {
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v, FLEX_DATE);
        } catch (Exception e) {
            throw new RowViolationException("unparseable date: '" + v + "'");
        }
    }

    /** Parse numbers tolerant of thousand separators and stray whitespace. */
    public static Double parseDouble(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (v.isEmpty() || v.equals("-") || v.equalsIgnoreCase("n/a") || v.equalsIgnoreCase("null")) {
            return null;
        }
        v = v.replace(",", "").replace(" ", "");
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new RowViolationException("unparseable number: '" + raw + "'");
        }
    }
}

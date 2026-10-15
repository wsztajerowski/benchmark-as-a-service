package pl.wsztajerowski.baas.console;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** The CSV payloads' values, written the same way by every command; see {@link Json} for why by hand. */
public final class Csv {

    private Csv() {
    }

    /**
     * RFC 4180 quoting. Tag values are free-form — the first CSV field that can carry a comma, a
     * quote or a line break — so this one is always quoted, embedded quotes doubled.
     */
    public static String field(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /** Every tag (or param), key-sorted, as {@code k=v;k=v}. */
    public static String tags(Map<String, String> tags) {
        return new TreeMap<>(tags).entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining(";"));
    }

    /**
     * An unknown value is an empty cell, CSV's form of JSON's {@code null}; never a number. Locale.ROOT,
     * because a comma decimal separator turns one CSV column into two.
     */
    public static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "";
    }
}

package pl.wsztajerowski.baas.console;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The JSON payloads' values, written the same way by every command. Payloads go to the
 * {@link Console}, never the logger, and are built by hand rather than through a mapper because
 * each command's field order is part of its documented output. Everything here formats with
 * {@link Locale#ROOT}: a comma-decimal locale would emit text that is not JSON, silently and only on
 * some machines.
 */
public final class Json {

    private Json() {
    }

    /** A JSON string literal, or the {@code null} literal — absent is not the empty string. */
    public static String string(String value) {
        if (value == null) {
            return "null";
        }
        var out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Key-sorted, so the same map always serialises identically. Tags and params alike. */
    public static String object(Map<String, String> entries) {
        return new TreeMap<>(entries).entrySet().stream()
            .map(e -> string(e.getKey()) + ":" + string(e.getValue()))
            .collect(Collectors.joining(",", "{", "}"));
    }

    /**
     * JSON has no NaN or Infinity literal, and a single-iteration JMH run reports {@code NaN}
     * score error routinely — emitting it produces a document {@code jq} refuses outright.
     */
    public static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "null";
    }
}

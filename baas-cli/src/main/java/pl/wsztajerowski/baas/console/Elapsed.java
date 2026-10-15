package pl.wsztajerowski.baas.console;

import java.util.Locale;

/** A duration as a person reads it in a status line or a table: {@code 3m 07s}, {@code 1h 02m 03s}. */
public final class Elapsed {

    private Elapsed() {
    }

    public static String format(long seconds) {
        long h = seconds / 3600;
        long m = seconds % 3600 / 60;
        long s = seconds % 60;
        return h > 0
            ? String.format(Locale.ROOT, "%dh %02dm %02ds", h, m, s)
            : String.format(Locale.ROOT, "%dm %02ds", m, s);
    }
}

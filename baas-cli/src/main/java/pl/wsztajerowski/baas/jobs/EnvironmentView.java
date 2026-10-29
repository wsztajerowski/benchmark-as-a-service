package pl.wsztajerowski.baas.jobs;

import pl.wsztajerowski.baas.console.Printable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How {@code jobs show} prints a manifest for a person. The manifest stays the raw observation —
 * banners, sysfs syntax, MiB — and {@code jobs diff} and {@code --format json} read it as stored;
 * only this view shortens it. A field without a form here, or whose value a form does not
 * recognise, is printed as {@code <field> <value>}, so a field added by a later schema, or a value
 * an image extension changed, still shows instead of disappearing.
 */
final class EnvironmentView {

    private EnvironmentView() {}

    private static final Pattern PERF = Pattern.compile("^perf version (\\S+)$");
    private static final Pattern ASYNC_PROFILER = Pattern.compile("^Async-profiler (\\S+)");
    private static final Pattern SELECTED = Pattern.compile("\\[(\\S+)]");

    /** Each group with its items, in the manifest's group order; a group with nothing to show is left out. */
    static Map<String, List<String>> of(EnvironmentManifest manifest) {
        Map<String, List<String>> view = new LinkedHashMap<>();
        for (String group : EnvironmentManifest.GROUPS) {
            Map<String, String> fields = new LinkedHashMap<>();
            // Field names come from the stored file as much as values do, so both are filtered.
            manifest.group(group).forEach((field, value) -> fields.put(Printable.of(field), Printable.of(value).strip()));
            List<String> items = new ArrayList<>();
            fields.forEach((field, value) -> {
                if (!value.isEmpty()) {
                    item(group, field, value, fields).ifPresent(items::add);
                }
            });
            if (!items.isEmpty()) {
                view.put(group, items);
            }
        }
        return view;
    }

    /** The item for one non-empty field: its form, the raw {@code field value}, or nothing. */
    private static Optional<String> item(String group, String field, String value, Map<String, String> fields) {
        String raw = field + " " + value;
        boolean vendorVersion = !fields.getOrDefault("vendorVersion", "").isEmpty();
        return switch (group + "." + field) {
            // The Instance line of the Job section already shows it.
            case "machine.instanceType" -> Optional.empty();
            case "memory.totalMib" -> Optional.of(mib(value).map(EnvironmentView::gib).orElse(raw));
            case "memory.swapTotalMib" -> Optional.of(mib(value)
                .map(mib -> mib == 0 ? "no swap" : "swap " + gib(mib)).orElse(raw));
            // Corretto-25.0.4.7.1 names the build exactly; the banner and the release repeat it.
            case "jvm.vendorVersion" -> Optional.of(value.replaceFirst("-", " ")
                + (fields.getOrDefault("vendor", "").isEmpty() ? "" : " (" + fields.get("vendor") + ")"));
            case "jvm.version", "jvm.release", "jvm.vendor" -> vendorVersion ? Optional.empty() : Optional.of(raw);
            case "tools.perf" -> Optional.of(matched(PERF, value).map(v -> "perf " + v).orElse(raw));
            case "tools.asyncProfiler" -> Optional.of(matched(ASYNC_PROFILER, value)
                .map(v -> "async-profiler " + v).orElse(raw));
            case "tunables.transparentHugepages" -> Optional.of(matched(SELECTED, value)
                .map(v -> "thp " + v).orElse(raw));
            default -> Optional.of(raw);
        };
    }

    private static Optional<Long> mib(String value) {
        try {
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String gib(long mib) {
        return String.format(Locale.ROOT, "%.1f GiB", mib / 1024.0);
    }

    private static Optional<String> matched(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}

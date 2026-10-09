package pl.wsztajerowski.baas.results;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What changed between two jobs' {@code packages.txt} ({@code rpm -qa}), compared by package name.
 * Read only when the two AMIs differ: user-data installs nothing, so one AMI is one package set.
 *
 * <p>A line is {@code name-version-release.arch}. A line without an architecture
 * ({@code gpg-pubkey-d832c631-6515c85e}) parses as {@code name-version-release}, and anything else
 * is taken whole as the name — reported as added or removed rather than silently dropped. A name
 * maps to a set of versions, because installonly packages such as the kernel can be present in
 * several.
 */
public record PackagesDiff(List<Change> changed, List<String> added, List<String> removed) {

    private static final Pattern WITH_ARCH = Pattern.compile("^(.+)-([^-]+)-([^-]+)\\.([^.-]+)$");
    private static final Pattern WITHOUT_ARCH = Pattern.compile("^(.+)-([^-]+)-([^-]+)$");

    /** A package present in both lists with a different set of versions. */
    public record Change(String name, String before, String after) {
    }

    public static PackagesDiff of(String before, String after) {
        Map<String, SortedSet<String>> left = parse(before);
        Map<String, SortedSet<String>> right = parse(after);
        List<Change> changed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (var entry : left.entrySet()) {
            var other = right.get(entry.getKey());
            if (other == null) {
                removed.add(entry.getKey() + " " + String.join(", ", entry.getValue()));
            } else if (!other.equals(entry.getValue())) {
                changed.add(new Change(entry.getKey(), String.join(", ", entry.getValue()), String.join(", ", other)));
            }
        }
        for (var entry : right.entrySet()) {
            if (!left.containsKey(entry.getKey())) {
                added.add(entry.getKey() + " " + String.join(", ", entry.getValue()));
            }
        }
        return new PackagesDiff(changed, added, removed);
    }

    public boolean isEmpty() {
        return changed.isEmpty() && added.isEmpty() && removed.isEmpty();
    }

    static Map<String, SortedSet<String>> parse(String packages) {
        Map<String, SortedSet<String>> byName = new TreeMap<>();
        for (String raw : packages.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            Matcher withArch = WITH_ARCH.matcher(line);
            Matcher withoutArch = WITHOUT_ARCH.matcher(line);
            String name;
            String version;
            if (withArch.matches()) {
                name = withArch.group(1);
                version = withArch.group(2) + "-" + withArch.group(3);
            } else if (withoutArch.matches()) {
                name = withoutArch.group(1);
                version = withoutArch.group(2) + "-" + withoutArch.group(3);
            } else {
                name = line;
                version = "";
            }
            byName.computeIfAbsent(name, n -> new TreeSet<>()).add(version);
        }
        return byName;
    }
}

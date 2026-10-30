package pl.wsztajerowski.baas.jobs;

import pl.wsztajerowski.baas.aws.S3UploadService.StoredObject;
import pl.wsztajerowski.baas.model.BenchmarkType;
import pl.wsztajerowski.baas.model.JobLayout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A job prefix's objects as {@code jobs show} prints them: grouped by what they are, one entry per
 * top-level file and one per folder. Names come from {@code baas-model}, so a renamed artifact
 * moves group with it; anything unnamed there — JCStress pages, profiler folders — is a report.
 */
final class ArtifactView {

    private ArtifactView() {}

    /** One printed entry: a file, or a folder with its file count, and the bytes it holds. */
    record Entry(String name, long size) {
    }

    static final List<String> GROUPS = List.of("output", "environment", "results", "reports", "inputs");

    private static final Set<String> OUTPUT = Stream.concat(
            Stream.of(JobLayout.BOOT_LOG_NAME, JobLayout.LAUNCH_ERROR_NAME, JobLayout.LOGS_SEGMENT + "/"),
            // A stored job of a retired profiled type still holds its output under that type's name.
            Stream.concat(Stream.of(BenchmarkType.values()).map(BenchmarkType::processOutputName),
                BenchmarkType.LEGACY_PROCESS_OUTPUT_NAMES.stream()))
        .collect(Collectors.toUnmodifiableSet());
    private static final Set<String> ENVIRONMENT = Set.of(JobLayout.ENVIRONMENT_NAME, JobLayout.PACKAGES_NAME);
    private static final Set<String> RESULTS = Set.of(JobLayout.RESULT_JSON_NAME);
    private static final String INPUTS = JobLayout.INPUT_SEGMENT + "/";

    /**
     * The entries of each non-empty group, in {@link #GROUPS} order; within a group files first,
     * then folders, each by name. {@code objects} carry keys relative to the job prefix.
     */
    static Map<String, List<Entry>> of(List<StoredObject> objects) {
        Map<String, Long> files = new TreeMap<>();
        Map<String, long[]> folders = new TreeMap<>();
        for (StoredObject object : objects) {
            int slash = object.key().indexOf('/');
            if (slash < 0) {
                files.merge(object.key(), object.size(), Long::sum);
            } else {
                long[] countAndSize = folders.computeIfAbsent(object.key().substring(0, slash + 1), k -> new long[2]);
                countAndSize[0]++;
                countAndSize[1] += object.size();
            }
        }
        Map<String, List<Entry>> view = new LinkedHashMap<>();
        GROUPS.forEach(group -> view.put(group, new ArrayList<>()));
        files.forEach((name, size) -> view.get(group(name)).add(new Entry(name, size)));
        folders.forEach((name, countAndSize) -> view.get(group(name)).add(
            new Entry(name + " (" + countAndSize[0] + (countAndSize[0] == 1 ? " file)" : " files)"), countAndSize[1])));
        view.values().removeIf(List::isEmpty);
        return view;
    }

    private static String group(String name) {
        if (OUTPUT.contains(name)) {
            return "output";
        }
        if (ENVIRONMENT.contains(name)) {
            return "environment";
        }
        if (RESULTS.contains(name)) {
            return "results";
        }
        return name.equals(INPUTS) ? "inputs" : "reports";
    }

    /** Binary units, one decimal from KiB up: {@code 812 B}, {@code 4.0 KiB}, {@code 5.2 MiB}. */
    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}

package pl.wsztajerowski.baas.jobs;

import pl.wsztajerowski.baas.cli.CommandContext;
import pl.wsztajerowski.baas.cli.JobReference;
import pl.wsztajerowski.baas.cli.CommandRefusal;
import pl.wsztajerowski.baas.model.JobLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Parameters;
import pl.wsztajerowski.baas.cli.LoggingMixin;
import pl.wsztajerowski.baas.session.BaasConfig;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.console.Printable;
import java.util.stream.Collectors;
import picocli.CommandLine.Option;
import pl.wsztajerowski.baas.console.Json;
import pl.wsztajerowski.baas.console.Table;
import pl.wsztajerowski.baas.console.Table.Cell;
import pl.wsztajerowski.baas.console.Table.Column;
import pl.wsztajerowski.baas.aws.AwsClientFactory;
import pl.wsztajerowski.baas.aws.S3UploadService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(
    name = "diff",
    mixinStandardHelpOptions = true,
    description = "Report how the environments two jobs measured on differ, group by group.",
    footer = {
        "",
        "Each job is named by the id `baas run` printed and `baas jobs list` shows:",
        "  baas jobs diff 20260724T120000000Z-a3f9c21b 20260811T093000000Z-b7e4d0f2",
        "",
        "Only the environment is compared: machine, cpu, memory, os, jdk, runnerJdk,",
        "tools and tunables. When the two AMIs differ, the installed packages are",
        "compared too.",
        "",
        "--format json prints one object: the two job ids and schema versions, every",
        "differing field by group as {left, right} (null where a job has none), and",
        "the package changes, or null when they were not compared."
    }
)
public class JobsDiffSubcommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(JobsDiffSubcommand.class);

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    @Mixin CommandContext context = new CommandContext();

    @Parameters(index = "0", paramLabel = "<jobA>", description = "First job's id.")
    String jobA;

    @Parameters(index = "1", paramLabel = "<jobB>", description = "Second job's id.")
    String jobB;

    @Option(names = "--format", description = "Output format: table (default), json.", defaultValue = "table")
    String format = "table";

    /** Resolves a job id to its stored result path. Overridden by tests, which have no table. */
    JobPaths jobLookup(BaasConfig config, AwsClientFactory factory) {
        return new JobPaths(factory.dynamoDb(), config.resultsTable());
    }

    @Override
    public Integer call() {
        CommandRefusal.requireFormat(format, List.of("table", "json"));
        for (String job : new String[]{jobA, jobB}) {
            String notAJobId = JobReference.notAJobId(job);
            if (notAJobId != null) {
                throw CommandRefusal.usage(notAJobId);
            }
        }
        BaasConfig config = context.operator().config();

        // Read-only, day-to-day: operator credentials, like `run` and `results`.
        var factory = context.operator().factory();
        String bucket = config.bucket();

        String pathA;
        String pathB;
        try (var lookup = jobLookup(config, factory)) {
            // One at a time, so an unknown first job is reported without looking up the second.
            pathA = JobReference.resolve(jobA, lookup::resultPathForJob);
            if (pathA == null) {
                throw CommandRefusal.refused(JobReference.noSuchJob(jobA));
            }
            pathB = JobReference.resolve(jobB, lookup::resultPathForJob);
            if (pathB == null) {
                throw CommandRefusal.refused(JobReference.noSuchJob(jobB));
            }
        }

        EnvironmentManifest a;
        EnvironmentManifest b;
        PackagesDiff packages = null;
        try (var s3 = factory.s3()) {
            var storage = new S3UploadService(s3);
            var fetchedA = fetch(storage, bucket, jobA, pathA);
            var fetchedB = fetch(storage, bucket, jobB, pathB);
            if (fetchedA.isEmpty() || fetchedB.isEmpty()) {
                return 1;
            }
            a = fetchedA.get();
            b = fetchedB.get();
            // Same AMI, same packages: user-data installs nothing, so the lists are read only when
            // the images differ.
            if (!a.amiId().equals(b.amiId())) {
                var listA = storage.getObjectIfExists(bucket, JobLayout.artifactKey(pathA, JobLayout.PACKAGES_NAME));
                var listB = storage.getObjectIfExists(bucket, JobLayout.artifactKey(pathB, JobLayout.PACKAGES_NAME));
                if (listA.isPresent() && listB.isPresent()) {
                    packages = PackagesDiff.of(listA.get(), listB.get());
                } else {
                    logger.warn("The AMIs differ but a packages.txt is missing, so packages are not compared.");
                }
            }
        }

        a.schemaVersion().ifPresent(versionA -> b.schemaVersion().ifPresent(versionB -> {
            if (!versionA.equals(versionB)) {
                // Not a failure: the comparison still holds. But a field that appears or disappears
                // across a schema change is a change in the record, not in the environment.
                logger.warn("Manifests use different schema versions ({} vs {}) — added or removed "
                    + "fields below may reflect that rather than an environment change", versionA, versionB);
            }
        }));

        var differences = EnvironmentManifest.diff(a, b);
        if ("json".equalsIgnoreCase(format)) {
            context.console().println(diffJson(a.schemaVersion().orElse(null), b.schemaVersion().orElse(null),
                differences, packages));
        } else {
            printDiff(context.console(), differences, packages);
        }
        return 0;
    }

    /**
     * Command payload, so the {@link Console} rather than the logger — a timestamp prefix on every
     * line breaks redirecting this to a file. The two jobs' values are coloured apart when the
     * terminal allows it.
     */
    void printDiff(Console out, Map<String, Map<String, EnvironmentManifest.Difference>> differences,
                   PackagesDiff packages) {
        boolean packagesDiffer = packages != null && !packages.isEmpty();
        if (differences.isEmpty() && !packagesDiffer) {
            out.println("No differences. Both jobs measured on the same environment.");
            return;
        }
        List<String> groups = new java.util.ArrayList<>();
        differences.keySet().forEach(group -> groups.add(group.isEmpty() ? "(ungrouped)" : group));
        if (packagesDiffer) {
            groups.add("packages");
        }
        out.println("Differs in: " + String.join(", ", groups));
        if (!differences.isEmpty()) {
            out.println("");
            List<List<String>> rows = new java.util.ArrayList<>();
            differences.forEach((group, fields) -> fields.forEach((field, difference) -> rows.add(List.of(
                group.isEmpty() ? "(ungrouped)" : group, field,
                orAbsent(difference.left()), orAbsent(difference.right())))));
            // Each column grows to its longest value (E5): a CPU model name overflowed its 30 and
            // pushed the next column out of line, and clipping it could hide where the jobs differ.
            int[] widths = {10, 18, 30, 30};
            String[] headers = {"GROUP", "FIELD", jobA, jobB};
            for (int i = 0; i < widths.length; i++) {
                int column = i;
                widths[i] = Math.max(widths[i], Math.max(headers[i].length(), rows.stream()
                    .mapToInt(row -> Printable.of(row.get(column)).length()).max().orElse(0)));
            }
            var table = new Table(out, java.util.Arrays.stream(widths).sum() + widths.length - 1, List.of(
                Column.left(headers[0], widths[0]),
                Column.left(headers[1], widths[1]),
                Column.left(headers[2], widths[2]),
                Column.left(headers[3], widths[3])));
            table.printHeader();
            rows.forEach(row -> table.printRow(List.of(
                Cell.plain(row.get(0)),
                Cell.plain(row.get(1)),
                new Cell(row.get(2), out::red),
                new Cell(row.get(3), out::green))));
        }
        if (packagesDiffer) {
            out.println("");
            out.println("Packages (AMIs differ): " + packages.changed().size() + " changed · "
                + packages.added().size() + " added · " + packages.removed().size() + " removed");
            packages.changed().forEach(change ->
                out.println("  changed  " + change.name() + "  " + change.before() + " → " + change.after()));
            packages.added().forEach(line -> out.println("  added    " + line));
            packages.removed().forEach(line -> out.println("  removed  " + line));
        }
    }

    private Optional<EnvironmentManifest> fetch(S3UploadService storage, String bucket, String jobId, String resultPath) {
        String key = JobLayout.artifactKey(resultPath, JobLayout.ENVIRONMENT_NAME);
        var body = storage.getObjectIfExists(bucket, key);
        if (body.isEmpty()) {
            logger.error("Job {} has no environment.json (s3://{}/{}): a job whose instance never booted "
                + "writes none.", jobId, bucket, key);
            return Optional.empty();
        }
        return Optional.of(EnvironmentManifest.parse(jobId, body.get()));
    }

    /**
     * {@code --format json} (G11): groups and fields in the table's order, an absent value as
     * {@code null}; {@code packages} is null when they were not compared (same AMI, or a list missing).
     */
    String diffJson(String schemaA, String schemaB, Map<String, Map<String, EnvironmentManifest.Difference>> differences,
                    PackagesDiff packages) {
        String groups = differences.entrySet().stream()
            .map(group -> Json.string(group.getKey().isEmpty() ? "(ungrouped)" : group.getKey()) + ":"
                + group.getValue().entrySet().stream()
                    .map(field -> Json.string(field.getKey()) + ":{\"left\":" + nullIfAbsent(field.getValue().left())
                        + ",\"right\":" + nullIfAbsent(field.getValue().right()) + "}")
                    .collect(Collectors.joining(",", "{", "}")))
            .collect(Collectors.joining(",", "{", "}"));
        String packagesJson = packages == null ? "null"
            : "{\"changed\":" + packages.changed().stream()
                    .map(c -> "{\"name\":" + Json.string(c.name()) + ",\"before\":" + Json.string(c.before())
                        + ",\"after\":" + Json.string(c.after()) + "}")
                    .collect(Collectors.joining(",", "[", "]"))
                + ",\"added\":" + packages.added().stream().map(Json::string).collect(Collectors.joining(",", "[", "]"))
                + ",\"removed\":" + packages.removed().stream().map(Json::string).collect(Collectors.joining(",", "[", "]"))
                + "}";
        return "{\"jobs\":[" + Json.string(jobA) + "," + Json.string(jobB) + "]"
            + ",\"schemaVersions\":[" + Json.string(schemaA) + "," + Json.string(schemaB) + "]"
            + ",\"groups\":" + groups + ",\"packages\":" + packagesJson + "}";
    }

    private static String nullIfAbsent(String value) {
        return Json.string(value == null || value.isEmpty() ? null : value);
    }

    /** A field present in only one manifest reads as blank otherwise, which looks like a bug. */
    private static String orAbsent(String value) {
        return value.isEmpty() ? "(absent)" : value;
    }
}

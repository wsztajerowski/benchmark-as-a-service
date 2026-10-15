package pl.wsztajerowski.baas.commands;

import pl.wsztajerowski.baas.cli.Invocation;

import pl.wsztajerowski.baas.model.JobLayout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Parameters;

import pl.wsztajerowski.baas.cli.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.console.Table;
import pl.wsztajerowski.baas.console.Table.Cell;
import pl.wsztajerowski.baas.console.Table.Column;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.OperatorSession;
import pl.wsztajerowski.baas.infra.S3UploadService;
import pl.wsztajerowski.baas.results.EnvironmentManifest;
import pl.wsztajerowski.baas.results.PackagesDiff;
import pl.wsztajerowski.baas.jobs.JobPaths;

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
        "Only the environment is compared: machine, cpu, memory, os, jvm, tools and",
        "tunables. When the two AMIs differ, the installed packages are compared too."
    }
)
public class JobsDiffSubcommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(JobsDiffSubcommand.class);

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    /** Which deployment, as its operator role; opened by {@link #call()}. */
    private OperatorSession session;

    OperatorSession session() {
        if (session == null) {
            session = OperatorSession.of(Invocation.configService(spec));
        }
        return session;
    }

    /** Set by tests; otherwise built from picocli's {@code getOut()} on first use. */
    Console console;

    @Parameters(index = "0", paramLabel = "<jobA>", description = "First job's id.")
    String jobA;

    @Parameters(index = "1", paramLabel = "<jobB>", description = "Second job's id.")
    String jobB;

    /** Resolves a job id to its stored result path. Overridden by tests, which have no table. */
    JobPaths jobLookup(BaasConfig config, AwsClientFactory factory) {
        return new JobPaths(factory.dynamoDb(), config.resultsTable());
    }

    private ConfigService configService() {
        return Invocation.configService(spec);
    }

    @Override
    public Integer call() {
        for (String job : new String[]{jobA, jobB}) {
            String notAJobId = JobReference.notAJobId(job);
            if (notAJobId != null) {
                logger.error("{}", notAJobId);
                return 2;
            }
        }
        BaasConfig config = session().config();

        // Read-only, day-to-day: operator credentials, like `run` and `results`.
        var factory = session().factory();
        String bucket = config.bucket();

        String pathA;
        String pathB;
        try (var lookup = jobLookup(config, factory)) {
            // One at a time, so an unknown first job is reported without looking up the second.
            pathA = JobReference.resolve(jobA, lookup::resultPathForJob);
            if (pathA == null) {
                logger.error("{}", JobReference.noSuchJob(jobA));
                return 1;
            }
            pathB = JobReference.resolve(jobB, lookup::resultPathForJob);
            if (pathB == null) {
                logger.error("{}", JobReference.noSuchJob(jobB));
                return 1;
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

        printDiff(console(), EnvironmentManifest.diff(a, b), packages);
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
            var table = new Table(out, 91, List.of(
                Column.left("GROUP", 10),
                Column.left("FIELD", 18),
                Column.left(jobA, 30),
                Column.left(jobB, 30)));
            table.printHeader();
            differences.forEach((group, fields) -> fields.forEach((field, difference) -> table.printRow(List.of(
                Cell.plain(group.isEmpty() ? "(ungrouped)" : group),
                Cell.plain(field),
                new Cell(orAbsent(difference.left()), out::red),
                new Cell(orAbsent(difference.right()), out::green)))));
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

    private Console console() {
        if (console == null) {
            console = Console.of(spec.commandLine().getOut());
        }
        return console;
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

    /** A field present in only one manifest reads as blank otherwise, which looks like a bug. */
    private static String orAbsent(String value) {
        return value.isEmpty() ? "(absent)" : value;
    }
}

package pl.wsztajerowski.baas.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import pl.wsztajerowski.baas.session.BaasConfig;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.session.OperatorSession;
import pl.wsztajerowski.baas.model.JobStatus;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code baas run} through picocli and {@link RunCommand#call()} — the success path CLAUDE.md
 * recorded as executed by no JVM test — with the operator session adopted from a configuration and
 * every AWS call answered by {@link FakeRunAws}.
 */
class RunCommandLaunchTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private final FakeRunAws aws = new FakeRunAws();
    private final StringWriter out = new StringWriter();
    private int armedHooks;

    private int run(String... args) {
        var config = new BaasConfig();
        config.setPrefix("dev");
        config.getAws().setRegion("eu-central-1");
        var command = new RunCommand();
        command.context.use(OperatorSession.adopting(config, null, Map.of()));
        command.awsFactory = factory -> aws;
        command.context.use(Console.plain(new PrintWriter(out)));
        command.shutdownHooks = hook -> armedHooks++;
        return new CommandLine(command).setExecutionExceptionHandler(pl.wsztajerowski.baas.BaasApp::reportFailure).execute(args);
    }

    private String[] args(String... extra) throws Exception {
        Path jar = Files.writeString(dir.resolve("b.jar"), "jar");
        var base = new java.util.ArrayList<>(java.util.List.of("--benchmark-jar", jar.toString(),
            "--runner-jar", jar.toString(), "--project", "lynx-journal", "--format", "json"));
        base.addAll(java.util.List.of(extra));
        base.addAll(java.util.List.of("jmh", "--", "MyBenchmark", "-f", "1"));
        return base.toArray(String[]::new);
    }

    /** A flag BaaS manages, in any spelling JMH accepts, is refused before anything reaches AWS. */
    @Test
    void aManagedArgumentIsRefusedAfterTheDoubleDashBeforeAnyAwsCall() throws Exception {
        Path jar = Files.writeString(dir.resolve("b.jar"), "jar");

        int exit = run("--benchmark-jar", jar.toString(), "--runner-jar", jar.toString(), "--project", "lynx-journal",
            "jmh", "--", "MyBenchmark", "-pro", "async:dir=/tmp/x");

        assertThat(exit).isEqualTo(2);
        assertThat(aws.events).as("nothing reached AWS").isEmpty();
        assertThat(aws.uploads).isEmpty();
    }

    /** An argument that would measure nothing is refused before a paid boot. */
    @Test
    void aNoRunArgumentIsRefusedAfterTheDoubleDashBeforeAnyAwsCall() throws Exception {
        Path jar = Files.writeString(dir.resolve("b.jar"), "jar");

        int exit = run("--benchmark-jar", jar.toString(), "--runner-jar", jar.toString(), "--project", "lynx-journal",
            "jcstress", "--", "-p", "old.bin.gz");

        assertThat(exit).isEqualTo(2);
        assertThat(aws.events).as("nothing reached AWS").isEmpty();
        assertThat(aws.uploads).isEmpty();
    }

    // ─── --dry-run (G9) ─────────────────────────────────────────────────────────

    private final java.util.List<java.util.List<String>> listings = new java.util.ArrayList<>();
    private final StringWriter err = new StringWriter();

    /**
     * A dry run with no deployment adopted: were the session opened, it would look for a deployment
     * under the test home and fail, so exit 0 proves nothing read one.
     */
    private int dryRun(LocalListing.Result answer, String... args) {
        var command = new RunCommand();
        command.awsFactory = factory -> { throw new AssertionError("a dry run built an AWS client"); };
        command.context.use(Console.plain(new PrintWriter(out)));
        command.localListing = listing -> {
            listings.add(listing);
            return answer;
        };
        return new CommandLine(command).setErr(new PrintWriter(err))
            .setExecutionExceptionHandler(pl.wsztajerowski.baas.BaasApp::reportFailure).execute(args);
    }

    private String[] dryRunArgs(String type, String... params) throws Exception {
        Path jar = Files.writeString(dir.resolve("b.jar"), "jar");
        var all = new java.util.ArrayList<>(java.util.List.of("--dry-run", "--benchmark-jar", jar.toString(), type, "--"));
        all.addAll(java.util.List.of(params));
        return all.toArray(String[]::new);
    }

    @Test
    void aDryRunPrintsWhatWouldRunAndTouchesNoDeployment() throws Exception {
        int exit = dryRun(new LocalListing.Result(0, "Benchmarks: \npl.Summing.sum\n"),
            dryRunArgs("jmh", "Summing", "-f", "1"));

        assertThat(exit).isZero();
        assertThat(out.toString()).isEqualTo("pl.Summing.sum" + System.lineSeparator());
        assertThat(listings).singleElement().satisfies(listing -> assertThat(listing)
            .endsWith("-jar", dir.resolve("b.jar").toString(), "Summing", "-f", "1", "-l"));
    }

    @Test
    void aDryRunThatTheToolRejectsExitsTwo() throws Exception {
        int exit = dryRun(new LocalListing.Result(1, "Error parsing command line:\n Cannot parse argument 'ii' of option w\n"),
            dryRunArgs("jmh", "-f", "1", "-wii", "1"));

        assertThat(exit).isEqualTo(2);
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void aDryRunThatMatchesNothingExitsTwo() throws Exception {
        assertThat(dryRun(new LocalListing.Result(0, "Benchmarks: \n"), dryRunArgs("jmh", "NoSuchBenchmark")))
            .isEqualTo(2);
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void aDryRunRefusesAManagedArgumentBeforeListing() throws Exception {
        assertThat(dryRun(new LocalListing.Result(0, "pl.A.b\n"), dryRunArgs("jmh", "-rf", "csv"))).isEqualTo(2);
        assertThat(listings).isEmpty();
    }

    @Test
    void aDryRunOfAProfilerListingRunsNothingHere() throws Exception {
        assertThat(dryRun(new LocalListing.Result(1, ""), dryRunArgs("jmh", "-lprof"))).isZero();
        assertThat(listings).isEmpty();
    }

    @Test
    void aDryRunTakesNoJsonSummary() throws Exception {
        Path jar = Files.writeString(dir.resolve("b.jar"), "jar");
        int exit = dryRun(new LocalListing.Result(0, "pl.A.b\n"),
            "--dry-run", "--format", "json", "--benchmark-jar", jar.toString(), "jmh", "--", "A");

        assertThat(exit).isEqualTo(2);
        assertThat(listings).isEmpty();
        assertThat(out.toString()).as("no summary object").isEmpty();
    }

    /** A directory passes an existence check and would fail only as the upload of a reserved job. */
    @Test
    void aBenchmarkJarThatIsADirectoryIsRefusedBeforeAnyAwsCall() throws Exception {
        Path notAFile = Files.createDirectory(dir.resolve("b.jar"));

        int exit = run("--benchmark-jar", notAFile.toString(), "--runner-jar", notAFile.toString(),
            "--project", "lynx-journal", "jmh", "--", "MyBenchmark");

        assertThat(exit).isEqualTo(1);
        assertThat(aws.events).as("nothing reached AWS").isEmpty();
    }

    @Test
    void aCompletedJobExitsZeroAndPrintsOneSummaryObject() throws Exception {
        aws.instance = FakeRunAws.Instance.COMPLETED_BEFORE_CONFIRM;

        int exit = run(args());

        assertThat(exit).isZero();
        JsonNode summary = JSON.readTree(out.toString());
        assertThat(summary.get("status").asText()).isEqualTo("completed");
        assertThat(summary.get("jobStatus").asText()).isEqualTo(JobStatus.COMPLETED);
        assertThat(summary.get("project").asText()).isEqualTo("lynx-journal");
        assertThat(summary.get("instanceId").asText()).isEqualTo("i-1");
        assertThat(summary.get("resultPath").asText()).isEqualTo("jobs/lynx-journal/" + summary.get("jobId").asText());
        assertThat(aws.uploads).hasSize(2);
        assertThat(armedHooks).as("the hook is armed once the job is reserved").isEqualTo(1);
    }

    /** The bound connect and show report from; the same value the watchdog sleeps for. */
    @Test
    void theReservationRecordsTheWatchdogBound() throws Exception {
        int exit = run(args("--detach", "--timeout", "600", "--watchdog-margin", "300"));

        assertThat(exit).isZero();
        assertThat(aws.reserved.watchdogSeconds()).isEqualTo(900);
    }

    @Test
    void aDetachedJobIsReportedAsLaunched() throws Exception {
        int exit = run(args("--detach"));

        assertThat(exit).isZero();
        JsonNode summary = JSON.readTree(out.toString());
        assertThat(summary.get("status").asText()).isEqualTo("launched");
        assertThat(summary.get("jobStatus").asText()).isEqualTo(JobStatus.LAUNCHED);
    }

    @Test
    void aRefusedLaunchStillPrintsTheJobIdItFailedUnder() throws Exception {
        aws.instance = FakeRunAws.Instance.REFUSED;

        int exit = run(args());

        assertThat(exit).isEqualTo(1);
        JsonNode summary = JSON.readTree(out.toString());
        assertThat(summary.get("status").asText()).isEqualTo("failed");
        assertThat(summary.get("jobId").isNull()).isFalse();
        assertThat(summary.get("jobStatus").asText()).isEqualTo(JobStatus.LAUNCH_FAILED);
    }

    @Test
    void aReservedTagIsRefusedBeforeAnyAwsCall() throws Exception {
        int exit = run(args("--tag", "jdkVersion=21"));

        assertThat(exit).isNotZero();
        assertThat(aws.events).isEmpty();
        assertThat(armedHooks).isZero();
    }
}

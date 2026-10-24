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
        command.operatorSession = OperatorSession.adopting(config, null, Map.of());
        command.awsFactory = factory -> aws;
        command.console = Console.plain(new PrintWriter(out));
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

    /** G1: the benchmark's arguments come last on the runner's command line, where picocli's last value wins. */
    @Test
    void aRunnerOptionTheCliSetsIsRefusedAfterTheDoubleDashBeforeAnyAwsCall() throws Exception {
        Path jar = Files.writeString(dir.resolve("b.jar"), "jar");

        int exit = run("--benchmark-jar", jar.toString(), "--runner-jar", jar.toString(), "--project", "lynx-journal",
            "jmh", "--", "MyBenchmark", "--tag", "imageVersion=1.0.0");

        assertThat(exit).isEqualTo(2);
        assertThat(aws.events).as("nothing reached AWS").isEmpty();
        assertThat(aws.uploads).isEmpty();
    }

    /** -m is JCStress's mode flag but no runner option; refused before it can fail a paid boot. */
    @Test
    void aShortModeFlagIsRefusedAfterTheDoubleDashBeforeAnyAwsCall() throws Exception {
        Path jar = Files.writeString(dir.resolve("b.jar"), "jar");

        int exit = run("--benchmark-jar", jar.toString(), "--runner-jar", jar.toString(), "--project", "lynx-journal",
            "jcstress", "--", "-m", "sanity");

        assertThat(exit).isEqualTo(2);
        assertThat(aws.events).as("nothing reached AWS").isEmpty();
        assertThat(aws.uploads).isEmpty();
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
        int exit = run(args("--tag", "jdk=21"));

        assertThat(exit).isNotZero();
        assertThat(aws.events).isEmpty();
        assertThat(armedHooks).isZero();
    }
}

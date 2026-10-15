package pl.wsztajerowski.baas.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.session.DeploymentNames;
import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.model.JobStatus;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --detach}: everything up to the confirmed launch is the attached path, and after it the CLI
 * touches nothing. These drive the steps around the launch — the shutdown hook, the branch after
 * {@code confirmLaunched} (in {@link JobLaunch}, reporting to the command as its listener), the
 * summary and the hints — directly; {@code JobLaunchTest} drives a whole launch.
 */
class RunCommandDetachTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final JobItem JOB = new JobItem("20261012T120000000Z-a3f9c21b", "p",
        Instant.parse("2026-10-12T12:00:00Z"), "jobs/p/20261012T120000000Z-a3f9c21b", "c5.2xlarge",
        JobStatus.LAUNCHING, null, null, Map.of(), null);

    /** The job item in memory: the conditional writes the CLI and the instance make. */
    private static final class Recorder implements JobRecorder {
        final List<String> writes = new ArrayList<>();
        String status = JobStatus.LAUNCHING;
        RuntimeException failLaunched;

        @Override
        public void reserve(JobItem job) {
            writes.add("reserve");
        }

        @Override
        public Write launched(JobItem job, String instanceId) {
            if (failLaunched != null) throw failLaunched;
            writes.add("launched");
            if (!JobStatus.LAUNCHING.equals(status)) return Write.REFUSED;
            status = JobStatus.LAUNCHED;
            return Write.WRITTEN;
        }

        @Override
        public Write launchFailed(JobItem job, String errorCode) {
            writes.add("launch-failed");
            return Write.WRITTEN;
        }

        @Override
        public Write stop(JobItem job, String reason) {
            writes.add("stop:" + reason);
            if (JobStatus.isTerminal(status)) return Write.REFUSED;
            status = reason;
            return Write.WRITTEN;
        }

        @Override
        public Optional<JobItem> read(JobItem job) {
            return Optional.of(new JobItem(job.jobId(), job.project(), job.createdAt(), job.resultPath(),
                job.instanceType(), status, null, null, Map.of(), null));
        }

        @Override
        public Optional<JobItem> find(String jobId) {
            return read(JOB);
        }

        @Override
        public List<JobItem> newestFirst(Predicate<JobItem> filter, int limit) {
            return List.of();
        }
    }

    private static final class Instances implements JobSession.Instances {
        final List<String> terminated = new ArrayList<>();

        @Override
        public String state(String instanceId) {
            return "running";
        }

        @Override
        public Optional<String> findLive(String jobId) {
            return Optional.empty();
        }

        @Override
        public void terminate(String instanceId) {
            terminated.add(instanceId);
        }
    }

    private final Recorder recorder = new Recorder();
    private final Instances instances = new Instances();
    private final JobSession session = new JobSession(JOB, recorder, recorder, instances);

    /** The handler with the command as its listener — how a real launch reaches the command. */
    private static JobLaunch launch(RunCommand command) {
        return new JobLaunch(null, DeploymentNames.of("dev"), command);
    }

    private RunCommand command(boolean detach) {
        var command = new RunCommand();
        command.detach = detach;
        command.format = "json";
        command.console = Console.plain(new PrintWriter(new StringWriter()));
        command.session = session;
        return command;
    }

    // ─── parsing ─────────────────────────────────────────────────────────────────

    @Test
    void bothSpellingsAcceptDetach() {
        for (String[] args : List.of(
            new String[] {"run", "--detach", "--benchmark-jar", "b.jar", "jmh", "--", "MyBenchmark"},
            new String[] {"jobs", "run", "--detach", "--benchmark-jar", "b.jar", "jmh", "--", "MyBenchmark"})) {
            var parsed = new CommandLine(new BaasApp()).parseArgs(args);
            while (parsed.hasSubcommand()) {
                parsed = parsed.subcommand();
            }
            assertThat(((RunCommand) parsed.commandSpec().userObject()).detach).isTrue();
        }
    }

    @Test
    void withoutTheFlagARunIsAttached() {
        var parsed = new CommandLine(new BaasApp())
            .parseArgs("run", "--benchmark-jar", "b.jar", "jmh", "--", "MyBenchmark").subcommand();
        assertThat(((RunCommand) parsed.commandSpec().userObject()).detach).isFalse();
    }

    // ─── after the launch ────────────────────────────────────────────────────────

    @Test
    void aConfirmedDetachedLaunchReturnsZeroWithoutPolling() {
        var command = command(true);
        var confirmation = session.confirmLaunched("i-1");

        assertThat(launch(command).afterLaunch(session, confirmation, command.detach)).isZero();
        assertThat(command.summaryJobStatus).isEqualTo(JobStatus.LAUNCHED);
        assertThat(session.ended()).isFalse();
    }

    @Test
    void anUnrecordedDetachedLaunchReportsLaunching() {
        recorder.failLaunched = new RuntimeException("network down");
        var command = command(true);
        var confirmation = session.confirmLaunched("i-1");

        assertThat(confirmation).isEqualTo(JobSession.Confirmation.UNCONFIRMED);
        assertThat(launch(command).afterLaunch(session, confirmation, command.detach)).isZero();
        assertThat(command.summaryJobStatus).isEqualTo(JobStatus.LAUNCHING);
    }

    @Test
    void aDetachedLaunchCancelledWhileLaunchingFails() {
        recorder.status = JobStatus.CANCELLED;
        var command = command(true);
        var confirmation = session.confirmLaunched("i-1");

        assertThat(confirmation).isEqualTo(JobSession.Confirmation.CANCELLED_WHILE_LAUNCHING);
        assertThat(launch(command).afterLaunch(session, confirmation, command.detach)).isEqualTo(1);
        assertThat(instances.terminated).containsExactly("i-1");
    }

    @Test
    void anAttachedLaunchGoesOnToPoll() {
        var command = command(false);
        var confirmation = session.confirmLaunched("i-1");

        assertThat(launch(command).afterLaunch(session, confirmation, command.detach)).isNull();
    }

    // ─── the shutdown hook ───────────────────────────────────────────────────────

    @Test
    void theHookLeavesADetachedJobAlone() {
        var command = command(true);
        launch(command).afterLaunch(session, session.confirmLaunched("i-1"), command.detach);

        command.onShutdown();

        assertThat(recorder.writes).doesNotContain("stop:" + JobStatus.CANCELLED);
        assertThat(instances.terminated).isEmpty();
        assertThat(recorder.status).isEqualTo(JobStatus.LAUNCHED);
    }

    @Test
    void theHookStillCancelsAnAttachedJob() {
        var command = command(false);
        launch(command).afterLaunch(session, session.confirmLaunched("i-1"), command.detach);

        command.onShutdown();

        assertThat(recorder.status).isEqualTo(JobStatus.CANCELLED);
        assertThat(instances.terminated).containsExactly("i-1");
    }

    @Test
    void theHookCancelsADetachedRunInterruptedBeforeItsLaunchWasConfirmed() {
        var command = command(true);

        command.onShutdown();

        assertThat(recorder.status).isEqualTo(JobStatus.CANCELLED);
    }

    // ─── what it reports ─────────────────────────────────────────────────────────

    private static JsonNode summary(RunCommand command, int exitCode) throws Exception {
        var out = new StringWriter();
        command.console = Console.plain(new PrintWriter(out));
        command.printJobSummary(exitCode);
        return JSON.readTree(out.toString());
    }

    @Test
    void aDetachedLaunchIsReportedAsLaunchedNotCompleted() throws Exception {
        var command = command(true);
        command.summaryJobId = JOB.jobId();
        command.summaryDeployment = "dev";
        command.summaryInstanceId = "i-1";
        launch(command).afterLaunch(session, session.confirmLaunched("i-1"), command.detach);

        JsonNode parsed = summary(command, 0);

        assertThat(parsed.get("status").asText()).isEqualTo("launched");
        assertThat(parsed.get("jobStatus").asText()).isEqualTo(JobStatus.LAUNCHED);
        assertThat(parsed.get("exitCode").asInt()).isZero();
        assertThat(parsed.get("instanceId").asText()).isEqualTo("i-1");
    }

    @Test
    void aDetachedLaunchThatFailedIsReportedAsFailed() throws Exception {
        var command = command(true);

        assertThat(summary(command, 1).get("status").asText()).isEqualTo("failed");
    }

    @Test
    void anAttachedSuccessIsStillReportedAsCompleted() throws Exception {
        assertThat(summary(command(false), 0).get("status").asText()).isEqualTo("completed");
    }

    @Test
    void theSummaryKeepsTheDetachedStatusRatherThanTheSessionsEmptyEnd() {
        var command = command(true);
        launch(command).afterLaunch(session, session.confirmLaunched("i-1"), command.detach);

        command.recordEndStatus();

        assertThat(command.summaryJobStatus).isEqualTo(JobStatus.LAUNCHED);
    }

    @Test
    void aDetachedJobIsFollowedNotQueried() {
        var command = command(true);
        command.summaryJobId = JOB.jobId();
        command.summaryDeployment = "dev";
        launch(command).afterLaunch(session, session.confirmLaunched("i-1"), command.detach);
        command.console = Console.withFlags(new PrintWriter(new StringWriter()), true, false);
        List<String> lines = new ArrayList<>();

        command.hints(0, lines::add);

        assertThat(lines).containsExactly(
            "→ follow it: baas --deployment dev jobs show " + JOB.jobId(),
            "→ stop it: baas --deployment dev jobs terminate " + JOB.jobId());
    }

    @Test
    void anAttachedSuccessStillPointsAtItsMeasurements() {
        var command = command(false);
        command.summaryJobId = JOB.jobId();
        command.summaryDeployment = "dev";
        command.console = Console.withFlags(new PrintWriter(new StringWriter()), true, false);
        List<String> lines = new ArrayList<>();

        command.hints(0, lines::add);

        assertThat(lines).containsExactly(
            "→ measurements: baas --deployment dev query --job-id " + JOB.jobId(),
            "→ artifacts: baas --deployment dev jobs download " + JOB.jobId());
    }
}

package pl.wsztajerowski.baas.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.model.JobStatus;
import pl.wsztajerowski.baas.session.BaasConfig;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** `jobs connect`: which jobs it reaches, what it refuses before any session, and what it hands the plugin. */
class JobsConnectCommandTest {

    private static final String ID = "20261022T120000000Z-a3f9c21b";
    private static final String INSTANCE = "i-0a5605864eb16d146";
    private static final String DOCUMENT = "baas-123456789012-session-runner";

    @TempDir Path dir;

    /** Every AWS call the fake answered, in order. */
    private final List<String> calls = new ArrayList<>();
    private final List<List<String>> launched = new ArrayList<>();

    private JobItem job;
    private Ec2ProvisioningService.LiveInstance live;
    private boolean online = true;
    private RuntimeException startFailure;
    private int pluginExit;
    /** Instance-state answers while the session is open, one per check; live once they run out. */
    private final java.util.Deque<Boolean> liveness = new java.util.ArrayDeque<>();
    /** The fake plugin's run: exits at once unless {@link #hangs}, as a plugin whose target vanished does. */
    private boolean hangs;
    private boolean exitsWhenSessionEnds = true;
    private boolean destroyed;
    private SessionLauncher.Runner runner = command -> {
        launched.add(command);
        return new SessionLauncher.Attached() {
            public boolean waitFor(java.time.Duration timeout) {
                if (!hangs) return true;
                return exitsWhenSessionEnds && calls.stream().anyMatch(call -> call.startsWith("terminate"));
            }
            public int exitValue() { return pluginExit; }
            public void destroy() { destroyed = true; }
        };
    };

    private record Run(int exit, String out, String err) {}

    private Path pluginDir() throws Exception {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path plugin = bin.resolve(SessionPlugin.NAME);
        if (!Files.exists(plugin)) {
            Files.writeString(plugin, "#!/bin/sh\nexit 0\n");
            Files.setPosixFilePermissions(plugin, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        return bin;
    }

    private Run connect(String searchPath) throws Exception {
        pl.wsztajerowski.baas.TestDeployments.write(dir, pl.wsztajerowski.baas.TestDeployments.DEFAULT);
        var out = new StringWriter();
        var err = new StringWriter();
        CommandLine.IFactory defaults = CommandLine.defaultFactory();
        CommandLine.IFactory factory = new CommandLine.IFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <K> K create(Class<K> cls) throws Exception {
                if (cls != JobsConnectSubcommand.class) {
                    return defaults.create(cls);
                }
                var command = new JobsConnectSubcommand() {
                    @Override
                    Sources sources(BaasConfig config) {
                        calls.add("sources");
                        return fake();
                    }
                };
                command.console = Console.plain(new PrintWriter(out, true));
                command.searchPath = searchPath;
                command.runner = runner;
                command.instanceCheck = java.time.Duration.ofMillis(1);
                command.pluginGrace = java.time.Duration.ofMillis(1);
                return (K) command;
            }
        };
        // A refusal is logged through BaasApp.reportFailure, which writes to System.err.
        var logged = new ByteArrayOutputStream();
        PrintStream original = System.err;
        int exit;
        try {
            System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
            exit = BaasApp.commandLine(new BaasApp(dir), factory).setErr(new PrintWriter(err, true))
                .setExecutionExceptionHandler(BaasApp::reportFailure)
                .execute("jobs", "connect", ID);
        } finally {
            System.setErr(original);
        }
        return new Run(exit, out.toString(), logged.toString(StandardCharsets.UTF_8) + err);
    }

    private Run connect() throws Exception {
        return connect(pluginDir().toString());
    }

    private JobsConnectSubcommand.Sources fake() {
        return new JobsConnectSubcommand.Sources() {
            public String region() { return "eu-central-1"; }
            public Optional<JobItem> job(String id) { calls.add("job"); return Optional.ofNullable(job); }
            public Optional<Ec2ProvisioningService.LiveInstance> liveInstance(String id) { calls.add("liveInstance"); return Optional.ofNullable(live); }
            public boolean agentOnline(String instanceId) { calls.add("agentOnline " + instanceId); return online; }
            public boolean instanceLive(String instanceId) {
                calls.add("instanceLive");
                return liveness.isEmpty() || liveness.poll();
            }
            public SessionLauncher.Started start(String instanceId, String document) {
                calls.add("start " + instanceId + " " + document);
                if (startFailure != null) throw startFailure;
                return new SessionLauncher.Started("baas-cli-0abc", "wss://ssmmessages.eu-central-1.amazonaws.com/v1/data-channel/baas-cli-0abc", "token-1");
            }
            public void terminate(String sessionId) { calls.add("terminate " + sessionId); }
            public void close() { }
        };
    }

    private static JobItem job(String status, Integer watchdogSeconds) {
        return new JobItem(ID, "baas-e2e", Instant.parse("2026-10-22T12:00:00Z"), "jobs/baas-e2e/" + ID, "c5.large",
            status, INSTANCE, null, Map.of("type", "jmh"), null, watchdogSeconds);
    }

    private void runningJob() {
        job = job(JobStatus.RUNNING, 3600);
        live = new Ec2ProvisioningService.LiveInstance(INSTANCE, Instant.now().minusSeconds(600));
    }

    @Test
    void aRunningJobOpensASessionWithTheDeploymentsDocument() throws Exception {
        runningJob();

        var run = connect();

        assertThat(run.exit()).isZero();
        assertThat(calls).containsExactly("sources", "job", "liveInstance", "agentOnline " + INSTANCE,
            "start " + INSTANCE + " " + DOCUMENT);
        assertThat(launched).hasSize(1);
    }

    @Test
    void aMissingPluginIsRefusedBeforeAnyAwsCall() throws Exception {
        runningJob();

        var run = connect(Files.createDirectories(dir.resolve("empty")).toString());

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.err()).contains(SessionPlugin.NAME, "Nothing was started");
        assertThat(calls).as("not even the operator session's sources").isEmpty();
        assertThat(launched).isEmpty();
    }

    @Test
    void anUnknownIdStartsNoSession() throws Exception {
        var run = connect();

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.err()).contains("No job found with id '" + ID + "'");
        assertThat(calls).noneMatch(call -> call.startsWith("start"));
    }

    @Test
    void aJobStillReservingHasNothingToConnectTo() throws Exception {
        job = new JobItem(ID, "baas-e2e", Instant.parse("2026-10-22T12:00:00Z"), "jobs/baas-e2e/" + ID, "c5.large",
            JobStatus.LAUNCHING, null, null, Map.of(), null, 3600);

        var run = connect();

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.err()).contains("stored status: launching");
        assertThat(calls).noneMatch(call -> call.startsWith("start"));
    }

    @Test
    void aFinishedJobIsRefusedNamingItsStatus() throws Exception {
        job = job(JobStatus.COMPLETED, 3600);

        var run = connect();

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.err()).contains("no pending or running instance", "stored status: completed");
        assertThat(calls).noneMatch(call -> call.startsWith("start"));
    }

    /** The instance decides, not the status: a job stuck before `running` is the one worth a look. */
    @Test
    void aJobStillLaunchedIsReachedWhenItsInstanceIsLive() throws Exception {
        runningJob();
        job = job(JobStatus.LAUNCHED, 3600);

        var run = connect();

        assertThat(run.exit()).isZero();
        assertThat(launched).hasSize(1);
    }

    @Test
    void anInstanceWhoseAgentHasNotRegisteredIsNotReachableYet() throws Exception {
        runningJob();
        online = false;

        var run = connect();

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.err()).contains("not reachable yet", "Retry shortly");
        assertThat(calls).noneMatch(call -> call.startsWith("start"));
    }

    @Test
    void theDeadlineIsPrintedBeforeTheShellOpens() throws Exception {
        runningJob();

        var run = connect();

        assertThat(run.out()).containsPattern("watchdog terminates it by about \\d\\d:\\d\\d UTC \\(in (49|50) min\\)");
    }

    @Test
    void aJobWithoutARecordedBoundGetsTheGenericWarning() throws Exception {
        runningJob();
        job = job(JobStatus.RUNNING, null);

        var run = connect();

        assertThat(run.out()).contains("terminates when the job finishes or its watchdog fires")
            .doesNotContain("UTC");
        assertThat(launched).hasSize(1);
    }

    @Test
    void aDeploymentWithoutTheGrantsIsToldToRerunSetup() throws Exception {
        runningJob();
        startFailure = AwsServiceException.builder().message("not authorized to perform: ssm:StartSession")
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDeniedException").build()).build();

        var run = connect();

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.err()).contains("baas admin deployment setup");
    }

    @Test
    void aDeploymentWithoutTheDocumentIsToldToRerunSetup() throws Exception {
        runningJob();
        startFailure = new JobsConnectSubcommand.Sources.Refused("Document not found", null);

        var run = connect();

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.err()).contains("baas admin deployment setup");
    }

    @Test
    void aFailedPluginRunEndsTheSessionAndReturnsItsExitCode() throws Exception {
        runningJob();
        pluginExit = 255;

        var run = connect();

        assertThat(run.exit()).isEqualTo(255);
        assertThat(calls).endsWith("terminate baas-cli-0abc");
    }

    @Test
    void aCleanExitLeavesTheSessionToThePlugin() throws Exception {
        runningJob();

        connect();

        assertThat(calls).noneMatch(call -> call.startsWith("terminate"));
    }

    /** Session Manager leaves a session open when its target terminates; connect must not hang with it. */
    @Test
    void anInstanceThatTerminatesEndsTheSession() throws Exception {
        runningJob();
        hangs = true;
        liveness.addAll(List.of(true, true, false));

        var run = connect();

        assertThat(run.exit()).isZero();
        assertThat(run.out()).contains("Instance " + INSTANCE + " has terminated; ending the session.");
        assertThat(calls).containsSubsequence("instanceLive", "instanceLive", "instanceLive", "terminate baas-cli-0abc");
        assertThat(destroyed).as("the plugin left on its own once its session ended").isFalse();
    }

    @Test
    void aPluginThatDoesNotLeaveAfterTheSessionEndsIsStopped() throws Exception {
        runningJob();
        hangs = true;
        exitsWhenSessionEnds = false;
        liveness.add(false);

        var run = connect();

        assertThat(run.exit()).isZero();
        assertThat(destroyed).isTrue();
    }

    @Test
    void aLiveInstanceKeepsTheSessionOpen() throws Exception {
        runningJob();

        connect();

        assertThat(calls).noneMatch(call -> call.startsWith("terminate"));
    }

    /**
     * The real launcher, against a stub plugin that records what it was given: the AWS CLI's six
     * arguments and nothing else, so no credential travels with them.
     */
    @Test
    void thePluginGetsTheAwsCliArgumentsAndNoCredential() throws Exception {
        runningJob();
        Path bin = Files.createDirectories(dir.resolve("stub"));
        Path seen = dir.resolve("args.txt");
        Path plugin = bin.resolve(SessionPlugin.NAME);
        Files.writeString(plugin, "#!/bin/sh\nfor a in \"$@\"; do printf '%s\\n' \"$a\"; done > '" + seen + "'\nexit 3\n");
        Files.setPosixFilePermissions(plugin, PosixFilePermissions.fromString("rwxr-xr-x"));
        runner = SessionLauncher.INHERIT_TERMINAL;

        var run = connect(bin.toString());

        assertThat(run.exit()).isEqualTo(3);
        List<String> lines = Files.readAllLines(seen);
        assertThat(lines).containsExactly(
            "{\"SessionId\":\"baas-cli-0abc\",\"TokenValue\":\"token-1\",\"StreamUrl\":"
                + "\"wss://ssmmessages.eu-central-1.amazonaws.com/v1/data-channel/baas-cli-0abc\"}",
            "eu-central-1", "StartSession", "",
            "{\"Target\":\"" + INSTANCE + "\",\"DocumentName\":\"" + DOCUMENT + "\"}",
            "https://ssm.eu-central-1.amazonaws.com");
    }
}

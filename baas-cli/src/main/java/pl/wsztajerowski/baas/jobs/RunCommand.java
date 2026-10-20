package pl.wsztajerowski.baas.jobs;

import pl.wsztajerowski.baas.session.GitProject;
import pl.wsztajerowski.baas.cli.Invocation;
import pl.wsztajerowski.baas.console.Json;
import pl.wsztajerowski.baas.model.BenchmarkType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.cli.BaasVersion;
import pl.wsztajerowski.baas.cli.LoggingMixin;
import pl.wsztajerowski.baas.session.BaasConfig;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.console.Hints;
import pl.wsztajerowski.baas.console.StatusLine;
import pl.wsztajerowski.baas.aws.AwsClientFactory;
import pl.wsztajerowski.baas.session.OperatorSession;
import pl.wsztajerowski.baas.model.RunnerArgs;
import pl.wsztajerowski.baas.model.JobStatus;
import pl.wsztajerowski.baas.model.TagKeys;
import pl.wsztajerowski.baas.results.ResultRow;
import pl.wsztajerowski.baas.results.ResultsTable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

@Command(
    name = "run",
    mixinStandardHelpOptions = true,
    description = "Launch an EC2 runner for a pre-built benchmark JAR, and poll for results.",
    // Lines are kept under 80 columns: picocli wraps the footer at the usage width and
    // re-wrapping mid-sentence makes the -- rule harder to read than no footer at all.
    footer = {
        "",
        "Put -- before the benchmark parameters. Everything after it goes verbatim",
        "to benchmark-runner.jar. Without it, JMH flags are parsed as baas options",
        "and the command fails with: Unknown options: '-f', '-wi', '-i'",
        "",
        "  baas run --benchmark-jar target/b.jar --project my-bench \\",
        "    jmh -- MyBenchmark -f 1 -wi 1 -i 3",
        "  baas run --benchmark-jar target/b.jar --project my-bench \\",
        "    --instance-type c6i.4xlarge jmh -- MyBenchmark -f 1",
        "",
        "--project can be derived from the JAR's git repository instead:",
        "  baas config set --git-resolve-project true",
        "",
        "Measurements and the job's status go to the DynamoDB results table; see",
        "them with baas results query and baas jobs list. S3 receives process output,",
        "logs, profiler artifacts and the verbatim result JSON. An unresolvable",
        "table fails before anything is launched."
    },
    separator = " "
)
public class RunCommand implements Callable<Integer>, JobLaunch.Listener {

    private static final Logger logger = LoggerFactory.getLogger(RunCommand.class);

    private static final List<String> VALID_TYPES = BenchmarkType.commandNames();

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    /** Which deployment, as its operator role; opened by {@link #call()}. */
    OperatorSession operatorSession;

    OperatorSession operatorSession() {
        if (operatorSession == null) {
            operatorSession = OperatorSession.of(Invocation.configService(spec));
        }
        return operatorSession;
    }

    /** Replaced in tests, so a test JVM does not exit with a hook per launch it drove. */
    Consumer<Thread> shutdownHooks = Runtime.getRuntime()::addShutdownHook;

    /** Replaced in tests: the AWS a launch talks to, given the operator session's clients. */
    Function<AwsClientFactory, RunAws> awsFactory = AwsRunAws::new;

    /** Set by tests; otherwise built from picocli's {@code getOut()} on first use. */
    Console console;

    /**
     * The poll loop's status line while one is shown. Volatile because the shutdown hook reads it
     * from another thread to clear the line before logging the termination.
     */
    private volatile StatusLine statusLine;

    /**
     * The job's lifecycle once it has been named — reserve, launch, poll, stop. Volatile because
     * the shutdown hook reads it from another thread.
     */
    volatile JobSession session;

    /**
     * Set once a {@code --detach} launch is confirmed: from then on the job ends on its instance
     * alone, and the shutdown hook must not cancel it. Volatile for the same reason as
     * {@link #session}.
     */
    volatile boolean detached;

    @Parameters(index = "0", paramLabel = "<type>",
        completionCandidates = BenchmarkTypes.class,
        description = "Benchmark type: ${COMPLETION-CANDIDATES}.")
    String benchmarkType;

    @Parameters(index = "1..*", paramLabel = "PARAMS",
        description = "Parameters forwarded to benchmark-runner.jar. Must follow a -- separator.")
    List<String> benchmarkParams = new ArrayList<>();

    @Option(names = "--benchmark-jar", required = true,
        description = "Path to the pre-built benchmark JAR. Required — baas run builds nothing.")
    Path benchmarkJar;

    @Option(names = "--runner-jar", description = "Local runner JAR to upload for this job instead of "
        + "pinning the release matching this CLI's version. Required from an unreleased build.")
    Path runnerJar;

    @Option(names = "--instance-type", description = "EC2 instance type (overrides config default).")
    String instanceType;

    @Option(names = "--timeout", description = "Benchmark process timeout in seconds.")
    Integer timeoutSeconds;

    @Option(names = "--watchdog-margin",
        description = "Seconds the self-termination watchdog waits beyond --timeout (overrides config "
            + "default; minimum " + BaasConfig.MIN_WATCHDOG_MARGIN_SECONDS + ").")
    Integer watchdogMarginSeconds;

    @Option(names = "--tag", description = "Tag recorded on the stored benchmark result (key=value), not just "
        + "the EC2 instance — including branch and commit, which are never derived. Rejected for "
        + "machine-observed keys (imageVersion, instanceType, jdk, jvmVendor, cpuModel, cpuArch, type) — those are "
        + "captured on the instance so a result's tags can't disagree with its own environment.json — "
        + "and for project, which --project names.")
    Map<String, String> extraTags = new LinkedHashMap<>();

    @Option(names = "--project", description = "Project name for the results partition. Required unless "
        + "git.resolveProject is enabled, which derives it from the repository holding --benchmark-jar.")
    String project;

    // No --image-version and no --ami-id: exactly one image is maintained, so there is nothing to
    // select between, and an override could only name an image whose results are not comparable.
    @Option(names = "--format", defaultValue = "text",
        description = "Job summary format: text (default) or json. json writes one object to "
            + "standard output — on the failure path too, which is when the job id is most "
            + "needed — while diagnostics stay on standard error.")
    String format;

    @Option(names = "--detach",
        description = "Return once the instance is launched instead of waiting for the job to end. "
            + "The job then ends on its instance alone, bounded by its watchdog: follow it with "
            + "baas jobs show <jobId>, stop it with baas jobs terminate <jobId>.")
    boolean detach;


    /**
     * What the JSON summary reports. Populated as the job reaches each fact rather than assembled
     * at the end, because the failure path has to be able to print whatever is known so far: a job
     * that died after launching still has an id, and that id is the whole point of the option.
     */
    String summaryJobId;
    /** The deployment the job runs on, for the hints that follow it. */
    String summaryDeployment;
    String summaryProject;
    String summaryResultPath;
    String summaryInstanceId;
    /** The job item's status as the job ended; see {@link JobSession#endStatus()}. */
    String summaryJobStatus;

    boolean jsonSummary() {
        return "json".equalsIgnoreCase(format);
    }


    @Override
    public Integer call() throws Exception {
        int exitCode = 1;
        try {
            exitCode = execute();
            return exitCode;
        } finally {
            // In a finally so it also covers the paths that throw — resolveProject() and
            // resolveResultsTable() both do. Those fail before a job exists, so the object
            // carries nulls; a consumer still gets one parseable object saying it failed rather
            // than empty output it has to special-case.
            if (jsonSummary()) {
                recordEndStatus();
                printJobSummary(exitCode);
            }
            hints(exitCode, logger::info);
        }
    }

    /**
     * The summary's {@code jobStatus}: the status the job ended with. A detached job has not ended —
     * {@link #detached} recorded what the item said at the launch, and the session's empty end
     * status must not overwrite it.
     */
    void recordEndStatus() {
        var ended = session;
        if (ended != null && !detached) {
            summaryJobStatus = ended.endStatus();
        }
    }


    /** Where to look next, once a job exists; interactive terminals only (see {@link Hints}). */
    void hints(int exitCode, Consumer<String> sink) {
        // In the finally: a command built outside picocli (a unit test) has no spec to print through,
        // and a hint must never replace the outcome being reported.
        if (summaryJobId == null || (console == null && spec == null)) {
            return;
        }
        String d = summaryDeployment;
        if (exitCode == 0 && detached) {
            // Nothing has been measured yet: the job is followed, not queried.
            Hints.show(console(), sink, "follow it", Hints.command(d, "jobs show " + summaryJobId),
                "stop it", Hints.command(d, "jobs terminate " + summaryJobId));
        } else if (exitCode == 0) {
            Hints.show(console(), sink, "measurements", Hints.command(d, "query --job-id " + summaryJobId),
                "artifacts", Hints.command(d, "jobs download " + summaryJobId));
        } else {
            Hints.show(console(), sink, "what happened", Hints.command(d, "jobs show " + summaryJobId),
                "boot log", Hints.command(d, "jobs download " + summaryJobId));
        }
    }

    private Integer execute() throws Exception {
        // Anything but json used to mean text, so `--format jsno` silently printed no summary
        // for a pipeline expecting one.
        if (format != null && !"text".equalsIgnoreCase(format) && !jsonSummary()) {
            logger.error("Unknown --format '{}'. Valid: text, json.", format);
            return 2;
        }
        if (!VALID_TYPES.contains(benchmarkType)) {
            logger.error("Unknown benchmark type '{}'. Valid: {}", benchmarkType, VALID_TYPES);
            return 2;
        }
        Optional<String> setByTheCli = RunnerArgs.firstSetByTheCli(benchmarkParams);
        if (setByTheCli.isPresent()) {
            logger.error("{} after -- names a runner option baas run sets itself. {}\nNothing was launched.",
                setByTheCli.get(), insteadOf(setByTheCli.get()));
            return 2;
        }

        // Checked first, before resolving the project, the results table, the runner image or
        // anything else: a job that cannot name the runner JAR it will execute is going to fail
        // anyway, and there is deliberately no fallback — two provisioning paths produce silently
        // incomparable results.
        if (runnerJar == null && !BaasVersion.isReleased()) {
            logger.error("""
                This is an unreleased build ({}), so there is no runner release to pin to.
                  Run against a local build:  baas run --runner-jar <path> ...
                Nothing was built or launched.""", BaasVersion.current());
            return 1;
        }

        BaasConfig config = operatorSession().config();

        // Resolved before any AWS call — the runner-image lookup and the S3 upload both come later
        // in this method, and neither should run for a request that is going to fail anyway because
        // it can't be attributed to a project. resolveProject() throws IllegalStateException with a
        // message naming --project when none was passed and none can be derived, or when the name
        // holds a character a GitHub repository name cannot.
        String resolvedProject = resolveProject(config);
        summaryProject = resolvedProject;

        // Same reasoning as resolveProject() above, and deliberately before the runner-image lookup
        // and the upload: a job that cannot say where its measurements go is going to fail anyway.
        // The launch reads the table from the deployment's names; this only refuses an unconfigured one early.
        resolveResultsTable(config);
        String resolvedInstanceType = instanceType != null ? instanceType : config.getEc2().getDefaultInstanceType();
        Timings timings = resolveTimings(config);
        // Before any AWS call, like every other check on what the caller typed: a reserved or
        // project tag used to be refused only after the JAR upload, leaving an input/ in S3 that
        // no job item names and nothing expires.
        Map<String, String> runnerTags = buildRunnerTags(benchmarkType, resolvedProject);
        int resolvedTimeout = timings.timeoutSeconds();
        int resolvedWallClock = timings.watchdogSeconds();
        logger.debug("Resolved job parameters: instanceType={}, timeout={}s, watchdog={}s, project={}, params={}",
            resolvedInstanceType, resolvedTimeout, resolvedWallClock, resolvedProject, benchmarkParams);

        // The JAR is named, never derived. Checked before the image lookup and before any upload,
        // like every other precondition this command has.
        if (!benchmarkJar.toFile().exists()) {
            logger.error("Benchmark JAR not found: {}\nBuild it first, then pass --benchmark-jar.",
                benchmarkJar);
            return 1;
        }

        summaryDeployment = config.getPrefix();
        var request = new JobLaunch.Request(benchmarkType, benchmarkParams, benchmarkJar, runnerJar,
            resolvedInstanceType, resolvedProject, resolvedTimeout, resolvedWallClock,
            runnerTags, detach, BaasVersion.current(), BaasVersion.sourceRepo(), BaasVersion.runnerSha256());
        return new JobLaunch(awsFactory.apply(operatorSession().factory()), config.names(), this).run(request);
    }

    /**
     * The shutdown hook's body. Armed through the launch, so an interrupt while {@code RunInstances}
     * is in flight cancels a detached job too; once a detached launch is confirmed the job is the
     * instance's alone, and {@code baas jobs terminate} is what stops it (ADR 0007).
     */
    void onShutdown() {
        // Cleared first, or the termination message would be drawn over by the status line.
        var line = statusLine;
        if (line != null) {
            line.close();
        }
        var active = session;
        if (active == null) {
            return;
        }
        if (detached) {
            logger.debug("Job {} is detached; instance {} ends it.", active.job().jobId(), active.instanceId());
        } else if (!active.ended()) {
            active.stop(JobStatus.CANCELLED);
        } else {
            logger.debug("Job ended; instance {} terminates itself.", active.instanceId());
        }
    }

    /**
     * A status line only on an interactive terminal and never under {@code --format json}: there
     * standard output belongs to the summary object, and progress stays on the log lines CI reads.
     * {@code null} otherwise, which the poll loop takes as "log as before".
     */
    @Override
    public StatusLine openStatusLine() {
        if (jsonSummary()) {
            return null;
        }
        statusLine = console().openStatusLine();
        return statusLine;
    }



    private Console console() {
        if (console == null) {
            console = Console.of(spec.commandLine().getOut());
        }
        return console;
    }


    /**
     * {@code --project}, else — only when the operator enabled {@code git.resolveProject} — the
     * repository holding the benchmark JAR. Anchored on the JAR, not the working directory: the JAR
     * is what is being measured, and the shell's directory is incidental. {@code branch} and
     * {@code commit} are deliberately not derived at all; they arrive as {@code --tag}s or not at all.
     */
    String resolveProject(BaasConfig config) {
        if (project != null && !project.isBlank()) return requireValidProject(project, "--project");
        if (!config.getGit().isResolveProject()) {
            throw new IllegalStateException(
                "No project named. Pass --project <name>, or let baas derive it from the benchmark "
                    + "JAR's git repository: baas config set --git-resolve-project true");
        }
        Path jarDir = benchmarkJar.toAbsolutePath().normalize().getParent();
        String derived = jarDir != null && Files.isDirectory(jarDir) ? GitProject.repositoryName(jarDir) : null;
        if (derived == null) {
            throw new IllegalStateException(
                "Cannot determine the project name: " + benchmarkJar + " is not inside a git repository. "
                    + "Pass --project <name>.");
        }
        return requireValidProject(derived, "The benchmark JAR's repository directory");
    }

    /**
     * The characters GitHub allows in a repository name, so any repository's name passes. The
     * project names an S3 prefix, a DynamoDB partition and a line of the instance's user-data;
     * anything wider is a typo here and a parse failure there, the latter before the watchdog runs.
     */
    static final Pattern PROJECT_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    static String requireValidProject(String name, String source) {
        if (PROJECT_NAME.matcher(name).matches()) return name;
        throw new IllegalStateException(source + " gives project name '" + name + "', which may "
            + "contain only letters, digits, '.', '_' and '-'."
            + (source.startsWith("--") ? "" : " Pass --project <name>."));
    }

    /** The benchmark timeout and the watchdog bound — which is also the CLI's poll cap. */
    record Timings(int timeoutSeconds, int watchdogSeconds) {}

    /** Flags over configuration, for both values; the bound always derived, never taken as given. */
    Timings resolveTimings(BaasConfig config) {
        int timeout = timeoutSeconds != null ? timeoutSeconds : config.getEc2().getBenchmarkTimeoutSeconds();
        int margin = watchdogMarginSeconds != null
            ? watchdogMarginSeconds : config.getEc2().getWatchdogMarginSeconds();
        return new Timings(timeout, watchdogBound(timeout, margin));
    }

    /**
     * The one place the watchdog bound is computed. It feeds both the instance's self-termination
     * delay and this CLI's poll cap, so the two cannot drift, and being relative it can never fall
     * below the benchmark's own timeout.
     */
    static int watchdogBound(int timeoutSeconds, int marginSeconds) {
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException(
                "The benchmark timeout must be at least 1 second; got " + timeoutSeconds + ". `timeout 0` "
                    + "disables the process timeout and a negative one fails the job unstarted. "
                    + "Nothing was launched.");
        }
        if (marginSeconds < BaasConfig.MIN_WATCHDOG_MARGIN_SECONDS) {
            throw new IllegalArgumentException(
                "The watchdog margin must be at least " + BaasConfig.MIN_WATCHDOG_MARGIN_SECONDS
                    + " seconds, to cover boot and the final upload; got " + marginSeconds
                    + ". Nothing was launched.");
        }
        return timeoutSeconds + marginSeconds;
    }

    /**
     * Tag keys populated from values observed on the instance, plus {@code type} — derived from
     * the executed subcommand, not from anything measured, but the same defect class: a caller
     * override would make a JMH job report {@code type=jcstress} while the manifest and the
     * actual subcommand disagree. A result's tags must never be able to disagree with that same
     * job's {@code environment.json} (see {@code UserDataScriptBuilder}'s {@code --tag} block),
     * so {@link #buildRunnerTags} rejects a caller {@code --tag} for any of these outright rather
     * than silently dropping or overriding it. {@code project}, {@code commit} and {@code branch}
     * are deliberately NOT in this set — design.md specifies the caller wins for those.
     *
     * <p>Defined once in baas-model so the CLI and the runner cannot drift apart.
     */
    static final List<String> RESERVED_TAG_KEYS = TagKeys.MACHINE_OBSERVED;

    /**
     * What to type instead of a runner option the benchmark's arguments may not name. The arguments
     * reach the runner after the CLI's own options, and picocli keeps the last value, so each of
     * these would silently override what {@code baas run} set, or fail the job on the instance.
     */
    static String insteadOf(String option) {
        return switch (option) {
            case RunnerArgs.TAG -> "Pass tags to baas run, before --: baas run --tag key=value ...";
            case RunnerArgs.PROJECT -> "Pass the project to baas run, before --: baas run --project <name> ...";
            case RunnerArgs.MONGO_CONNECTION_STRING_SHORT, RunnerArgs.MONGO_CONNECTION_STRING ->
                "BaaS stores results in its deployment's table. A JCStress mode is --mode, not -m.";
            default -> "It names the job's own deployment resources, which no benchmark argument may change.";
        };
    }

    /**
     * The results table this job records its status in and stores its measurements to.
     *
     * <p>Resolved before the runner-image lookup and before anything is uploaded or launched, like
     * every other precondition this command checks early: discovering it later costs a paid
     * instance. There is no option to go without one. Every job records its status in the table,
     * so a job with no table could not be seen at all — and the {@code --no-database} that once
     * discarded measurements had outlived any use.
     */
    static String resolveResultsTable(BaasConfig config) {
        // Throws, naming the command that adopts a deployment, when none is configured.
        return config.resultsTable();
    }

    /**
     * Extracted from call() so it can be tested without AWS. A caller tag colliding with a
     * {@link #RESERVED_TAG_KEYS reserved key} is rejected rather than silently dropped or allowed
     * to override — a silently discarded tag is its own surprise. So is {@code project}: it names
     * the S3 prefix and the job item through {@code --project}, and the measurements' partition
     * through this tag, so a second input for it split one job across two projects.
     */
    Map<String, String> buildRunnerTags(String benchmarkType, String project) {
        return buildRunnerTags(benchmarkType, project, System.getenv());
    }

    /**
     * Package-private overload taking the environment explicitly, for the same testability reason
     * as every other environment-dependent decision here — {@code source} is derived from it, and a test that read the
     * real environment would say {@code local} on a laptop and {@code ci} in CI.
     */
    Map<String, String> buildRunnerTags(String benchmarkType, String project, Map<String, String> environment) {
        List<String> collided = RESERVED_TAG_KEYS.stream().filter(extraTags::containsKey).toList();
        if (!collided.isEmpty()) {
            throw new IllegalArgumentException(
                "--tag " + String.join(", ", collided) + " cannot be set from the command line: "
                    + (collided.size() == 1 ? "it is" : "they are")
                    + " observed on the instance (or derived from the benchmark type), and a "
                    + "caller override would let a result's tags disagree with its own "
                    + "environment.json. Reserved keys: " + String.join(", ", RESERVED_TAG_KEYS)
                    + ". commit, branch and source remain caller-settable.");
        }
        if (extraTags.containsKey(TagKeys.PROJECT)) {
            throw new IllegalArgumentException(
                "--tag project cannot be set: the project is named by --project (or derived from git "
                    + "when git.resolveProject is on), and a second value would store the measurements "
                    + "under a different project than the job. Pass --project <name> instead.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(TagKeys.PROJECT, project);
        // commit and branch come only from the caller's --tag. Absent stays absent, never
        // "unknown": a placeholder value is indistinguishable from a real one at query time, which
        // is how RESULT#unknown grew.
        tags.put(TagKeys.TYPE, benchmarkType);
        tags.put(TagKeys.SOURCE, deriveSource(environment));
        tags.putAll(extraTags);
        return tags;
    }

    /**
     * How this job was triggered. Not a reserved key: the instance never observes it, so a forged
     * value misleads nobody about the measurement environment — which is the only thing
     * {@link #RESERVED_TAG_KEYS} exists to protect. Deriving it rather than leaving it to a
     * convention tag is what makes absence meaningful: a key only present when someone types it
     * would make {@code --best-per source} unreliable in exactly the direction that matters.
     *
     * <p>{@code CI} is the cross-vendor convention and GitHub Actions sets both it and
     * {@code GITHUB_ACTIONS}; {@code CI=false} is honoured because some environments set it that
     * way to opt out.
     */
    static String deriveSource(Map<String, String> environment) {
        return isTruthy(environment.get("CI")) || isTruthy(environment.get("GITHUB_ACTIONS"))
            ? TagKeys.SOURCE_CI
            : TagKeys.SOURCE_LOCAL;
    }

    // ─── JobLaunch.Listener: what the summary, the hints and the shutdown hook need ─────────

    @Override
    public void named(String jobId, String resultPath) {
        summaryJobId = jobId;
        summaryResultPath = resultPath;
    }

    /**
     * Registered here, after the reservation and before the launch: an interrupt while RunInstances
     * is in flight still finds the instance, by its job-id tag. Once the job has ended the instance
     * terminates itself, and the watchdog backs it up; terminating it from here as well would cut
     * off its final cloud-init-output.log upload. This layer is for a CLI that stops while the job
     * is still in flight.
     */
    @Override
    public void reserved(JobSession reserved) {
        session = reserved;
        shutdownHooks.accept(new Thread(this::onShutdown));
    }

    @Override
    public void launched(String instanceId) {
        summaryInstanceId = instanceId;
    }

    /** From here the hook leaves the job alone; see {@link #onShutdown}. */
    @Override
    public void detached(String jobStatus) {
        summaryJobStatus = jobStatus;
        detached = true;
    }

    @Override
    public void printResults(List<ResultRow> rows, String jobId) {
        reportJobResults(rows, jobId, r -> ResultsTable.print(console(), r));
    }

    /**
     * The post-job summary, which under {@code --format json} must not be printed at all.
     *
     * <p>The table goes to the {@link Console} — deliberately, since a table is a command
     * payload rather than a diagnostic. But so is the JSON summary, and two payloads on
     * one stream is not a stream anyone can parse: the table lands first and
     * {@code baas run --format json | jq} fails on the very first token. Caught by a real CI job,
     * which read an empty job id and then queried {@code --job-id ""}.
     *
     * <p>The rows are not folded into the summary object. They are a separate concern with a
     * separate command — the object carries the job id precisely so
     * {@code baas results query --job-id} can fetch them.
     *
     * @param printTable passed as a function so this is testable without an AWS client
     */
    void reportJobResults(List<ResultRow> rows, String jobId, Consumer<List<ResultRow>> printTable) {
        if (jsonSummary()) {
            logger.info("Job {} stored {} measurement(s) — {}",
                jobId, rows.size(), Hints.command(summaryDeployment, "results query --job-id " + jobId));
            return;
        }
        // Named, not just shown: this is the value `baas jobs download <jobId>` takes.
        logger.info("Results for job {} (baas jobs download {}):", jobId, jobId);
        printTable.accept(rows);
    }

    /**
     * One object on the {@link Console}, never coloured, following {@code ResultsQuerySubcommand.printJson}'s rule exactly:
     * the payload goes to standard output and every diagnostic to the logger, so
     * {@code baas run --format json | jq} is not corrupted by a timestamped log line — including
     * under {@code -v}.
     *
     * <p>{@code status} is the command's own verdict, kept for existing consumers — {@code launched}
     * for a confirmed {@code --detach}, which has not run yet and so cannot be {@code completed};
     * {@code jobStatus}
     * is what the job item says — {@code timed-out}, {@code cancelled}, {@code failed:<n>} and the
     * rest — or {@code vanished}/{@code status-lost}, or {@code null} before a job was recorded.
     *
     * <p>Printed on both outcomes. A failed job is precisely when a continuous-integration job
     * needs the id: to {@code baas jobs download} it and surface {@code cloud-init-output.log}, the
     * documented place to start when a job dies before producing output. The command's exit code
     * is unchanged by this — it still exits non-zero when the job failed.
     *
     * <p>Formatted with {@link Locale#ROOT} for the same reason the results formatters are: a
     * comma-decimal locale would emit text that is not JSON, silently and only on some machines.
     */
    void printJobSummary(int exitCode) {
        console().printf(
            "{\"jobId\":%s,\"project\":%s,\"resultPath\":%s,\"status\":\"%s\","
                + "\"jobStatus\":%s,\"exitCode\":%d,\"instanceId\":%s}%n",
            Json.string(summaryJobId), Json.string(summaryProject), Json.string(summaryResultPath),
            exitCode != 0 ? "failed" : detached ? "launched" : "completed", Json.string(summaryJobStatus), exitCode,
            Json.string(summaryInstanceId));
    }

    private static boolean isTruthy(String value) {
        return value != null && !value.isBlank() && !"false".equalsIgnoreCase(value.strip());
    }

    /** The {@code <type>} help lists the model's types, so it cannot fall behind them. */
    static final class BenchmarkTypes implements Iterable<String> {
        @Override
        public java.util.Iterator<String> iterator() {
            return BenchmarkType.commandNames().iterator();
        }
    }
}

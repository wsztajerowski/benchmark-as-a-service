package pl.wsztajerowski.baas.jobs;

import pl.wsztajerowski.baas.session.CoreStack;
import pl.wsztajerowski.baas.model.JobItem;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.session.DeploymentNames;
import pl.wsztajerowski.baas.cli.CommandRefusal;
import pl.wsztajerowski.baas.console.Hints;
import pl.wsztajerowski.baas.console.StatusLine;
import pl.wsztajerowski.baas.image.RunnerImage;
import pl.wsztajerowski.baas.model.BenchmarkArgs;
import pl.wsztajerowski.baas.model.BenchmarkType;
import pl.wsztajerowski.baas.model.JobId;
import pl.wsztajerowski.baas.model.JobLayout;
import pl.wsztajerowski.baas.model.JobStatus;
import pl.wsztajerowski.baas.results.ResultRow;

import java.util.ArrayList;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * {@code baas run}'s use case: from a validated request to a job that has ended, or has been handed
 * to its instance under {@code --detach}. Everything the command line decides — the project, the
 * tags, the timings — arrives resolved in the {@link Request}; everything AWS does goes through
 * {@link RunAws}; and the command hears about the job's progress through a {@link Listener}, because
 * the command owns the two things that outlive this method: the JSON summary and the shutdown hook.
 *
 * <p>The order below is the one CLAUDE.md's invariants describe and nothing may reorder it: every
 * precondition before any upload, the reservation before the launch, the listener's
 * {@link Listener#reserved} (where the hook is registered) before {@code RunInstances}.
 */
public final class JobLaunch {

    private static final Logger logger = LoggerFactory.getLogger(JobLaunch.class);

    /** What the command resolved from its options and the deployment's configuration. */
    public record Request(String benchmarkType, List<String> benchmarkParams, Path benchmarkJar, Path runnerJar,
                          String instanceType, String project, int timeoutSeconds,
                          int watchdogSeconds, Map<String, String> runnerTags, boolean detach,
                          String cliVersion, String sourceRepo, String jdk) {
        public Request {
            benchmarkParams = List.copyOf(benchmarkParams);
            runnerTags = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(runnerTags));
        }

        /** A request without {@code --jdk}. */
        public Request(String benchmarkType, List<String> benchmarkParams, Path benchmarkJar, Path runnerJar,
                       String instanceType, String project, int timeoutSeconds, int watchdogSeconds,
                       Map<String, String> runnerTags, boolean detach, String cliVersion, String sourceRepo) {
            this(benchmarkType, benchmarkParams, benchmarkJar, runnerJar, instanceType, project, timeoutSeconds,
                watchdogSeconds, runnerTags, detach, cliVersion, sourceRepo, null);
        }

        /**
         * The JDK the job runs on, once checked against the image: {@code --jdk}, else the image's
         * default, else none — an image from before JDK selection runs on {@code java} from {@code PATH}.
         */
        Request withJdk(String resolved) {
            return new Request(benchmarkType, benchmarkParams, benchmarkJar, runnerJar, instanceType, project,
                timeoutSeconds, watchdogSeconds, runnerTags, detach, cliVersion, sourceRepo, resolved);
        }
    }

    /** How the command follows the job: the facts its summary and hints report, and its shutdown hook. */
    public interface Listener {
        /** The job has a name and a prefix; nothing is uploaded yet. */
        void named(String jobId, String resultPath);

        /**
         * The reservation is written, and the instance is about to be requested: from here an
         * interrupt must find and stop the job, so this is where the shutdown hook is armed.
         */
        void reserved(JobSession session);

        void launched(String instanceId);

        /** A {@code --detach} launch is confirmed: the job ends on its instance alone. */
        void detached(String jobStatus);

        /** A status line for the poll loop, or {@code null} to log progress instead. */
        StatusLine openStatusLine();

        /** A completed job's measurements, as the command shows them. */
        void printResults(List<ResultRow> rows, String jobId);

        /** A completed profiler listing's process output, verbatim: the job measured nothing. */
        void printListing(String listing, String jobId);
    }

    private final RunAws aws;
    private final DeploymentNames deployment;
    private final Listener listener;
    private final LongSupplier clock;
    private final JobSession.Sleeper sleeper;
    private final long pollIntervalMillis;

    public JobLaunch(RunAws aws, DeploymentNames deployment, Listener listener) {
        this(aws, deployment, listener, System::currentTimeMillis, Thread::sleep, 15_000);
    }

    JobLaunch(RunAws aws, DeploymentNames deployment, Listener listener, LongSupplier clock,
              JobSession.Sleeper sleeper, long pollIntervalMillis) {
        this.aws = aws;
        this.deployment = deployment;
        this.listener = listener;
        this.clock = clock;
        this.sleeper = sleeper;
        this.pollIntervalMillis = pollIntervalMillis;
    }

    /** Why a job cannot launch on a deployment with no runner image, and the command that fixes it. */
    public static String noImageMessage(String deployment, String region) {
        return """
            Deployment %s has no runner image (%s).
              Build one:  %s
            Nothing was launched — the runner boots from a purpose-built AMI and \
            there is no boot-time install path.""".formatted(deployment, region, Hints.command(deployment, "admin image build"));
    }

    /**
     * The JDK the job runs on: the one asked for, which the image must hold, or the image's default.
     * An image from before JDK selection accepts no {@code --jdk} and runs on {@code java} from
     * {@code PATH}, under no JDK id.
     */
    String selectJdk(String asked, RunnerImage image) {
        if (!image.selectsJdks()) {
            if (asked != null) {
                throw CommandRefusal.usage("""
                    Runner image %s (%s) predates JDK selection, so it accepts no --jdk.
                      Rebuild it:  %s
                    Nothing was launched.""".formatted(image.imageVersion(), image.amiId(),
                    Hints.command(deployment.prefix(), "admin image build")));
            }
            return null;
        }
        if (asked == null) {
            return image.defaultJdk();
        }
        if (!image.jdkIds().contains(asked)) {
            throw CommandRefusal.usage("""
                Runner image %s (%s) holds no JDK '%s'. Its JDKs: %s.
                Nothing was launched.""".formatted(image.imageVersion(), image.amiId(), asked, image.describeJdks()));
        }
        return asked;
    }

    /**
     * The job item's tags: the CLI-side ones the runner also gets, plus the JDK the job runs on. The
     * other observed tags reach only the measurements, since the instance observes them after the
     * item is written; the JDK id is the CLI's own choice, checked against the image, so the item can
     * carry it — and a job that stores no measurement, a profiler listing, is still found by its JDK.
     * Absent on an image from before JDK selection, as on its measurements.
     */
    static Map<String, String> jobTags(Request request) {
        if (request.jdk() == null) {
            return request.runnerTags();
        }
        Map<String, String> tags = new java.util.LinkedHashMap<>(request.runnerTags());
        tags.put(pl.wsztajerowski.baas.model.TagKeys.JDK_ID, request.jdk());
        return tags;
    }

    /** Runs the job to its end, or to its hand-over under {@code --detach}; returns the command's exit code. */
    public int run(Request request) throws InterruptedException {
        String prefix = deployment.prefix();

        // 1. Resolve the runner image, before anything is uploaded or launched. A missing image
        //    is a hard stop — there is no fallback to AL2023 + yum, since two provisioning paths
        //    would produce silently incomparable results — so discovering it here costs two API
        //    calls rather than an upload that would only fail afterward.
        var resolved = aws.currentImage(deployment.amiPointer());
        if (resolved.isEmpty()) {
            logger.error(noImageMessage(prefix, aws.region()));
            return 1;
        }
        RunnerImage runnerImage = resolved.get();
        logger.debug("Resolved runner AMI: {} (image version {})",
            runnerImage.amiId(), runnerImage.imageVersion());
        // Checked against what the image holds, in the same lookup: a misspelt id costs nothing,
        // where the runner's own check would cost a boot.
        request = request.withJdk(selectJdk(request.jdk(), runnerImage));

        // Before naming the job and before any upload: a torn-down or wrong deployment used to
        // upload the benchmark JAR and only then fail here. Resolved per job rather than cached in
        // config: replacing RunnerSecurityGroup moves its id, and a stored copy then names a group
        // that no longer exists. The operator role already holds cloudformation:DescribeStacks on
        // its own stack, so this costs one call.
        Map<String, String> networking = resolveNetworking();

        // 2. Name the job. One clock read: the instant travels into the identifier, into the S3
        //    prefix and on to the runner as --created-at, so the prefix name and the stored
        //    timestamp are the same value rather than two values that happen to be close.
        Instant jobInstant = Instant.now();
        String jobId = JobId.generate(jobInstant);
        String resultPath = JobLayout.jobPrefix(request.project(), jobId);
        listener.named(jobId, resultPath);
        logger.info("Job {} — results will land under s3://{}/{}",
            jobId, deployment.bucket(), resultPath);

        // 3. Upload JARs into the job's own prefix, so one prefix holds the whole job.
        logger.info("Uploading benchmark JAR to S3...");
        String benchmarkJarKey = JobLayout.benchmarkJarKey(request.project(), jobId);
        aws.upload(request.benchmarkJar(), deployment.bucket(), benchmarkJarKey);

        // The instance's only runner-JAR source. A --runner-jar override stays per-job under the
        // job's own input/, so releases/ holds released artifacts only.
        String runnerJarS3Key;
        if (request.runnerJar() != null) {
            logger.info("Uploading runner JAR to S3...");
            runnerJarS3Key = JobLayout.runnerJarOverrideKey(request.project(), jobId);
            aws.upload(request.runnerJar(), deployment.bucket(), runnerJarS3Key);
            logger.info("Runner JAR: {} (local override, not a pinned release)", request.runnerJar());
        } else {
            runnerJarS3Key = aws.pinnedRunnerJar(deployment.bucket(), request.cliVersion(), request.sourceRepo());
            // Which runner build a job executed is the first thing anyone comparing two results
            // asks, so it is reported on every job — not only on the one that seeded the slot.
            logger.info("Runner JAR: {} (pinned to CLI version {})", runnerJarS3Key, request.cliVersion());
        }

        // 4. Build user-data. The job item's sort key is built here, once, by ResultKeys, and
        //    handed to the instance verbatim; see UserDataScriptBuilder's JOB_SORT_KEY.
        JobItem job = new JobItem(jobId, request.project(), jobInstant, resultPath, request.instanceType(),
            JobStatus.LAUNCHING, null, null, jobTags(request), null, request.watchdogSeconds());
        String userData = new UserDataScriptBuilder().build(
            aws.region(), deployment, job, runnerImage, request, runnerJarS3Key);
        // The script is what actually decides whether a job works; when a runner dies before it
        // can upload cloud-init-output.log, this is the only place left to look.
        logger.debug("Generated user-data script:\n{}", userData);

        var instances = aws.instances(prefix);
        var current = new JobSession(job, aws.recorder(deployment.resultsTable()),
            aws.stopRecorder(deployment.resultsTable()), instances);

        // 5. Reserve the job before launching it. An instance without a record is exactly the
        //    invisible job `baas jobs` exists to show, so a reservation that cannot be written
        //    launches nothing.
        try {
            current.reserve();
        } catch (RuntimeException e) {
            logger.error("""
                Could not record run {} in the results table, so nothing was launched: {}
                If the operator role lacks dynamodb:UpdateItem, the deployment predates run \
                tracking — update it with `{}`.""", jobId, e.getMessage(),
                Hints.command(prefix, "admin deployment setup"));
            return 1;
        }

        // 6. The shutdown hook, armed before the launch: an interrupt while RunInstances is in
        //    flight still finds the instance, by its job-id tag.
        listener.reserved(current);

        // 7. Launch. It carries only the fixed tags (see Ec2ProvisioningService#instanceTags):
        //    every caller --tag, and the observed imageVersion/instanceType, reach the stored
        //    result through the runner's own --tag options in user-data, which is the only place
        //    `baas results query` reads.
        logger.info("Launching EC2 instance ({}) from {}...", request.instanceType(), runnerImage.amiId());
        String subnetId = networking.get(CoreStack.OUTPUT_SUBNET_ID);
        String instanceId;
        try {
            instanceId = instances.runInstance(runnerImage.amiId(), request.instanceType(),
                subnetId, networking.get(CoreStack.OUTPUT_SECURITY_GROUP_ID),
                deployment.runnerInstanceProfile(), userData, jobId, deployment.stack());
        } catch (RuntimeException e) {
            recordLaunchFailure(current, e, runnerImage.amiId(), request.instanceType(), subnetId);
            return 1;
        }
        listener.launched(instanceId);
        logger.info("Instance launched: {}", instanceId);
        logger.info("Job ID: {}", jobId);
        Integer exitBeforePolling = afterLaunch(current, current.confirmLaunched(instanceId), request.detach());
        if (exitBeforePolling != null) {
            return exitBeforePolling;
        }

        // 8. Poll, then report.
        boolean listing = BenchmarkArgs.of(BenchmarkType.fromCommandName(request.benchmarkType()))
            .isProfilerListing(request.benchmarkParams());
        return poll(current, deployment.resultsTable(), request.watchdogSeconds(), listing);
    }

    /**
     * The runner subnet and security group, read from the deployment's stack outputs. Not cached in
     * the deployment's file: editing {@code RunnerSecurityGroup}'s {@code GroupDescription} replaces
     * the security group and changes its id, and a stored copy then points at a group that no
     * longer exists. Resolving here removes that failure rather than documenting it.
     */
    private Map<String, String> resolveNetworking() {
        Map<String, String> outputs = aws.stackOutputs(deployment.stack());
        List<String> missing = new ArrayList<>();
        for (String key : List.of(CoreStack.OUTPUT_SUBNET_ID, CoreStack.OUTPUT_SECURITY_GROUP_ID)) {
            String value = outputs.get(key);
            if (value == null || value.isBlank()) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                "Stack %s did not report %s. Run `%s`, or point this machine at an existing deployment "
                    .formatted(deployment.stack(), String.join(", ", missing),
                        Hints.command(deployment.prefix(), "admin deployment setup"))
                    + "with `baas --deployment <name> config sync`.");
        }
        return outputs;
    }

    /**
     * What follows a launch: exit 1 when the job was cancelled while launching; under
     * {@code --detach}, exit 0 with the status the item holds; {@code null} to go on polling.
     *
     * <p>A detached job reports {@code launched}, or {@code launching} when that write was lost and
     * the instance records itself once it boots. A launch confirmed by a refusal — the instance got
     * to {@code running} first — still reports {@code launched}: {@code baas jobs show} is where the
     * current status is read, and a re-read here would be stale a second later anyway.
     */
    Integer afterLaunch(JobSession current, JobSession.Confirmation confirmation, boolean detach) {
        if (confirmation == JobSession.Confirmation.CANCELLED_WHILE_LAUNCHING) {
            return 1;
        }
        if (!detach) {
            return null;
        }
        // From here the hook leaves the job alone. A Ctrl+C that lands before this line still
        // cancels it, which is what an interrupt during a launch means.
        listener.detached(confirmation == JobSession.Confirmation.CONFIRMED
            ? JobStatus.LAUNCHED : JobStatus.LAUNCHING);
        logger.info("Job {} detached; it ends on instance {}. Follow it: {}",
            current.job().jobId(), current.instanceId(),
            Hints.command(deployment.prefix(), "jobs show " + current.job().jobId()));
        return 0;
    }

    /**
     * A launch that failed leaves no instance and so no boot log. What there is to keep is in the
     * exception and in the request, so both go to the job's prefix, where {@code baas jobs download}
     * finds them, and the error code goes on the job item, where {@code baas jobs list} shows it.
     * Both are best effort: the launch error is reported whether or not they land, since a launch
     * often fails for the same reason they would — the network or the credentials.
     */
    private void recordLaunchFailure(JobSession current, RuntimeException error, String amiId,
                                     String instanceType, String subnetId) {
        String errorCode = null;
        String awsRequestId = null;
        if (error instanceof AwsServiceException awsError) {
            awsRequestId = awsError.requestId();
            if (awsError.awsErrorDetails() != null) {
                errorCode = awsError.awsErrorDetails().errorCode();
            }
        }
        JobItem job = current.job();
        logger.error("Launching the instance for job {} failed{}: {}", job.jobId(),
            errorCode == null ? "" : " (" + errorCode + ")", error.getMessage());
        current.recordLaunchFailed(errorCode);
        String report = String.join("\n",
            "jobId: " + job.jobId(),
            "time: " + Instant.now(),
            "errorCode: " + (errorCode == null ? "" : errorCode),
            "awsRequestId: " + (awsRequestId == null ? "" : awsRequestId),
            "instanceType: " + instanceType,
            "amiId: " + amiId,
            "subnetId: " + subnetId,
            "message: " + error.getMessage(),
            "");
        try {
            aws.putText(deployment.bucket(), JobLayout.launchErrorKey(job.project(), job.jobId()), report);
            logger.info("Launch error recorded: {}", Hints.command(deployment.prefix(), "jobs download " + job.jobId()));
        } catch (RuntimeException e) {
            logger.warn("Could not upload {} ({})", JobLayout.LAUNCH_ERROR_NAME, e.getMessage());
        }
    }

    private int poll(JobSession current, String resultsTable, int capSeconds, boolean listing)
            throws InterruptedException {
        JobItem job = current.job();
        String logPath = "s3://" + deployment.bucket() + "/" + JobLayout.artifactKey(job.resultPath(), JobLayout.BOOT_LOG_NAME);
        JobSession.Outcome outcome;
        StatusLine line = listener.openStatusLine();
        try {
            outcome = current.await(capSeconds, pollIntervalMillis, clock, sleeper,
                () -> measurementsStored(resultsTable, job.jobId()),
                (state, elapsed) -> {
                    if (line != null) {
                        line.update(statusText(state, elapsed, current.instanceId()));
                    } else {
                        logger.info("Still running ({})... elapsed: {}s", state, elapsed);
                    }
                });
        } finally {
            // The job is over: a line still saying "running" beside its results is wrong, and
            // would be redrawn under every row of the table printed next.
            if (line != null) {
                line.close();
            }
        }
        return report(outcome, resultsTable, job, current.instanceId(), logPath, listing);
    }

    /** Short enough never to wrap an 80-column terminal, so a redraw stays on one row. */
    public static String statusText(String state, long elapsedSeconds, String instanceId) {
        return String.format(java.util.Locale.ROOT, "%s · %s elapsed · %s",
            state, pl.wsztajerowski.baas.console.Elapsed.format(elapsedSeconds), instanceId);
    }

    /** Says what the outcome was, shows the results of a completed job, and returns its exit code. */
    private int report(JobSession.Outcome outcome, String resultsTable, JobItem job, String instanceId,
                       String logPath, boolean listing) {
        String jobId = job.jobId();
        String status = outcome.status();
        logger.info("Job status: {}", status);
        if (outcome.completed() && listing) {
            showListing(job);
        } else if (outcome.completed()) {
            showResults(resultsTable, jobId);
        } else if (status.startsWith(JobStatus.FAILED_PREFIX)) {
            logger.error("Benchmark failed. Runner log: {}", logPath);
        } else if (JobStatus.TIMED_OUT.equals(status)) {
            logger.error("Job {} timed out. Runner log (if the instance got far enough to upload "
                + "it): {}", jobId, logPath);
        } else if (JobStatus.CANCELLED.equals(status)) {
            logger.error("Job {} was cancelled.", jobId);
        } else if (JobSession.Outcome.STATUS_LOST.equals(status)) {
            logger.error("Instance {} terminated without recording its final status, but the job "
                + "stored measurements: {}\nRunner log: {}",
                instanceId, Hints.command(deployment.prefix(), "results query --job-id " + jobId), logPath);
        } else {
            logger.error("Instance {} terminated without recording a final status — the runner "
                + "died before finishing.\nRunner log (present only if the instance got far "
                + "enough to upload it): {}", instanceId, logPath);
        }
        return outcome.exitCode();
    }

    /** Asked only when the instance is gone without a final status; a failed read counts as none. */
    private boolean measurementsStored(String resultsTable, String jobId) {
        try {
            return !aws.jobRows(resultsTable, jobId).isEmpty();
        } catch (RuntimeException e) {
            logger.debug("Could not check for stored measurements: {}", e.getMessage());
            return false;
        }
    }

    /**
     * The listing is the job's process output, the same object {@code baas jobs download} fetches. The
     * job has completed whatever this read does, so a failure here is a warning, as for results.
     */
    private void showListing(JobItem job) {
        String key = JobLayout.artifactKey(job.resultPath(), BenchmarkType.JMH.processOutputName());
        try {
            aws.getText(deployment.bucket(), key).ifPresentOrElse(
                listing -> listener.printListing(listing, job.jobId()),
                () -> logger.warn("Job {} completed but its listing is not at s3://{}/{}", job.jobId(), deployment.bucket(), key));
        } catch (Exception e) {
            logger.warn("Could not fetch the profiler listing: {}", e.getMessage());
        }
    }

    /**
     * Reads the same path {@code baas results query} does, so the post-job summary can never disagree
     * with what a later query reports. A benchmark that has already run and terminated must not be
     * reported as failed over a summary, so a failed read is a warning.
     */
    private void showResults(String resultsTable, String jobId) {
        try {
            listener.printResults(aws.jobRows(resultsTable, jobId), jobId);
        } catch (Exception e) {
            logger.warn("Could not fetch results from the results table: {}", e.getMessage());
        }
    }
}

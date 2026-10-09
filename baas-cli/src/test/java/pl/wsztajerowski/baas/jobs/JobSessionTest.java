package pl.wsztajerowski.baas.jobs;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.model.JobStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobSessionTest {

    private static final JobItem JOB = new JobItem("20261003T000000000Z-a3f9c21b", "p",
        Instant.parse("2026-10-03T00:00:00Z"), "jobs/p/20261003T000000000Z-a3f9c21b", "c5.2xlarge",
        JobStatus.LAUNCHING, null, null, Map.of(), null);

    /** The job item in memory, applying the same rules the DynamoDB conditions enforce. */
    static final class FakeRecorder implements JobRecorder {
        final List<String> writes = new ArrayList<>();
        String status;
        String instanceId;
        RuntimeException failReserve;
        RuntimeException failLaunched;
        RuntimeException failStop;
        /** Runs just before a stop write, as an instance's own write racing it would. */
        Runnable beforeStop = () -> { };

        @Override
        public void reserve(JobItem job) {
            if (failReserve != null) throw failReserve;
            writes.add("reserve");
            status = JobStatus.LAUNCHING;
        }

        @Override
        public Write launched(JobItem job, String id) {
            if (failLaunched != null) throw failLaunched;
            writes.add("launched");
            if (!JobStatus.LAUNCHING.equals(status)) return Write.REFUSED;
            status = JobStatus.LAUNCHED;
            instanceId = id;
            return Write.WRITTEN;
        }

        @Override
        public Write launchFailed(JobItem job, String errorCode) {
            writes.add("launch-failed:" + errorCode);
            return guarded(JobStatus.LAUNCH_FAILED);
        }

        @Override
        public Write stop(JobItem job, String s) {
            if (failStop != null) throw failStop;
            beforeStop.run();
            writes.add("stop:" + s);
            return guarded(s);
        }

        /** What the instance's own write does. */
        Write instanceWrites(String s) {
            return guarded(s);
        }

        private Write guarded(String s) {
            if (status == null || JobStatus.isTerminal(status)) return Write.REFUSED;
            status = s;
            return Write.WRITTEN;
        }

        @Override
        public Optional<JobItem> read(JobItem job) {
            return status == null ? Optional.empty() : Optional.of(new JobItem(job.jobId(), job.project(),
                job.createdAt(), job.resultPath(), job.instanceType(), status, instanceId, null, Map.of(), null));
        }

        @Override
        public Optional<JobItem> find(String jobId) {
            return read(JOB);
        }

        @Override
        public List<JobItem> newestFirst(Predicate<JobItem> filter, int limit) {
            return read(JOB).filter(filter).stream().toList();
        }
    }

    static final class FakeInstances implements JobSession.Instances {
        final Map<String, String> states = new HashMap<>();
        final Map<String, String> byJobId = new HashMap<>();
        final List<String> terminated = new ArrayList<>();
        RuntimeException failTerminate;

        @Override
        public String state(String instanceId) {
            return states.getOrDefault(instanceId, "unknown");
        }

        @Override
        public Optional<String> findLive(String jobId) {
            return Optional.ofNullable(byJobId.get(jobId));
        }

        @Override
        public void terminate(String instanceId) {
            if (failTerminate != null) throw failTerminate;
            terminated.add(instanceId);
            states.put(instanceId, "shutting-down");
        }
    }

    private final FakeRecorder recorder = new FakeRecorder();
    private final FakeInstances instances = new FakeInstances();
    private final JobSession session = new JobSession(JOB, recorder, recorder, instances);

    // ─── reserve and launch ──────────────────────────────────────────────────────

    @Test
    void aFailedReservationThrowsSoNothingIsLaunched() {
        recorder.failReserve = new IllegalStateException("AccessDenied");

        assertThatThrownBy(session::reserve).hasMessageContaining("AccessDenied");
        assertThat(recorder.status).isNull();
    }

    @Test
    void aConfirmedLaunchRecordsTheInstance() {
        session.reserve();

        assertThat(session.confirmLaunched("i-1")).isEqualTo(JobSession.Confirmation.CONFIRMED);
        assertThat(recorder.status).isEqualTo(JobStatus.LAUNCHED);
        assertThat(session.instanceId()).isEqualTo("i-1");
    }

    /** The id is held before the write, so an interrupt during its retries still finds the instance. */
    @Test
    void aFailedLaunchedWriteProceedsAndAnInterruptStillTerminates() {
        session.reserve();
        recorder.failLaunched = new IllegalStateException("network down");

        assertThat(session.confirmLaunched("i-1")).isEqualTo(JobSession.Confirmation.UNCONFIRMED);
        session.stop(JobStatus.CANCELLED);

        assertThat(instances.terminated).containsExactly("i-1");
        assertThat(recorder.status).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void aJobCancelledWhileLaunchingTerminatesTheInstanceItJustLaunched() {
        session.reserve();
        recorder.stop(JOB, JobStatus.CANCELLED);   // another operator's `baas jobs terminate`

        assertThat(session.confirmLaunched("i-1")).isEqualTo(JobSession.Confirmation.CANCELLED_WHILE_LAUNCHING);
        assertThat(instances.terminated).containsExactly("i-1");
        assertThat(session.ended()).isTrue();
        assertThat(session.endStatus()).isEqualTo(JobStatus.CANCELLED);
    }

    /** The instance's `running` landed first; the late `launched` must not move it backwards. */
    @Test
    void aLateLaunchedWriteDoesNotMoveTheStatusBack() {
        session.reserve();
        recorder.instanceWrites(JobStatus.RUNNING);

        assertThat(session.confirmLaunched("i-1")).isEqualTo(JobSession.Confirmation.CONFIRMED);
        assertThat(recorder.status).isEqualTo(JobStatus.RUNNING);
        assertThat(instances.terminated).isEmpty();
    }

    @Test
    void aFailedLaunchIsRecordedAndEndsTheSession() {
        session.reserve();

        session.recordLaunchFailed("InsufficientInstanceCapacity");

        assertThat(recorder.status).isEqualTo(JobStatus.LAUNCH_FAILED);
        assertThat(recorder.writes).contains("launch-failed:InsufficientInstanceCapacity");
        assertThat(session.ended()).as("the shutdown hook then has nothing to stop").isTrue();
        assertThat(session.endStatus()).isEqualTo(JobStatus.LAUNCH_FAILED);
    }

    // ─── stop ────────────────────────────────────────────────────────────────────

    @Test
    void stopRecordsTheReasonBeforeTerminating() {
        session.reserve();
        session.confirmLaunched("i-1");

        session.stop(JobStatus.CANCELLED);

        assertThat(recorder.writes).containsSubsequence("stop:cancelled");
        assertThat(recorder.status).isEqualTo(JobStatus.CANCELLED);
        assertThat(instances.terminated).containsExactly("i-1");
        assertThat(session.endStatus()).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void aJobStillGoingHasNoEndStatus() {
        session.reserve();
        session.confirmLaunched("i-1");

        assertThat(session.endStatus()).isNull();
    }

    @Test
    void aThrowingStatusWriteStillTerminates() {
        session.reserve();
        session.confirmLaunched("i-1");
        recorder.failStop = new IllegalStateException("timed out after 5s");

        session.stop(JobStatus.CANCELLED);

        assertThat(instances.terminated).containsExactly("i-1");
    }

    /** Interrupted while RunInstances was in flight: no id yet, so the run-id tag finds it. */
    @Test
    void anInterruptBeforeTheLaunchReturnedFindsTheInstanceByItsTag() {
        session.reserve();
        instances.byJobId.put(JOB.jobId(), "i-tagged");

        session.stop(JobStatus.CANCELLED);

        assertThat(instances.terminated).containsExactly("i-tagged");
    }

    /**
     * The tag is not visible yet — AWS has not created the instance, or DescribeInstances lags
     * RunInstances. Nothing is terminated here; the instance's own {@code running} write is refused
     * over {@code cancelled}, and it terminates itself before the benchmark (UserDataScriptBuilder).
     */
    @Test
    void anInterruptWhoseInstanceIsNotYetVisibleStillRecordsTheCancellation() {
        session.reserve();

        session.stop(JobStatus.CANCELLED);

        assertThat(instances.terminated).isEmpty();
        assertThat(recorder.status).isEqualTo(JobStatus.CANCELLED);
        assertThat(recorder.instanceWrites(JobStatus.RUNNING)).isEqualTo(JobRecorder.Write.REFUSED);
    }

    /** Ctrl+C between the instance's final write and the next poll: its log upload must finish. */
    @Test
    void anInterruptAfterTheInstanceRecordedItsOutcomeLeavesItToTerminateItself() {
        for (String outcome : List.of(JobStatus.COMPLETED, JobStatus.failed(3))) {
            FakeRecorder recorder = new FakeRecorder();
            FakeInstances instances = new FakeInstances();
            JobSession session = new JobSession(JOB, recorder, recorder, instances);
            session.reserve();
            session.confirmLaunched("i-1");
            recorder.instanceWrites(outcome);

            session.stop(JobStatus.CANCELLED);

            assertThat(instances.terminated).as(outcome).isEmpty();
            assertThat(recorder.status).as(outcome).isEqualTo(outcome);
        }
    }

    /** Refused because someone else stopped it first: that guarantees nothing about the instance. */
    @Test
    void anInterruptAfterACancellationFromElsewhereStillTerminates() {
        session.reserve();
        session.confirmLaunched("i-1");
        recorder.stop(JOB, JobStatus.CANCELLED);

        session.stop(JobStatus.CANCELLED);

        assertThat(instances.terminated).containsExactly("i-1");
    }

    @Test
    void aFailedTerminationIsLoggedNotThrownFromTheHook() {
        session.reserve();
        session.confirmLaunched("i-1");
        instances.failTerminate = new IllegalStateException("UnauthorizedOperation");

        session.stop(JobStatus.CANCELLED);

        assertThat(recorder.status).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void stopIsANoOpOnceTheJobEnded() {
        session.reserve();
        session.recordLaunchFailed("X");

        String reported = session.stop(JobStatus.CANCELLED);

        assertThat(recorder.writes).doesNotContain("stop:cancelled");
        assertThat(reported).as("R14: the status the job ended with, not the late caller's reason")
            .isEqualTo(JobStatus.LAUNCH_FAILED);
    }

    /**
     * R14: the shutdown hook and the poll cap stop the job at once. One writes and terminates; the
     * other waits and reports what was recorded.
     */
    @Test
    void twoStopsAtOnceRecordAndTerminateOnce() throws Exception {
        launched();
        var inside = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        recorder.beforeStop = () -> {
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        var hook = new java.util.concurrent.atomic.AtomicReference<String>();
        Thread first = new Thread(() -> hook.set(session.stop(JobStatus.CANCELLED)));
        first.start();
        inside.await();

        var cap = new java.util.concurrent.atomic.AtomicReference<String>();
        Thread second = new Thread(() -> cap.set(session.stop(JobStatus.TIMED_OUT)));
        second.start();
        Thread.sleep(100);
        assertThat(second.getState()).as("the second stop waits for the first").isIn(Thread.State.BLOCKED, Thread.State.WAITING);
        release.countDown();
        first.join(5_000);
        second.join(5_000);

        assertThat(recorder.writes).filteredOn(w -> w.startsWith("stop:")).containsExactly("stop:cancelled");
        assertThat(instances.terminated).containsExactly("i-1");
        assertThat(hook.get()).isEqualTo(JobStatus.CANCELLED);
        assertThat(cap.get()).isEqualTo(JobStatus.CANCELLED);
        assertThat(session.endStatus()).isEqualTo(recorder.status);
    }

    // ─── await ───────────────────────────────────────────────────────────────────

    private final AtomicLong clock = new AtomicLong();

    private JobSession.Outcome await(int capSeconds, boolean measurementsStored, Runnable onEachSleep)
        throws InterruptedException {
        return session.await(capSeconds, 15_000, clock::get, millis -> {
            clock.addAndGet(millis);
            onEachSleep.run();
        }, () -> measurementsStored, (state, elapsed) -> { });
    }

    private void launched() {
        session.reserve();
        session.confirmLaunched("i-1");
        instances.states.put("i-1", "running");
    }

    @Test
    void completionExitsZero() throws Exception {
        launched();

        var outcome = await(600, false, () -> recorder.instanceWrites(JobStatus.COMPLETED));

        assertThat(outcome).isEqualTo(new JobSession.Outcome(JobStatus.COMPLETED, 0));
        assertThat(instances.terminated).as("the instance terminates itself after its log upload").isEmpty();
        assertThat(session.endStatus()).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    void aFailedBenchmarkExitsOneAsItAlwaysHas() throws Exception {
        launched();

        var outcome = await(600, false, () -> recorder.instanceWrites(JobStatus.failed(3)));

        assertThat(outcome).isEqualTo(new JobSession.Outcome("failed:3", 1));
        assertThat(session.endStatus()).isEqualTo("failed:3");
    }

    @Test
    void theWatchdogsTimeoutExitsOne() throws Exception {
        launched();

        var outcome = await(600, false, () -> recorder.instanceWrites(JobStatus.TIMED_OUT));

        assertThat(outcome.exitCode()).isEqualTo(1);
        assertThat(outcome.status()).isEqualTo(JobStatus.TIMED_OUT);
        assertThat(instances.terminated)
            .as("R10: the watchdog wrote this and is uploading the boot log before it terminates the instance")
            .isEmpty();
    }

    @Test
    void theCapRecordsATimeoutNotACancellation() throws Exception {
        launched();

        var outcome = await(60, false, () -> { });

        assertThat(outcome).isEqualTo(new JobSession.Outcome(JobStatus.TIMED_OUT, 1));
        assertThat(session.endStatus()).isEqualTo(JobStatus.TIMED_OUT);
        assertThat(recorder.status).isEqualTo(JobStatus.TIMED_OUT);
        assertThat(instances.terminated).containsExactly("i-1");
    }

    /** The instance completed during the last sleep: the cap must report that, not a timeout. */
    @Test
    void theCapHonoursAnOutcomeRecordedDuringTheLastSleep() throws Exception {
        launched();

        var outcome = await(60, false, () -> {
            if (clock.get() > 60_000) recorder.instanceWrites(JobStatus.COMPLETED);
        });

        assertThat(outcome).isEqualTo(new JobSession.Outcome(JobStatus.COMPLETED, 0));
        assertThat(instances.terminated).isEmpty();
    }

    /** The instance's write lands between the cap's read and its own: the refused timeout is not reported. */
    @Test
    void theCapReportsAnOutcomeThatBeatItsOwnWrite() throws Exception {
        launched();
        recorder.beforeStop = () -> recorder.instanceWrites(JobStatus.COMPLETED);

        var outcome = await(60, false, () -> { });

        assertThat(outcome).isEqualTo(new JobSession.Outcome(JobStatus.COMPLETED, 0));
        assertThat(recorder.status).isEqualTo(JobStatus.COMPLETED);
        assertThat(instances.terminated).isEmpty();
    }

    @Test
    void aCancellationFromElsewhereStillTerminatesALiveInstance() throws Exception {
        launched();

        var outcome = await(600, false, () -> recorder.stop(JOB, JobStatus.CANCELLED));

        assertThat(outcome).isEqualTo(new JobSession.Outcome(JobStatus.CANCELLED, 1));
        assertThat(instances.terminated).containsExactly("i-1");
    }

    @Test
    void aStatusWrittenJustBeforeTerminationIsHonoured() throws Exception {
        launched();

        var outcome = await(600, false, () -> {
            instances.states.put("i-1", "terminated");
            recorder.instanceWrites(JobStatus.COMPLETED);
        });

        assertThat(outcome.exitCode()).isZero();
    }

    @Test
    void aGoneInstanceWithStoredMeasurementsIsALostStatusNotAVanishedJob() throws Exception {
        launched();

        var outcome = await(600, true, () -> instances.states.put("i-1", "terminated"));

        assertThat(outcome).isEqualTo(new JobSession.Outcome(JobSession.Outcome.STATUS_LOST, 1));
        assertThat(session.endStatus()).isEqualTo(JobSession.Outcome.STATUS_LOST);
    }

    @Test
    void aGoneInstanceWithNothingStoredVanished() throws Exception {
        launched();

        var outcome = await(600, false, () -> instances.states.put("i-1", "terminated"));

        assertThat(outcome).isEqualTo(new JobSession.Outcome(JobStatus.VANISHED, 1));
        assertThat(session.ended()).isTrue();
        assertThat(session.endStatus()).isEqualTo(JobStatus.VANISHED);
    }

    // ─── U38: an outcome that beat the launch confirmation ───────────────────────

    /**
     * RunInstances answered after the whole job (seen live on a slow link): the instance recorded
     * completed before the CLI could record launched. That is a finished job, not a cancelled one.
     */
    @Test
    void aJobThatFinishedBeforeItsLaunchWasConfirmedIsReportedAsFinished() throws Exception {
        session.reserve();
        recorder.instanceWrites(JobStatus.RUNNING);
        recorder.instanceWrites(JobStatus.COMPLETED);

        assertThat(session.confirmLaunched("i-1")).isEqualTo(JobSession.Confirmation.CONFIRMED);
        assertThat(instances.terminated).as("it is uploading its boot log and terminates itself").isEmpty();

        instances.states.put("i-1", "shutting-down");
        var outcome = await(600, true, () -> { });
        assertThat(outcome).isEqualTo(new JobSession.Outcome(JobStatus.COMPLETED, 0));
    }

    @Test
    void aFailedJobThatBeatTheConfirmationIsReportedAsFailedNotCancelled() throws Exception {
        session.reserve();
        recorder.instanceWrites(JobStatus.failed(7));

        assertThat(session.confirmLaunched("i-1")).isEqualTo(JobSession.Confirmation.CONFIRMED);
        assertThat(instances.terminated).isEmpty();
        assertThat(await(600, false, () -> { })).isEqualTo(new JobSession.Outcome("failed:7", 1));
    }
}

package pl.wsztajerowski.baas.runs;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.model.RunItem;
import pl.wsztajerowski.baas.model.RunStatus;

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

class RunSessionTest {

    private static final RunItem RUN = new RunItem("20261003T000000000Z-a3f9c21b", "p",
        Instant.parse("2026-10-03T00:00:00Z"), "runs/p/20261003T000000000Z-a3f9c21b", "c5.2xlarge",
        RunStatus.LAUNCHING, null, null, Map.of(), null);

    /** The run item in memory, applying the same rules the DynamoDB conditions enforce. */
    static final class FakeRecorder implements RunRecorder {
        final List<String> writes = new ArrayList<>();
        String status;
        String instanceId;
        RuntimeException failReserve;
        RuntimeException failLaunched;
        RuntimeException failStop;
        /** Runs just before a stop write, as an instance's own write racing it would. */
        Runnable beforeStop = () -> { };

        @Override
        public void reserve(RunItem run) {
            if (failReserve != null) throw failReserve;
            writes.add("reserve");
            status = RunStatus.LAUNCHING;
        }

        @Override
        public Write launched(RunItem run, String id) {
            if (failLaunched != null) throw failLaunched;
            writes.add("launched");
            if (!RunStatus.LAUNCHING.equals(status)) return Write.REFUSED;
            status = RunStatus.LAUNCHED;
            instanceId = id;
            return Write.WRITTEN;
        }

        @Override
        public Write launchFailed(RunItem run, String errorCode) {
            writes.add("launch-failed:" + errorCode);
            return guarded(RunStatus.LAUNCH_FAILED);
        }

        @Override
        public Write stop(RunItem run, String s) {
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
            if (status == null || RunStatus.isTerminal(status)) return Write.REFUSED;
            status = s;
            return Write.WRITTEN;
        }

        @Override
        public Optional<RunItem> read(RunItem run) {
            return status == null ? Optional.empty() : Optional.of(new RunItem(run.runId(), run.project(),
                run.createdAt(), run.resultPath(), run.instanceType(), status, instanceId, null, Map.of(), null));
        }

        @Override
        public Optional<RunItem> find(String runId) {
            return read(RUN);
        }

        @Override
        public List<RunItem> newestFirst(Predicate<RunItem> filter, int limit) {
            return read(RUN).filter(filter).stream().toList();
        }
    }

    static final class FakeInstances implements RunSession.Instances {
        final Map<String, String> states = new HashMap<>();
        final Map<String, String> byRunId = new HashMap<>();
        final List<String> terminated = new ArrayList<>();
        RuntimeException failTerminate;

        @Override
        public String state(String instanceId) {
            return states.getOrDefault(instanceId, "unknown");
        }

        @Override
        public Optional<String> findLive(String runId) {
            return Optional.ofNullable(byRunId.get(runId));
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
    private final RunSession session = new RunSession(RUN, recorder, recorder, instances);

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

        assertThat(session.confirmLaunched("i-1")).isEqualTo(RunSession.Confirmation.CONFIRMED);
        assertThat(recorder.status).isEqualTo(RunStatus.LAUNCHED);
        assertThat(session.instanceId()).isEqualTo("i-1");
    }

    /** The id is held before the write, so an interrupt during its retries still finds the instance. */
    @Test
    void aFailedLaunchedWriteProceedsAndAnInterruptStillTerminates() {
        session.reserve();
        recorder.failLaunched = new IllegalStateException("network down");

        assertThat(session.confirmLaunched("i-1")).isEqualTo(RunSession.Confirmation.UNCONFIRMED);
        session.stop(RunStatus.CANCELLED);

        assertThat(instances.terminated).containsExactly("i-1");
        assertThat(recorder.status).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void aRunCancelledWhileLaunchingTerminatesTheInstanceItJustLaunched() {
        session.reserve();
        recorder.stop(RUN, RunStatus.CANCELLED);   // another operator's `baas runs terminate`

        assertThat(session.confirmLaunched("i-1")).isEqualTo(RunSession.Confirmation.CANCELLED_WHILE_LAUNCHING);
        assertThat(instances.terminated).containsExactly("i-1");
        assertThat(session.ended()).isTrue();
        assertThat(session.endStatus()).isEqualTo(RunStatus.CANCELLED);
    }

    /** The instance's `running` landed first; the late `launched` must not move it backwards. */
    @Test
    void aLateLaunchedWriteDoesNotMoveTheStatusBack() {
        session.reserve();
        recorder.instanceWrites(RunStatus.RUNNING);

        assertThat(session.confirmLaunched("i-1")).isEqualTo(RunSession.Confirmation.CONFIRMED);
        assertThat(recorder.status).isEqualTo(RunStatus.RUNNING);
        assertThat(instances.terminated).isEmpty();
    }

    @Test
    void aFailedLaunchIsRecordedAndEndsTheSession() {
        session.reserve();

        session.recordLaunchFailed("InsufficientInstanceCapacity");

        assertThat(recorder.status).isEqualTo(RunStatus.LAUNCH_FAILED);
        assertThat(recorder.writes).contains("launch-failed:InsufficientInstanceCapacity");
        assertThat(session.ended()).as("the shutdown hook then has nothing to stop").isTrue();
        assertThat(session.endStatus()).isEqualTo(RunStatus.LAUNCH_FAILED);
    }

    // ─── stop ────────────────────────────────────────────────────────────────────

    @Test
    void stopRecordsTheReasonBeforeTerminating() {
        session.reserve();
        session.confirmLaunched("i-1");

        session.stop(RunStatus.CANCELLED);

        assertThat(recorder.writes).containsSubsequence("stop:cancelled");
        assertThat(recorder.status).isEqualTo(RunStatus.CANCELLED);
        assertThat(instances.terminated).containsExactly("i-1");
        assertThat(session.endStatus()).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void aRunStillGoingHasNoEndStatus() {
        session.reserve();
        session.confirmLaunched("i-1");

        assertThat(session.endStatus()).isNull();
    }

    @Test
    void aThrowingStatusWriteStillTerminates() {
        session.reserve();
        session.confirmLaunched("i-1");
        recorder.failStop = new IllegalStateException("timed out after 5s");

        session.stop(RunStatus.CANCELLED);

        assertThat(instances.terminated).containsExactly("i-1");
    }

    /** Interrupted while RunInstances was in flight: no id yet, so the run-id tag finds it. */
    @Test
    void anInterruptBeforeTheLaunchReturnedFindsTheInstanceByItsTag() {
        session.reserve();
        instances.byRunId.put(RUN.runId(), "i-tagged");

        session.stop(RunStatus.CANCELLED);

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

        session.stop(RunStatus.CANCELLED);

        assertThat(instances.terminated).isEmpty();
        assertThat(recorder.status).isEqualTo(RunStatus.CANCELLED);
        assertThat(recorder.instanceWrites(RunStatus.RUNNING)).isEqualTo(RunRecorder.Write.REFUSED);
    }

    /** Ctrl+C between the instance's final write and the next poll: its log upload must finish. */
    @Test
    void anInterruptAfterTheInstanceRecordedItsOutcomeLeavesItToTerminateItself() {
        for (String outcome : List.of(RunStatus.COMPLETED, RunStatus.failed(3))) {
            FakeRecorder recorder = new FakeRecorder();
            FakeInstances instances = new FakeInstances();
            RunSession session = new RunSession(RUN, recorder, recorder, instances);
            session.reserve();
            session.confirmLaunched("i-1");
            recorder.instanceWrites(outcome);

            session.stop(RunStatus.CANCELLED);

            assertThat(instances.terminated).as(outcome).isEmpty();
            assertThat(recorder.status).as(outcome).isEqualTo(outcome);
        }
    }

    /** Refused because someone else stopped it first: that guarantees nothing about the instance. */
    @Test
    void anInterruptAfterACancellationFromElsewhereStillTerminates() {
        session.reserve();
        session.confirmLaunched("i-1");
        recorder.stop(RUN, RunStatus.CANCELLED);

        session.stop(RunStatus.CANCELLED);

        assertThat(instances.terminated).containsExactly("i-1");
    }

    @Test
    void aFailedTerminationIsLoggedNotThrownFromTheHook() {
        session.reserve();
        session.confirmLaunched("i-1");
        instances.failTerminate = new IllegalStateException("UnauthorizedOperation");

        session.stop(RunStatus.CANCELLED);

        assertThat(recorder.status).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void stopIsANoOpOnceTheRunEnded() {
        session.reserve();
        session.recordLaunchFailed("X");

        session.stop(RunStatus.CANCELLED);

        assertThat(recorder.writes).doesNotContain("stop:cancelled");
    }

    // ─── await ───────────────────────────────────────────────────────────────────

    private final AtomicLong clock = new AtomicLong();

    private RunSession.Outcome await(int capSeconds, boolean measurementsStored, Runnable onEachSleep)
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

        var outcome = await(600, false, () -> recorder.instanceWrites(RunStatus.COMPLETED));

        assertThat(outcome).isEqualTo(new RunSession.Outcome(RunStatus.COMPLETED, 0));
        assertThat(instances.terminated).as("the instance terminates itself after its log upload").isEmpty();
        assertThat(session.endStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void aFailedBenchmarkExitsOneAsItAlwaysHas() throws Exception {
        launched();

        var outcome = await(600, false, () -> recorder.instanceWrites(RunStatus.failed(3)));

        assertThat(outcome).isEqualTo(new RunSession.Outcome("failed:3", 1));
        assertThat(session.endStatus()).isEqualTo("failed:3");
    }

    @Test
    void theWatchdogsTimeoutExitsOne() throws Exception {
        launched();

        var outcome = await(600, false, () -> recorder.instanceWrites(RunStatus.TIMED_OUT));

        assertThat(outcome.exitCode()).isEqualTo(1);
        assertThat(outcome.status()).isEqualTo(RunStatus.TIMED_OUT);
    }

    @Test
    void theCapRecordsATimeoutNotACancellation() throws Exception {
        launched();

        var outcome = await(60, false, () -> { });

        assertThat(outcome).isEqualTo(new RunSession.Outcome(RunStatus.TIMED_OUT, 1));
        assertThat(session.endStatus()).isEqualTo(RunStatus.TIMED_OUT);
        assertThat(recorder.status).isEqualTo(RunStatus.TIMED_OUT);
        assertThat(instances.terminated).containsExactly("i-1");
    }

    /** The instance completed during the last sleep: the cap must report that, not a timeout. */
    @Test
    void theCapHonoursAnOutcomeRecordedDuringTheLastSleep() throws Exception {
        launched();

        var outcome = await(60, false, () -> {
            if (clock.get() > 60_000) recorder.instanceWrites(RunStatus.COMPLETED);
        });

        assertThat(outcome).isEqualTo(new RunSession.Outcome(RunStatus.COMPLETED, 0));
        assertThat(instances.terminated).isEmpty();
    }

    /** The instance's write lands between the cap's read and its own: the refused timeout is not reported. */
    @Test
    void theCapReportsAnOutcomeThatBeatItsOwnWrite() throws Exception {
        launched();
        recorder.beforeStop = () -> recorder.instanceWrites(RunStatus.COMPLETED);

        var outcome = await(60, false, () -> { });

        assertThat(outcome).isEqualTo(new RunSession.Outcome(RunStatus.COMPLETED, 0));
        assertThat(recorder.status).isEqualTo(RunStatus.COMPLETED);
        assertThat(instances.terminated).isEmpty();
    }

    @Test
    void aCancellationFromElsewhereStillTerminatesALiveInstance() throws Exception {
        launched();

        var outcome = await(600, false, () -> recorder.stop(RUN, RunStatus.CANCELLED));

        assertThat(outcome).isEqualTo(new RunSession.Outcome(RunStatus.CANCELLED, 1));
        assertThat(instances.terminated).containsExactly("i-1");
    }

    @Test
    void aStatusWrittenJustBeforeTerminationIsHonoured() throws Exception {
        launched();

        var outcome = await(600, false, () -> {
            instances.states.put("i-1", "terminated");
            recorder.instanceWrites(RunStatus.COMPLETED);
        });

        assertThat(outcome.exitCode()).isZero();
    }

    @Test
    void aGoneInstanceWithStoredMeasurementsIsALostStatusNotAVanishedRun() throws Exception {
        launched();

        var outcome = await(600, true, () -> instances.states.put("i-1", "terminated"));

        assertThat(outcome).isEqualTo(new RunSession.Outcome(RunSession.Outcome.STATUS_LOST, 1));
        assertThat(session.endStatus()).isEqualTo(RunSession.Outcome.STATUS_LOST);
    }

    @Test
    void aGoneInstanceWithNothingStoredVanished() throws Exception {
        launched();

        var outcome = await(600, false, () -> instances.states.put("i-1", "terminated"));

        assertThat(outcome).isEqualTo(new RunSession.Outcome(RunStatus.VANISHED, 1));
        assertThat(session.ended()).isTrue();
        assertThat(session.endStatus()).isEqualTo(RunStatus.VANISHED);
    }
}

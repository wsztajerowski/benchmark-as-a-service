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
    }

    @Test
    void aFailedBenchmarkExitsOneAsItAlwaysHas() throws Exception {
        launched();

        var outcome = await(600, false, () -> recorder.instanceWrites(RunStatus.failed(3)));

        assertThat(outcome).isEqualTo(new RunSession.Outcome("failed:3", 1));
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
        assertThat(recorder.status).isEqualTo(RunStatus.TIMED_OUT);
        assertThat(instances.terminated).containsExactly("i-1");
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
    }

    @Test
    void aGoneInstanceWithNothingStoredVanished() throws Exception {
        launched();

        var outcome = await(600, false, () -> instances.states.put("i-1", "terminated"));

        assertThat(outcome).isEqualTo(new RunSession.Outcome(RunStatus.VANISHED, 1));
        assertThat(session.ended()).isTrue();
    }
}

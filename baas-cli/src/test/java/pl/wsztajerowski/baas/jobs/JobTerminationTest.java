package pl.wsztajerowski.baas.jobs;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.model.JobStatus;

import static org.assertj.core.api.Assertions.assertThat;

class JobTerminationTest {

    private final JobSessionTest.FakeRecorder recorder = new JobSessionTest.FakeRecorder();
    private final JobSessionTest.FakeInstances instances = new JobSessionTest.FakeInstances();
    private final JobTermination termination = new JobTermination(recorder, recorder, instances);

    private void runningOn(String instanceId) {
        recorder.status = JobStatus.RUNNING;
        recorder.instanceId = instanceId;
        instances.states.put(instanceId, "running");
    }

    @Test
    void anInFlightJobIsCancelledAndItsInstanceTerminated() {
        runningOn("i-1");

        assertThat(termination.terminate("r", () -> true)).isZero();
        assertThat(recorder.status).isEqualTo(JobStatus.CANCELLED);
        assertThat(instances.terminated).containsExactly("i-1");
    }

    @Test
    void aFinishedJobIsLeftAlone() {
        recorder.status = JobStatus.COMPLETED;
        recorder.instanceId = "i-1";
        instances.states.put("i-1", "terminated");

        assertThat(termination.terminate("r", () -> { throw new AssertionError("not asked"); })).isZero();
        assertThat(recorder.status).isEqualTo(JobStatus.COMPLETED);
        assertThat(instances.terminated).isEmpty();
    }

    /** An earlier termination failed after `cancelled` landed: a retry must still stop it. */
    @Test
    void aLiveInstanceIsStoppedEvenWhenTheItemIsAlreadyTerminal() {
        runningOn("i-1");
        recorder.status = JobStatus.CANCELLED;

        assertThat(termination.terminate("r", () -> true)).isZero();
        assertThat(instances.terminated).containsExactly("i-1");
        assertThat(recorder.status).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void aFailedTerminationExitsNonZero() {
        runningOn("i-1");
        instances.failTerminate = new IllegalStateException("UnauthorizedOperation");

        assertThat(termination.terminate("r", () -> true)).isEqualTo(1);
    }

    @Test
    void anUnknownJobFails() {
        assertThat(termination.terminate("no-such-run", () -> true)).isEqualTo(1);
    }

    /**
     * R6: an id with no item in this deployment is another deployment's job, or none. Its tagged
     * instance — which a scoped lookup would not even find — is never terminated from here.
     */
    @Test
    void anIdWithNoItemTerminatesNothing() {
        instances.byJobId.put("20261001T000000000Z-0badc0de", "i-other-deployment");
        recorder.status = null;

        assertThat(termination.terminate("20261001T000000000Z-0badc0de", () -> { throw new AssertionError("not asked"); }))
            .isEqualTo(1);
        assertThat(instances.terminated).isEmpty();
        assertThat(recorder.writes).isEmpty();
    }

    /**
     * R8: the job read as running, and the instance recorded its outcome just before the cancel
     * write. The refused write is re-read, and the instance — uploading its boot log — is left alone.
     */
    @Test
    void anInstanceThatCompletesBeforeTheCancelIsLeftToTerminateItself() {
        runningOn("i-1");
        recorder.beforeStop = () -> recorder.instanceWrites(JobStatus.COMPLETED);

        assertThat(termination.terminate("r", () -> true)).isZero();
        assertThat(recorder.status).isEqualTo(JobStatus.COMPLETED);
        assertThat(instances.terminated).isEmpty();
    }

    /** R10: the watchdog recorded the timeout and is uploading the boot log before it terminates. */
    @Test
    void aJobTheWatchdogTimedOutIsLeftToTerminateItself() {
        runningOn("i-1");
        recorder.status = JobStatus.TIMED_OUT;

        assertThat(termination.terminate("r", () -> { throw new AssertionError("not asked"); })).isZero();
        assertThat(instances.terminated).isEmpty();
    }

    @Test
    void aDeclinedConfirmationChangesNothing() {
        runningOn("i-1");

        assertThat(termination.terminate("r", () -> false)).isEqualTo(1);
        assertThat(recorder.status).isEqualTo(JobStatus.RUNNING);
        assertThat(instances.terminated).isEmpty();
    }

    @Test
    void aLaunchingJobWithNoInstanceIdIsFoundByItsTag() {
        recorder.status = JobStatus.LAUNCHING;
        instances.byJobId.put(recorder.find("r").orElseThrow().jobId(), "i-tagged");

        assertThat(termination.terminate("r", () -> true)).isZero();
        assertThat(instances.terminated).containsExactly("i-tagged");
        assertThat(recorder.status).isEqualTo(JobStatus.CANCELLED);
    }

    /** U30: the instance wrote this outcome and is uploading its boot log before it terminates. */
    @Test
    void aJobTheInstanceFinishedIsLeftToTerminateItself() {
        runningOn("i-1");
        recorder.status = JobStatus.COMPLETED;

        assertThat(termination.terminate("r", () -> { throw new AssertionError("not asked"); })).isZero();
        assertThat(instances.terminated).isEmpty();

        recorder.status = JobStatus.failed(3);
        assertThat(termination.terminate("r", () -> { throw new AssertionError("not asked"); })).isZero();
        assertThat(instances.terminated).isEmpty();
    }
}

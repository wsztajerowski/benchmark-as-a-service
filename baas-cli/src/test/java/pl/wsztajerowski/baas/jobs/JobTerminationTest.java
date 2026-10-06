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

    /** Launched by a CLI from before job items: only the instance's tag knows the job id. */
    @Test
    void aJobWithNoItemIsStoppedByItsInstanceTag() {
        instances.byJobId.put("20261001T000000000Z-0badc0de", "i-old");

        assertThat(termination.terminate("20261001T000000000Z-0badc0de", () -> true)).isZero();
        assertThat(instances.terminated).containsExactly("i-old");
        assertThat(recorder.writes).as("there is no item to write").isEmpty();
    }

    @Test
    void aJobWithNoItemIsLeftAloneWhenTheConfirmationIsDeclined() {
        instances.byJobId.put("20261001T000000000Z-0badc0de", "i-old");

        assertThat(termination.terminate("20261001T000000000Z-0badc0de", () -> false)).isEqualTo(1);
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

package pl.wsztajerowski.baas.runs;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.model.RunStatus;

import static org.assertj.core.api.Assertions.assertThat;

class RunTerminationTest {

    private final RunSessionTest.FakeRecorder recorder = new RunSessionTest.FakeRecorder();
    private final RunSessionTest.FakeInstances instances = new RunSessionTest.FakeInstances();
    private final RunTermination termination = new RunTermination(recorder, recorder, instances);

    private void runningOn(String instanceId) {
        recorder.status = RunStatus.RUNNING;
        recorder.instanceId = instanceId;
        instances.states.put(instanceId, "running");
    }

    @Test
    void anInFlightRunIsCancelledAndItsInstanceTerminated() {
        runningOn("i-1");

        assertThat(termination.terminate("r", () -> true)).isZero();
        assertThat(recorder.status).isEqualTo(RunStatus.CANCELLED);
        assertThat(instances.terminated).containsExactly("i-1");
    }

    @Test
    void aFinishedRunIsLeftAlone() {
        recorder.status = RunStatus.COMPLETED;
        recorder.instanceId = "i-1";
        instances.states.put("i-1", "terminated");

        assertThat(termination.terminate("r", () -> { throw new AssertionError("not asked"); })).isZero();
        assertThat(recorder.status).isEqualTo(RunStatus.COMPLETED);
        assertThat(instances.terminated).isEmpty();
    }

    /** An earlier termination failed after `cancelled` landed: a retry must still stop it. */
    @Test
    void aLiveInstanceIsStoppedEvenWhenTheItemIsAlreadyTerminal() {
        runningOn("i-1");
        recorder.status = RunStatus.CANCELLED;

        assertThat(termination.terminate("r", () -> true)).isZero();
        assertThat(instances.terminated).containsExactly("i-1");
        assertThat(recorder.status).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void aFailedTerminationExitsNonZero() {
        runningOn("i-1");
        instances.failTerminate = new IllegalStateException("UnauthorizedOperation");

        assertThat(termination.terminate("r", () -> true)).isEqualTo(1);
    }

    @Test
    void anUnknownRunFails() {
        assertThat(termination.terminate("no-such-run", () -> true)).isEqualTo(1);
    }

    /** Launched by a CLI from before run items: only the instance's tag knows the run id. */
    @Test
    void aRunWithNoItemIsStoppedByItsInstanceTag() {
        instances.byRunId.put("20261001T000000000Z-0badc0de", "i-old");

        assertThat(termination.terminate("20261001T000000000Z-0badc0de", () -> true)).isZero();
        assertThat(instances.terminated).containsExactly("i-old");
        assertThat(recorder.writes).as("there is no item to write").isEmpty();
    }

    @Test
    void aRunWithNoItemIsLeftAloneWhenTheConfirmationIsDeclined() {
        instances.byRunId.put("20261001T000000000Z-0badc0de", "i-old");

        assertThat(termination.terminate("20261001T000000000Z-0badc0de", () -> false)).isEqualTo(1);
        assertThat(instances.terminated).isEmpty();
    }

    @Test
    void aDeclinedConfirmationChangesNothing() {
        runningOn("i-1");

        assertThat(termination.terminate("r", () -> false)).isEqualTo(1);
        assertThat(recorder.status).isEqualTo(RunStatus.RUNNING);
        assertThat(instances.terminated).isEmpty();
    }

    @Test
    void aLaunchingRunWithNoInstanceIdIsFoundByItsTag() {
        recorder.status = RunStatus.LAUNCHING;
        instances.byRunId.put(recorder.find("r").orElseThrow().runId(), "i-tagged");

        assertThat(termination.terminate("r", () -> true)).isZero();
        assertThat(instances.terminated).containsExactly("i-tagged");
        assertThat(recorder.status).isEqualTo(RunStatus.CANCELLED);
    }

    /** U30: the instance wrote this outcome and is uploading its boot log before it terminates. */
    @Test
    void aRunTheInstanceFinishedIsLeftToTerminateItself() {
        runningOn("i-1");
        recorder.status = RunStatus.COMPLETED;

        assertThat(termination.terminate("r", () -> { throw new AssertionError("not asked"); })).isZero();
        assertThat(instances.terminated).isEmpty();

        recorder.status = RunStatus.failed(3);
        assertThat(termination.terminate("r", () -> { throw new AssertionError("not asked"); })).isZero();
        assertThat(instances.terminated).isEmpty();
    }
}

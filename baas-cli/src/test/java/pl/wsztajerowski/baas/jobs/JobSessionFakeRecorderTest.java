package pl.wsztajerowski.baas.jobs;

/** The recorder {@code JobSessionTest} drives sessions against keeps the table's rules. */
class JobSessionFakeRecorderTest extends JobRecorderContract {

    private final JobRecorder recorder = new JobSessionTest.FakeRecorder();

    @Override
    protected JobRecorder recorder() {
        return recorder;
    }
}

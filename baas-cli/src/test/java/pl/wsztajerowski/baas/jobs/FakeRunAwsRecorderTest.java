package pl.wsztajerowski.baas.jobs;

/** The in-memory recorder {@link FakeRunAws} launches against keeps the table's rules. */
class FakeRunAwsRecorderTest extends JobRecorderContract {

    private final JobRecorder recorder = new FakeRunAws().recorder("dev-results");

    @Override
    protected JobRecorder recorder() {
        return recorder;
    }
}

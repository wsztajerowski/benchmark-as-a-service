package pl.wsztajerowski.baas.jobs;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.model.JobStatus;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conditional-write rules every {@link JobRecorder} keeps, run against each implementation: the
 * DynamoDB one in {@code DynamoDbJobRecorderIT}, and the in-memory one tests launch against. A fake
 * that drifted from the table would let a launch test pass on rules the table does not have.
 */
public abstract class JobRecorderContract {

    protected abstract JobRecorder recorder();

    private static final JobItem JOB = new JobItem("20261003T000000000Z-c0ffee00", "p",
        Instant.parse("2026-10-03T00:00:00Z"), "jobs/p/20261003T000000000Z-c0ffee00", "c5.2xlarge",
        JobStatus.LAUNCHING, null, null, Map.of("project", "p"), null);

    private String status() {
        return recorder().read(JOB).map(JobItem::status).orElse(null);
    }

    @Test
    void aReservedJobIsLaunching() {
        recorder().reserve(JOB);

        assertThat(status()).isEqualTo(JobStatus.LAUNCHING);
    }

    @Test
    void launchedIsWrittenOnlyOverLaunching() {
        recorder().reserve(JOB);

        assertThat(recorder().launched(JOB, "i-1")).isEqualTo(JobRecorder.Write.WRITTEN);
        assertThat(recorder().launched(JOB, "i-1")).isEqualTo(JobRecorder.Write.REFUSED);
        assertThat(status()).isEqualTo(JobStatus.LAUNCHED);
    }

    @Test
    void theFirstTerminalStatusWins() {
        recorder().reserve(JOB);

        assertThat(recorder().stop(JOB, JobStatus.CANCELLED)).isEqualTo(JobRecorder.Write.WRITTEN);
        assertThat(recorder().stop(JOB, JobStatus.TIMED_OUT)).isEqualTo(JobRecorder.Write.REFUSED);
        assertThat(recorder().launchFailed(JOB, "InsufficientInstanceCapacity")).isEqualTo(JobRecorder.Write.REFUSED);
        assertThat(status()).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void aStopIsWrittenOverAnyStatusThatIsNotTerminal() {
        recorder().reserve(JOB);
        recorder().launched(JOB, "i-1");

        assertThat(recorder().stop(JOB, JobStatus.TIMED_OUT)).isEqualTo(JobRecorder.Write.WRITTEN);
        assertThat(status()).isEqualTo(JobStatus.TIMED_OUT);
    }

    @Test
    void aLaunchThatNeverHappenedIsRecordedAsSuch() {
        recorder().reserve(JOB);

        assertThat(recorder().launchFailed(JOB, "InsufficientInstanceCapacity")).isEqualTo(JobRecorder.Write.WRITTEN);
        assertThat(status()).isEqualTo(JobStatus.LAUNCH_FAILED);
    }
}

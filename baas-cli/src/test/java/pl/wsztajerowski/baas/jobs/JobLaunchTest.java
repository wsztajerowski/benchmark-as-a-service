package pl.wsztajerowski.baas.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.wsztajerowski.baas.session.CoreStack;
import pl.wsztajerowski.baas.session.DeploymentNames;
import pl.wsztajerowski.baas.console.StatusLine;
import pl.wsztajerowski.baas.model.JobStatus;
import pl.wsztajerowski.baas.results.ResultRow;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A whole {@code baas run}, from a resolved request to its exit code, against {@link FakeRunAws}.
 * Before this, the success path ran in no JVM test at all — only in the paid end-to-end job — and
 * the ordering rules it carries (every precondition before any upload, the reservation before the
 * launch, the shutdown hook armed before {@code RunInstances}) were pinned by nothing.
 */
class JobLaunchTest {

    private static final DeploymentNames DEV = DeploymentNames.of("dev");

    @TempDir
    Path dir;

    private final FakeRunAws aws = new FakeRunAws();
    private final Listener listener = new Listener();

    /** Records what the command would see, interleaved with the AWS calls. */
    private final class Listener implements JobLaunch.Listener {
        String jobId;
        String resultPath;
        String instanceId;
        String detachedStatus;
        JobSession session;
        List<ResultRow> printed;

        @Override
        public void named(String jobId, String resultPath) {
            this.jobId = jobId;
            this.resultPath = resultPath;
        }

        @Override
        public void reserved(JobSession session) {
            aws.events.add("hook armed");
            this.session = session;
        }

        @Override
        public void launched(String instanceId) {
            this.instanceId = instanceId;
        }

        @Override
        public void detached(String jobStatus) {
            this.detachedStatus = jobStatus;
        }

        @Override
        public StatusLine openStatusLine() {
            return null;
        }

        @Override
        public void printResults(List<ResultRow> rows, String jobId) {
            printed = rows;
        }
    }

    private JobLaunch launch() {
        return new JobLaunch(aws, DEV, listener, () -> 0L, millis -> { }, 0);
    }

    private JobLaunch.Request request(Path runnerJar, boolean detach) throws Exception {
        Path benchmarkJar = Files.writeString(dir.resolve("b.jar"), "jar");
        return new JobLaunch.Request("jmh", List.of("MyBenchmark", "-f", "1"), benchmarkJar, runnerJar,
            "c5.2xlarge", "lynx-journal", "dev-results", 600, 900,
            Map.of("project", "lynx-journal", "type", "jmh", "source", "local"), detach, "7.1.1",
            "Wiktor-Sztajerowski/benchmark-as-a-service");
    }

    private int indexOf(String prefix) {
        for (int i = 0; i < aws.events.size(); i++) {
            if (aws.events.get(i).startsWith(prefix)) return i;
        }
        return -1;
    }

    @Test
    void aCompletedJobRunsEveryStepInOrderAndShowsItsMeasurements() throws Exception {
        int exit = launch().run(request(null, false));

        assertThat(exit).isZero();
        assertThat(aws.events).startsWith(
            "currentImage /dev/runner/ami-id",
            "stackOutputs dev");
        assertThat(indexOf("upload ")).isGreaterThan(indexOf("stackOutputs"));
        assertThat(indexOf("reserve")).isGreaterThan(indexOf("pinnedRunnerJar"));
        assertThat(indexOf("hook armed")).isGreaterThan(indexOf("reserve"));
        assertThat(indexOf("runInstance")).isGreaterThan(indexOf("hook armed"));
        assertThat(aws.events).contains("runInstance ami-1 c5.2xlarge subnet-1 sg-1 dev-profile-runner");
        assertThat(aws.events.getLast()).startsWith("jobRows ");
        assertThat(listener.printed).isNotNull();
        assertThat(listener.session.endStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(listener.instanceId).isEqualTo("i-1");
        assertThat(listener.resultPath).isEqualTo("jobs/lynx-journal/" + listener.jobId);
    }

    @Test
    void theReleasePinnedRunnerIsUsedUnlessALocalOneIsGiven() throws Exception {
        launch().run(request(null, false));
        assertThat(aws.events).contains("pinnedRunnerJar 7.1.1");
        assertThat(aws.uploads.keySet()).containsExactly("jobs/lynx-journal/" + listener.jobId + "/input/benchmark.jar");

        var other = new FakeRunAws();
        new JobLaunch(other, DEV, listener, () -> 0L, millis -> { }, 0)
            .run(request(Files.writeString(dir.resolve("r.jar"), "runner"), false));
        assertThat(other.events).noneMatch(e -> e.startsWith("pinnedRunnerJar"));
        assertThat(other.uploads.keySet()).contains("jobs/lynx-journal/" + listener.jobId + "/input/runner.jar");
    }

    @Test
    void theUserDataNamesTheJobItsTableAndTheReservedSortKey() throws Exception {
        launch().run(request(null, false));

        String script = new String(Base64.getDecoder().decode(aws.userData), StandardCharsets.UTF_8);
        assertThat(script).contains("export JOB_ID='" + listener.jobId + "'")
            .contains("export RESULTS_TABLE='dev-results'")
            .contains("export JOB_SORT_KEY='" + listener.session.job().sortKey() + "'")
            .contains("export AWS_REGION='eu-central-1'");
    }

    @Test
    void withNoRunnerImageNothingIsUploadedOrReserved() throws Exception {
        aws.image = Optional.empty();

        assertThat(launch().run(request(null, false))).isEqualTo(1);
        assertThat(aws.events).containsExactly("currentImage /dev/runner/ami-id");
        assertThat(listener.jobId).isNull();
    }

    @Test
    void aStackWithoutNetworkingFailsBeforeAnyUpload() {
        aws.outputs.remove(CoreStack.OUTPUT_SECURITY_GROUP_ID);

        assertThatThrownBy(() -> launch().run(request(null, false)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("did not report SecurityGroupId");
        assertThat(aws.events).noneMatch(e -> e.startsWith("upload "));
    }

    @Test
    void aReservationThatCannotBeWrittenLaunchesNothing() throws Exception {
        aws.reserveFailure = new IllegalStateException("AccessDenied");

        assertThat(launch().run(request(null, false))).isEqualTo(1);
        assertThat(aws.events).noneMatch(e -> e.startsWith("runInstance"));
        assertThat(aws.events).doesNotContain("hook armed");
    }

    @Test
    void aRefusedLaunchIsRecordedOnTheItemAndInTheJobsPrefix() throws Exception {
        aws.instance = FakeRunAws.Instance.REFUSED;

        assertThat(launch().run(request(null, false))).isEqualTo(1);
        assertThat(aws.events).contains("hook armed").anyMatch(e -> e.startsWith("launchFailed"));
        assertThat(aws.status).isEqualTo(JobStatus.LAUNCH_FAILED);
        String report = aws.texts.get("jobs/lynx-journal/" + listener.jobId + "/launch-error.txt");
        assertThat(report).contains("jobId: " + listener.jobId).contains("amiId: ami-1")
            .contains("subnetId: subnet-1").contains("message: InsufficientInstanceCapacity");
    }

    @Test
    void aDetachedLaunchReturnsOnceConfirmedWithoutPolling() throws Exception {
        assertThat(launch().run(request(null, true))).isZero();

        assertThat(listener.detachedStatus).isEqualTo(JobStatus.LAUNCHED);
        assertThat(aws.events).noneMatch(e -> e.startsWith("jobRows"));
        assertThat(listener.session.ended()).isFalse();
    }

    @Test
    void aJobThatEndedBeforeItsLaunchWasConfirmedIsReportedNotStopped() throws Exception {
        aws.instance = FakeRunAws.Instance.COMPLETED_BEFORE_CONFIRM;

        assertThat(launch().run(request(null, false))).isZero();
        assertThat(aws.events).noneMatch(e -> e.startsWith("terminate"));
        assertThat(aws.events).noneMatch(e -> e.startsWith("stop "));
    }

    @Test
    void progressIsLoggedWhenNoStatusLineIsShown() throws Exception {
        List<String> slept = new ArrayList<>();
        int exit = new JobLaunch(aws, DEV, listener, () -> 0L, millis -> slept.add("slept " + millis), 7)
            .run(request(null, false));

        assertThat(exit).isZero();
        assertThat(slept).containsExactly("slept 7", "slept 7");
    }
}

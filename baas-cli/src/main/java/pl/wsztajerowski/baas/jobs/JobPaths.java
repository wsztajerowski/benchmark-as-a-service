package pl.wsztajerowski.baas.jobs;

import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.results.ResultsQueryService;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Where a job's artifacts are: the result path on its job item, or — for a job recorded before job
 * items existed — the one on its first measurement. Owned by {@code jobs}, which knows about job
 * items; the measurement half stays in {@code results}, which no longer reaches into {@code jobs}.
 */
public class JobPaths implements AutoCloseable {

    private final JobRecorder jobs;
    private final ResultsQueryService measurements;

    public JobPaths(DynamoDbClient client, String table) {
        this(new DynamoDbJobRecorder(client, table), new ResultsQueryService(client, table));
    }

    JobPaths(JobRecorder jobs, ResultsQueryService measurements) {
        this.jobs = jobs;
        this.measurements = measurements;
    }

    /** The job's result path, or {@code null} when no job item or measurement knows the id. */
    public String resultPathForJob(String jobId) {
        return jobs.find(jobId).map(JobItem::resultPath)
            .orElseGet(() -> measurements.resultPathFromMeasurements(jobId));
    }

    @Override
    public void close() {
        measurements.close();
    }
}

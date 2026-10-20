package pl.wsztajerowski.baas.jobs;

import pl.wsztajerowski.baas.model.JobItem;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Where a job's artifacts are: the result path on its job item. Every job since
 * {@code run-status-in-dynamodb} has one, written before its instance is launched, and the
 * deployments were rebuilt after that, so a job without one is a job that does not exist.
 */
public class JobPaths implements AutoCloseable {

    private final DynamoDbClient client;
    private final JobRecorder jobs;

    public JobPaths(DynamoDbClient client, String table) {
        this.client = client;
        this.jobs = new DynamoDbJobRecorder(client, table);
    }

    /** The job's result path, or {@code null} when no job item has the id. */
    public String resultPathForJob(String jobId) {
        return jobs.find(jobId).map(JobItem::resultPath).orElse(null);
    }

    @Override
    public void close() {
        client.close();
    }
}

package pl.wsztajerowski.baas.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One job, as recorded at {@code pk = JOB}. The identity fields are written once, by the CLI's
 * {@code launching} reservation; later writes change only {@code status}, {@code instanceId},
 * {@code errorCode} and {@code updatedAt}.
 *
 * <p>{@code tags} are the CLI-side tags (project, source, type, caller tags), never the ones the
 * instance observes: those belong to measurements, and {@code environment.json} carries them for
 * the job.
 *
 * @param instanceId absent until {@code launched} or the instance's own {@code running} write
 * @param errorCode  the AWS error code of a failed launch, otherwise absent
 * @param updatedAt  the CLI's clock at its last write; the instance never sets it
 * @param watchdogSeconds the job's watchdog bound (timeout plus margin), written by the reservation
 *                   alone so {@code baas jobs connect} and {@code show} can say when the instance
 *                   ends; absent on items reserved before it was recorded
 */
public record JobItem(
    String jobId,
    String project,
    Instant createdAt,
    String resultPath,
    String instanceType,
    String status,
    String instanceId,
    String errorCode,
    Map<String, String> tags,
    Instant updatedAt,
    Integer watchdogSeconds) {

    public JobItem {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(createdAt, "createdAt");
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    /** A job item with no recorded watchdog bound. */
    public JobItem(String jobId, String project, Instant createdAt, String resultPath, String instanceType,
                   String status, String instanceId, String errorCode, Map<String, String> tags,
                   Instant updatedAt) {
        this(jobId, project, createdAt, resultPath, instanceType, status, instanceId, errorCode, tags,
            updatedAt, null);
    }

    public String sortKey() {
        return ResultKeys.jobSortKey(createdAt, jobId);
    }

    public boolean isTerminal() {
        return JobStatus.isTerminal(status);
    }
}

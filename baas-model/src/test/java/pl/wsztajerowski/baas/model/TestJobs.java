package pl.wsztajerowski.baas.model;

import java.time.Instant;
import java.util.Map;

/**
 * {@link JobItem}s for tests, by name rather than by position (T6): a test sets what it is about and
 * every other field keeps the default here. Shared with the CLI's tests through baas-model's test-jar.
 */
public final class TestJobs {

    private TestJobs() {
    }

    /** Project {@code p}, created 2026-10-03, {@code launching} on a c5.2xlarge, no tags. */
    public static Builder job(String jobId) {
        return new Builder(jobId);
    }

    /** Every field of {@code job}, to change some. */
    public static Builder copy(JobItem job) {
        return job(job.jobId()).project(job.project()).createdAt(job.createdAt()).resultPath(job.resultPath())
            .instanceType(job.instanceType()).status(job.status()).instanceId(job.instanceId())
            .errorCode(job.errorCode()).tags(job.tags()).updatedAt(job.updatedAt()).watchdogSeconds(job.watchdogSeconds());
    }

    public static final class Builder {
        private final String jobId;
        private String project = "p";
        private Instant createdAt = Instant.parse("2026-10-03T00:00:00Z");
        private String resultPath;
        private boolean resultPathSet;
        private String instanceType = "c5.2xlarge";
        private String status = JobStatus.LAUNCHING;
        private String instanceId;
        private String errorCode;
        private Map<String, String> tags = Map.of();
        private Instant updatedAt;
        private Integer watchdogSeconds;

        private Builder(String jobId) {
            this.jobId = jobId;
        }

        public Builder project(String project) { this.project = project; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder createdAt(String createdAt) { return createdAt(Instant.parse(createdAt)); }
        /** {@code jobs/<project>/<jobId>} unless set. */
        public Builder resultPath(String resultPath) { this.resultPath = resultPath; resultPathSet = true; return this; }
        public Builder instanceType(String instanceType) { this.instanceType = instanceType; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder instanceId(String instanceId) { this.instanceId = instanceId; return this; }
        public Builder errorCode(String errorCode) { this.errorCode = errorCode; return this; }
        public Builder tags(Map<String, String> tags) { this.tags = tags; return this; }
        public Builder updatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
        public Builder watchdogSeconds(Integer watchdogSeconds) { this.watchdogSeconds = watchdogSeconds; return this; }

        public JobItem build() {
            return new JobItem(jobId, project, createdAt,
                resultPathSet ? resultPath : "jobs/" + project + "/" + jobId,
                instanceType, status, instanceId, errorCode, tags, updatedAt, watchdogSeconds);
        }
    }
}

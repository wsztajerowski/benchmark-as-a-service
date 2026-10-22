package pl.wsztajerowski.baas.jobs;

import pl.wsztajerowski.baas.session.CoreStack;
import pl.wsztajerowski.baas.image.RunnerImage;
import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.model.JobStatus;
import pl.wsztajerowski.baas.results.ResultRow;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * {@link RunAws} in memory, recording every call in order in {@link #events}. The job item follows
 * the conditional-write rules the real table enforces (a write never replaces a terminal status),
 * and {@link #instance} plays the instance: by default it records {@code running} on its first
 * describe and {@code completed} on its second, the way user-data does.
 */
public final class FakeRunAws implements RunAws {

    /** How the fake instance behaves once launched. */
    public enum Instance {
        /** {@code running}, then {@code completed}, one poll apart. */
        COMPLETES,
        /** The whole job ended before {@code RunInstances} answered: {@code completed} is already stored. */
        COMPLETED_BEFORE_CONFIRM,
        /** EC2 refuses the launch. */
        REFUSED
    }

    public final List<String> events = new ArrayList<>();
    public final Map<String, String> uploads = new HashMap<>();
    public final Map<String, String> texts = new HashMap<>();
    public Optional<RunnerImage> image = Optional.of(new RunnerImage("ami-1", "1.3.0", "ami-parent", "2026-10-01T00:00:00Z"));
    public Map<String, String> outputs = new HashMap<>(Map.of(
        CoreStack.OUTPUT_SUBNET_ID, "subnet-1", CoreStack.OUTPUT_SECURITY_GROUP_ID, "sg-1"));
    public Instance instance = Instance.COMPLETES;
    public RuntimeException reserveFailure;
    public List<ResultRow> rows = List.of();
    public String userData;
    /** The item as the reservation wrote it. */
    public JobItem reserved;
    public String status;
    private int describes;

    @Override
    public String region() {
        return "eu-central-1";
    }

    @Override
    public Optional<RunnerImage> currentImage(String amiPointer) {
        events.add("currentImage " + amiPointer);
        return image;
    }

    @Override
    public Map<String, String> stackOutputs(String stack) {
        events.add("stackOutputs " + stack);
        return outputs;
    }

    @Override
    public void upload(Path file, String bucket, String key) {
        events.add("upload " + key);
        uploads.put(key, file.toString());
    }

    @Override
    public void putText(String bucket, String key, String text) {
        events.add("putText " + key);
        texts.put(key, text);
    }

    @Override
    public String pinnedRunnerJar(String bucket, String version, String sourceRepo) {
        events.add("pinnedRunnerJar " + version);
        return "releases/" + version + "/benchmark-runner.jar";
    }

    @Override
    public JobRecorder recorder(String table) {
        return recorder;
    }

    @Override
    public JobRecorder stopRecorder(String table) {
        return recorder;
    }

    @Override
    public Instances instances(String deployment) {
        return instances;
    }

    @Override
    public List<ResultRow> jobRows(String table, String jobId) {
        events.add("jobRows " + jobId);
        return rows;
    }

    private final JobRecorder recorder = new JobRecorder() {
        @Override
        public void reserve(JobItem job) {
            events.add("reserve");
            if (reserveFailure != null) throw reserveFailure;
            if (status != null) throw new IllegalStateException("ConditionalCheckFailed: already reserved");
            status = JobStatus.LAUNCHING;
            reserved = job;
        }

        @Override
        public Write launched(JobItem job, String instanceId) {
            events.add("launched");
            if (!JobStatus.LAUNCHING.equals(status)) return Write.REFUSED;
            status = JobStatus.LAUNCHED;
            return Write.WRITTEN;
        }

        @Override
        public Write launchFailed(JobItem job, String errorCode) {
            events.add("launchFailed " + errorCode);
            return conditional(JobStatus.LAUNCH_FAILED);
        }

        @Override
        public Write stop(JobItem job, String reason) {
            events.add("stop " + reason);
            return conditional(reason);
        }

        @Override
        public Optional<JobItem> read(JobItem job) {
            return status == null ? Optional.empty() : Optional.of(new JobItem(job.jobId(), job.project(),
                job.createdAt(), job.resultPath(), job.instanceType(), status, null, null, job.tags(), null, job.watchdogSeconds()));
        }

        @Override
        public Optional<JobItem> find(String jobId) {
            return Optional.empty();
        }

        @Override
        public List<JobItem> newestFirst(Predicate<JobItem> filter, int limit) {
            return List.of();
        }

        private Write conditional(String next) {
            if (status == null || JobStatus.isTerminal(status)) return Write.REFUSED;
            status = next;
            return Write.WRITTEN;
        }
    };

    private final Instances instances = new Instances() {
        @Override
        public String runInstance(String amiId, String instanceType, String subnetId, String securityGroupId,
                                  String instanceProfileName, String userData, String jobId, String deployment) {
            events.add("runInstance " + amiId + " " + instanceType + " " + subnetId + " " + securityGroupId
                + " " + instanceProfileName);
            FakeRunAws.this.userData = userData;
            if (instance == Instance.REFUSED) {
                throw new IllegalStateException("InsufficientInstanceCapacity");
            }
            if (instance == Instance.COMPLETED_BEFORE_CONFIRM) {
                status = JobStatus.COMPLETED;
            }
            return "i-1";
        }

        @Override
        public InstanceState state(String instanceId) {
            describes++;
            if (instance == Instance.COMPLETES) {
                status = describes == 1 ? JobStatus.RUNNING : JobStatus.COMPLETED;
            }
            return InstanceState.RUNNING;
        }

        @Override
        public Optional<String> findLive(String jobId) {
            return Optional.of("i-1");
        }

        @Override
        public void terminate(String instanceId) {
            events.add("terminate " + instanceId);
        }
    };
}

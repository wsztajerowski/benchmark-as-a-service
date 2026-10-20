package pl.wsztajerowski.baas.jobs;

import pl.wsztajerowski.baas.image.RunnerImage;
import pl.wsztajerowski.baas.results.ResultRow;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything {@code baas run} asks of AWS, in the terms {@link JobLaunch} needs — one narrow port
 * for one use case, as {@code SetupAws} is for setup. {@link AwsRunAws} is the real one; a test
 * hands {@link JobLaunch} its own, so the launch's ordering is exercised without an account.
 */
public interface RunAws {

    /** The region the deployment lives in, which every job's user-data names. */
    String region();

    /** The deployment's runner image, or empty when none has been built. */
    Optional<RunnerImage> currentImage(String amiPointer);

    /** The core stack's outputs, read per job — never cached. */
    Map<String, String> stackOutputs(String stack);

    void upload(Path file, String bucket, String key);

    /** Best effort is the caller's call: this throws when the write fails. */
    void putText(String bucket, String key, String text);

    /**
     * The release-pinned runner JAR's key, seeding it when absent; throws unless the object there
     * has the digest this CLI was built with.
     */
    String pinnedRunnerJar(String bucket, String version, String sourceRepo, String runnerSha256);

    /** The job item's writer. Never closed: the shutdown hook may still be using it. */
    JobRecorder recorder(String table);

    /** The same, with the stop path's short timeout. Never closed, for the same reason. */
    JobRecorder stopRecorder(String table);

    /** The deployment's runners. Never closed, for the same reason. */
    Instances instances(String deployment);

    /** The measurements a job stored; throws when the table cannot be read. */
    List<ResultRow> jobRows(String table, String jobId);

    /** The runners a launch needs: {@link JobSession}'s view, plus the launch itself. */
    interface Instances extends JobSession.Instances {
        /** Launches the job's instance; returns its id, or throws when EC2 refuses. */
        String runInstance(String amiId, String instanceType, String subnetId, String securityGroupId,
                           String instanceProfileName, String userData, String jobId, String deployment);
    }
}

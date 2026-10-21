package pl.wsztajerowski.baas.deployment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.TestDeployments;
import pl.wsztajerowski.baas.image.RunnerImageParameters;
import pl.wsztajerowski.baas.jobs.Ec2ProvisioningService.LiveRunner;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teardown's order, one test per rule CLAUDE.md states for it (review T2): nothing is deleted while a
 * runner is live, or before the extension is saved; the stack only after the bucket is empty; the
 * image only after the stack is gone.
 */
class TeardownSequenceTest {

    private static final String D = TestDeployments.DEFAULT;

    @TempDir
    Path dir;

    private final FakeTeardownAws aws = new FakeTeardownAws();

    private int teardown(String... extra) throws Exception {
        TestDeployments.write(dir, D);
        CommandLine.IFactory defaults = CommandLine.defaultFactory();
        CommandLine.IFactory factory = new CommandLine.IFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <K> K create(Class<K> cls) throws Exception {
                if (cls == TeardownCommand.class) {
                    var command = new TeardownCommand();
                    command.awsFor = config -> aws;
                    return (K) command;
                }
                return defaults.create(cls);
            }
        };
        String[] args = new String[3 + extra.length];
        System.arraycopy(new String[]{"admin", "deployment", "teardown"}, 0, args, 0, 3);
        System.arraycopy(extra, 0, args, 3, extra.length);
        return BaasApp.commandLine(new BaasApp(dir), factory)
            .setOut(new PrintWriter(new StringWriter()))
            .setErr(new PrintWriter(new StringWriter()))
            .execute(args);
    }

    @Test
    void aCompleteTeardownEmptiesTheBucketThenDeletesTheStackThenRetiresTheImage() throws Exception {
        assertThat(teardown("--yes")).isZero();

        assertThat(aws.calls).containsExactly(
            "liveRunners " + D,
            "stackParameters " + D,
            "emptyBucket " + D,
            "deleteStack " + D,
            "retireImage /" + D + "/runner/ami-id");
    }

    @Test
    void aLiveRunnerStopsTheTeardownBeforeAnythingIsAskedOrDeleted() throws Exception {
        aws.live = List.of(new LiveRunner("i-1", "running", "20261021T000000000Z-a3f9c21b"));

        assertThat(teardown("--yes")).isEqualTo(1);
        assertThat(aws.calls).containsExactly("liveRunners " + D);
    }

    @Test
    void withoutConfirmationNothingIsDeleted() throws Exception {
        // No --yes and no terminal: refused, as a closed stdin cannot answer.
        assertThat(teardown()).isEqualTo(1);
        assertThat(aws.calls).containsExactly("liveRunners " + D);
    }

    /** The stack holds the extension's only copy: a teardown that cannot save it deletes nothing. */
    @Test
    void anExtensionThatCannotBeReadDeletesNothing() throws Exception {
        aws.failParameters = new RuntimeException("Throttling");

        assertThat(teardown("--yes")).isEqualTo(1);
        assertThat(aws.calls).noneMatch(c -> c.startsWith("emptyBucket") || c.startsWith("deleteStack")
            || c.startsWith("retireImage"));
    }

    @Test
    void theExtensionIsSavedBeforeTheBucketIsEmptied() throws Exception {
        aws.parameters.put(RunnerImageParameters.EXTENSION_DATA, "name: ext\nphases: []");

        assertThat(teardown("--yes")).isZero();
        assertThat(dir.resolve("runner-image-extension." + D + ".yaml")).exists();
    }

    /** CloudFormation refuses a non-empty bucket: the stack stays whole rather than stuck in DELETE_FAILED. */
    @Test
    void aBucketThatCannotBeEmptiedLeavesTheStackAndTheImage() throws Exception {
        aws.failEmpty = new RuntimeException("AccessDenied");

        assertThat(teardown("--yes")).isEqualTo(1);
        assertThat(aws.calls).noneMatch(c -> c.startsWith("deleteStack") || c.startsWith("retireImage"));
    }

    /** A failed stack deletion still leaves a deployment with an image to run on. */
    @Test
    void aStackThatCannotBeDeletedKeepsItsImage() throws Exception {
        aws.failDelete = new RuntimeException("DELETE_FAILED");

        assertThat(teardown("--yes")).isNotZero();
        assertThat(aws.calls).noneMatch(c -> c.startsWith("retireImage"));
        assertThat(dir.resolve("deployments").resolve(D + ".yaml")).as("the file outlives a failed teardown").exists();
    }

    /** No step fails the teardown once the stack is gone: leftovers are named, not fatal. */
    @Test
    void imageLeftoversDoNotFailATeardownWhoseStackIsGone() throws Exception {
        aws.imageLeftovers = List.of("ami-0123");

        assertThat(teardown("--yes")).isZero();
        assertThat(dir.resolve("deployments").resolve(D + ".yaml")).doesNotExist();
    }
}

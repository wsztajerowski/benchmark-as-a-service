package pl.wsztajerowski.baas.deployment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.TestDeployments;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.image.RunnerImageParameters;
import pl.wsztajerowski.baas.jobs.Ec2ProvisioningService.LiveRunner;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teardown's order, one test per rule CLAUDE.md states for it (review T2): nothing runs without a
 * terminal unless --yes, and nothing is deleted while a runner is live, before an accepted export has
 * completed, or before the name is typed back (or --yes); the stack only after the bucket is empty; the image only after the
 * stack is gone. Teardown itself writes no file.
 */
class TeardownSequenceTest {

    private static final String D = TestDeployments.DEFAULT;

    @TempDir
    Path dir;

    private final FakeTeardownAws aws = new FakeTeardownAws();
    private final FakeExportAws exportAws = new FakeExportAws();
    private boolean interactive = true;
    /** Decline the export, then confirm: the common path. */
    private ScriptedAnswers answers = new ScriptedAnswers("n", D);

    TeardownSequenceTest() {
        // One log for both fakes, so a test can pin the export before the bucket is emptied.
        exportAws.calls = Collections.synchronizedList(aws.calls);
    }

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
                    command.exportAwsFor = config -> exportAws;
                    command.console = Console.withFlags(new PrintWriter(new StringWriter()), interactive, false);
                    command.answerReader = answers;
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
        assertThat(teardown()).isZero();

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

        assertThat(teardown()).isEqualTo(1);
        assertThat(aws.calls).containsExactly("liveRunners " + D);
        assertThat(answers.asked).isZero();
    }

    @Test
    void withoutConfirmationNothingIsDeleted() throws Exception {
        answers = new ScriptedAnswers("n", "baas-1234");

        assertThat(teardown()).isEqualTo(1);
        assertThat(aws.calls).containsExactly("liveRunners " + D, "stackParameters " + D);
    }

    @Test
    void withoutATerminalNothingIsAskedOrDeleted() throws Exception {
        interactive = false;

        assertThat(teardown()).isEqualTo(1);
        assertThat(aws.calls).isEmpty();
        assertThat(answers.asked).isZero();
        assertThat(dir.resolve("deployments").resolve(D + ".yaml")).exists();
    }

    /** --yes runs without a terminal and asks nothing, so it offers no export either. */
    @Test
    void yesSkipsTheOfferAndTheConfirmationAndExportsNothing() throws Exception {
        interactive = false;
        aws.parameters.put(RunnerImageParameters.EXTENSION_DATA, "name: ext\nphases: []");

        assertThat(teardown("--yes")).isZero();

        assertThat(answers.asked).isZero();
        assertThat(aws.calls).containsExactly(
            "liveRunners " + D,
            "stackParameters " + D,
            "emptyBucket " + D,
            "deleteStack " + D,
            "retireImage /" + D + "/runner/ami-id");
        assertThat(dir.resolve("deployments").resolve(D + ".yaml")).doesNotExist();
    }

    @Test
    void yesStillStopsAtALiveRunner() throws Exception {
        aws.live = List.of(new LiveRunner("i-1", "running", "20261021T000000000Z-a3f9c21b"));

        assertThat(teardown("--yes")).isEqualTo(1);
        assertThat(aws.calls).containsExactly("liveRunners " + D);
    }

    /** The stack parameters name what the notice warns about; unreadable, nothing is asked or deleted. */
    @Test
    void unreadableStackParametersDeleteNothing() throws Exception {
        aws.failParameters = new RuntimeException("Throttling");

        assertThat(teardown()).isEqualTo(1);
        assertThat(aws.calls).noneMatch(c -> c.startsWith("emptyBucket") || c.startsWith("deleteStack")
            || c.startsWith("retireImage"));
        assertThat(answers.asked).isZero();
    }

    @Test
    void anAcceptedExportCompletesBeforeTheBucketIsEmptied() throws Exception {
        Path bundle = dir.resolve("bundle");
        answers = new ScriptedAnswers("y", bundle.toString(), D);
        exportAws.table.add(FakeExportAws.job("2026-10-23T09:06:42.860Z#j"));

        assertThat(teardown()).isZero();

        assertThat(bundle.resolve("manifest.json")).exists();
        assertThat(aws.calls).containsSubsequence("liveRunners " + D, "account", "scan " + D + "-results",
            "emptyBucket " + D, "deleteStack " + D);
    }

    @Test
    void aFailedExportStopsTheTeardownBeforeTheConfirmation() throws Exception {
        answers = new ScriptedAnswers("y", dir.resolve("bundle").toString(), D);
        exportAws.failScan = new IllegalStateException("throttled");

        assertThat(teardown()).isEqualTo(1);

        assertThat(answers.asked).as("the name is never asked for").isEqualTo(2);
        assertThat(aws.calls).noneMatch(c -> c.startsWith("emptyBucket") || c.startsWith("deleteStack")
            || c.startsWith("retireImage"));
        assertThat(dir.resolve("deployments").resolve(D + ".yaml")).exists();
    }

    /** Teardown saves nothing itself: the extension leaves only through an export the user accepted. */
    @Test
    void aDeclinedExportWritesNoFileEvenWithAnExtensionDeployed() throws Exception {
        aws.parameters.put(RunnerImageParameters.EXTENSION_DATA, "name: ext\nphases: []");

        assertThat(teardown()).isZero();

        try (Stream<Path> files = Files.walk(dir)) {
            assertThat(files.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString()))
                .noneMatch(p -> p.contains("extension") || p.contains("baas-export"));
        }
    }

    /** CloudFormation refuses a non-empty bucket: the stack stays whole rather than stuck in DELETE_FAILED. */
    @Test
    void aBucketThatCannotBeEmptiedLeavesTheStackAndTheImage() throws Exception {
        aws.failEmpty = new RuntimeException("AccessDenied");

        assertThat(teardown()).isEqualTo(1);
        assertThat(aws.calls).noneMatch(c -> c.startsWith("deleteStack") || c.startsWith("retireImage"));
    }

    /** A failed stack deletion still leaves a deployment with an image to run on. */
    @Test
    void aStackThatCannotBeDeletedKeepsItsImage() throws Exception {
        aws.failDelete = new RuntimeException("DELETE_FAILED");

        assertThat(teardown()).isNotZero();
        assertThat(aws.calls).noneMatch(c -> c.startsWith("retireImage"));
        assertThat(dir.resolve("deployments").resolve(D + ".yaml")).as("the file outlives a failed teardown").exists();
    }

    /** No step fails the teardown once the stack is gone: leftovers are named, not fatal. */
    @Test
    void imageLeftoversDoNotFailATeardownWhoseStackIsGone() throws Exception {
        aws.imageLeftovers = List.of("ami-0123");

        assertThat(teardown()).isZero();
        assertThat(dir.resolve("deployments").resolve(D + ".yaml")).doesNotExist();
    }
}

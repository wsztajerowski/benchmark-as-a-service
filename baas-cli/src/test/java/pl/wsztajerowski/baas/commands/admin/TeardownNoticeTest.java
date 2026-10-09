package pl.wsztajerowski.baas.commands.admin;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TeardownNoticeTest {

    /** Said before the prompt: nothing survives a teardown, and the notice names what goes. */
    @Test
    void theNoticeSaysEverythingIsDeletedForGood() {
        assertThat(TeardownCommand.everythingGoesNotice("baas-123456789012"))
            .contains("bucket baas-123456789012", "results table baas-123456789012-results",
                "every job and measurement", "None of it can be recovered")
            .doesNotContain("retained");
    }

    @Test
    void thereIsNoOptionToKeepTheBucket() {
        assertThat(new picocli.CommandLine(new TeardownCommand()).getCommandSpec().optionsMap())
            .doesNotContainKey("--delete-bucket");
    }

    /** The image retired is the configured deployment's — the only one teardown can reach. */
    @Test
    void theImageRetiredIsTheTornDownDeployments() {
        var config = new pl.wsztajerowski.baas.config.BaasConfig();
        config.setPrefix("baas-123456789012");

        String deployment = new TeardownCommand().resolveDeployment(config);
        assertThat(TeardownCommand.pointerPath(deployment)).isEqualTo("/baas-123456789012/runner/ami-id");
        assertThat(TeardownCommand.recipeName(deployment)).isEqualTo("baas-123456789012-recipe-runner");
    }

    @Test
    void theImageNoticesSayWhatWasRetiredOrWhatIsLeft() {
        assertThat(TeardownCommand.imageRetiredNotice("baas-123456789012"))
            .contains("/baas-123456789012/runner/ami-id", "baas-123456789012-recipe-runner",
                "baas admin image build");
        assertThat(TeardownCommand.imageLeftoverNotice("baas-123456789012",
                java.util.List.of("Runner AMI ami-1 was not deregistered (x): aws ec2 deregister-image --image-id ami-1")))
            .contains("partly retired", "  - Runner AMI ami-1", "aws ec2 deregister-image --image-id ami-1");
    }

    /**
     * The in-flight refusal names each job by its instance's tag and the command that stops one.
     * It is built from the EC2 listing alone: teardown's deployer credentials hold no read of the
     * results table, and the message must not need one.
     */
    @Test
    void theInFlightRefusalNamesEachJobAndHowToStopIt() {
        String message = TeardownCommand.inFlightRefusal(java.util.List.of(
            new pl.wsztajerowski.baas.infra.Ec2ProvisioningService.LiveRunner(
                "i-0abc", "running", "20261003T000000000Z-a3f9c21b"),
            new pl.wsztajerowski.baas.infra.Ec2ProvisioningService.LiveRunner("i-0def", "pending", null)));

        assertThat(message)
            .contains("2 jobs are still in flight")
            .contains("20261003T000000000Z-a3f9c21b", "i-0abc", "running")
            .contains("(no job id tag)", "i-0def", "pending")
            .contains("baas jobs terminate <jobId>")
            .doesNotContain("terminate them manually");
    }

    // ─── the extension, saved before the stack goes (U40) ────────────────────────

    @org.junit.jupiter.api.io.TempDir
    Path dir;

    @Test
    void aDeployedExtensionIsSavedWithItsMarkerSoItCanBePushedBack() throws Exception {
        String extension = "name: extra\nphases:\n  - name: build\n    steps: []";

        var saved = TeardownCommand.saveExtension(extension, dir, "baas-123456789012-dev");

        assertThat(saved).contains(dir.resolve("runner-image-extension.baas-123456789012-dev.yaml"));
        var parsed = pl.wsztajerowski.baas.infra.RunnerImageExtension.parse(
            java.nio.file.Files.readString(saved.orElseThrow()));
        assertThat(parsed.content().strip()).isEqualTo(extension);
        assertThat(parsed.baseMarker())
            .contains(pl.wsztajerowski.baas.infra.RunnerImageExtension.hash(extension));
    }

    @Test
    void anDeploymentWithoutAnExtensionWritesNothing() throws Exception {
        assertThat(TeardownCommand.saveExtension("", dir, "p")).isEmpty();
        assertThat(TeardownCommand.saveExtension(null, dir, "p")).isEmpty();
        try (var files = java.nio.file.Files.list(dir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void theSavedNoticeSaysHowToPushItBack() {
        assertThat(TeardownCommand.extensionSavedNotice(Path.of("/h/.baas/runner-image-extension.p.yaml")))
            .contains("baas admin image build --extension /h/.baas/runner-image-extension.p.yaml");
    }

    // ─── U32: confirmation ───────────────────────────────────────────────────────

    private TeardownCommand teardown(boolean interactive, String... args) {
        var command = new TeardownCommand();
        new picocli.CommandLine(command).parseArgs(args);
        command.console = pl.wsztajerowski.baas.console.Console.withFlags(
            new java.io.PrintWriter(new java.io.StringWriter()), interactive, false);
        return command;
    }

    /** Reading a closed stdin used to crash with "No line found". */
    @Test
    void withoutATerminalOnlyYesProceeds() {
        var command = teardown(false);
        command.answerReader = () -> { throw new AssertionError("must not prompt"); };
        assertThat(command.confirmed("baas-123456789012")).isFalse();

        assertThat(teardown(false, "--yes").confirmed("baas-123456789012")).isTrue();
    }

    @Test
    void onATerminalOnlyTheExactStackNameProceeds() {
        var wrong = teardown(true);
        wrong.answerReader = () -> "baas-1234";
        assertThat(wrong.confirmed("baas-123456789012")).isFalse();

        var right = teardown(true);
        right.answerReader = () -> " baas-123456789012 ";
        assertThat(right.confirmed("baas-123456789012")).isTrue();
    }
}

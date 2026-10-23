package pl.wsztajerowski.baas.deployment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TeardownNoticeTest {

    /** Said before the prompt: nothing survives a teardown, and the notice names what goes. */
    @Test
    void theNoticeSaysEverythingIsDeletedForGood() {
        assertThat(TeardownCommand.everythingGoesNotice("baas-123456789012", false))
            .contains("bucket baas-123456789012", "results table baas-123456789012-results",
                "every job and measurement", "None of it can be recovered", "except from an export")
            .doesNotContain("retained", "extension");
    }

    @Test
    void theNoticeNamesADeployedExtension() {
        assertThat(TeardownCommand.everythingGoesNotice("baas-123456789012", true))
            .contains("runner-image extension, whose only copy is in the stack");
    }

    @Test
    void thereIsNoOptionToKeepTheBucket() {
        assertThat(new picocli.CommandLine(new TeardownCommand()).getCommandSpec().optionsMap())
            .doesNotContainKey("--delete-bucket")
            .doesNotContainKey("--yes");
    }

    /** The image retired is the configured deployment's — the only one teardown can reach. */
    @Test
    void theImageRetiredIsTheTornDownDeployments() {
        var config = new pl.wsztajerowski.baas.session.BaasConfig();
        config.setPrefix("baas-123456789012");

        String deployment = new TeardownCommand().resolveDeployment(config);
        assertThat(TeardownCommand.pointerPath(deployment)).isEqualTo("/baas-123456789012/runner/ami-id");
        assertThat(TeardownCommand.recipeName(deployment)).isEqualTo("baas-123456789012-recipe-runner");
    }

    @Test
    void theImageNoticesSayWhatWasRetiredOrWhatIsLeft() {
        assertThat(TeardownCommand.imageRetiredNotice("baas-123456789012"))
            .contains("/baas-123456789012/runner/ami-id", "baas-123456789012-recipe-runner",
                "baas --deployment baas-123456789012 admin image build");
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
        String message = TeardownCommand.inFlightRefusal("baas-123456789012", java.util.List.of(
            new pl.wsztajerowski.baas.jobs.Ec2ProvisioningService.LiveRunner(
                "i-0abc", "running", "20261003T000000000Z-a3f9c21b"),
            new pl.wsztajerowski.baas.jobs.Ec2ProvisioningService.LiveRunner("i-0def", "pending", null)));

        assertThat(message)
            .contains("2 jobs are still in flight")
            .contains("20261003T000000000Z-a3f9c21b", "i-0abc", "running")
            .contains("(no job id tag)", "i-0def", "pending")
            .contains("baas --deployment baas-123456789012 jobs terminate <jobId>")
            .doesNotContain("terminate them manually");
    }

    // ─── U32: confirmation ───────────────────────────────────────────────────────

    private TeardownCommand teardown(boolean interactive, String... args) {
        var command = new TeardownCommand();
        new picocli.CommandLine(command).parseArgs(args);
        command.console = pl.wsztajerowski.baas.console.Console.withFlags(
            new java.io.PrintWriter(new java.io.StringWriter()), interactive, false);
        return command;
    }

    /** A closed input is a no: a lost terminal can never approve a teardown. */
    @Test
    void aClosedInputIsNotAConfirmation() {
        var command = teardown(true);
        command.answerReader = () -> null;
        assertThat(command.confirmed("baas-123456789012")).isFalse();
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

package pl.wsztajerowski.baas.commands.admin;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TeardownNoticeTest {

    /** The hint must name a way to read the table that still exists after the override was removed. */
    @Test
    void theRetainedTableHintNamesNoRemovedOption() {
        String notice = TeardownCommand.retainedTableNotice(
            "baas-123456789012-results", Path.of("/home/me/.baas/config.yaml"));

        assertThat(notice)
            .doesNotContain("--results-table")
            .contains("baas results --all-projects")
            .contains("/home/me/.baas/config.yaml")
            .contains("--config-path")
            .contains("aws dynamodb delete-table --table-name baas-123456789012-results");
    }

    /** --stack-name names the installation whose image is retired, not this machine's. */
    @Test
    void theImageRetiredIsTheTornDownInstallations() {
        var config = new pl.wsztajerowski.baas.config.BaasConfig();
        config.setPrefix("baas-123456789012");

        var named = new TeardownCommand();
        new picocli.CommandLine(named).parseArgs("--stack-name", "baas-123456789012-dev");
        String dev = named.resolveInstallation(config);
        assertThat(TeardownCommand.pointerPath(dev)).isEqualTo("/baas-123456789012-dev/runner/ami-id");
        assertThat(TeardownCommand.recipeName(dev)).isEqualTo("baas-123456789012-dev-recipe-runner");

        var configured = new TeardownCommand();
        new picocli.CommandLine(configured).parseArgs();
        assertThat(TeardownCommand.pointerPath(configured.resolveInstallation(config)))
            .isEqualTo("/baas-123456789012/runner/ami-id");
    }

    @Test
    void theImageNoticesSayWhatWasRetiredOrWhatIsLeft() {
        assertThat(TeardownCommand.imageRetiredNotice("baas-123456789012"))
            .contains("/baas-123456789012/runner/ami-id", "baas-123456789012-recipe-runner",
                "baas admin build-image");
        assertThat(TeardownCommand.imageLeftoverNotice("baas-123456789012",
                java.util.List.of("Runner AMI ami-1 was not deregistered (x): aws ec2 deregister-image --image-id ami-1")))
            .contains("partly retired", "  - Runner AMI ami-1", "aws ec2 deregister-image --image-id ami-1");
    }

    /**
     * The in-flight refusal names each run by its instance's tag and the command that stops one.
     * It is built from the EC2 listing alone: teardown's deployer credentials hold no read of the
     * results table, and the message must not need one.
     */
    @Test
    void theInFlightRefusalNamesEachRunAndHowToStopIt() {
        String message = TeardownCommand.inFlightRefusal(java.util.List.of(
            new pl.wsztajerowski.baas.infra.Ec2ProvisioningService.LiveRunner(
                "i-0abc", "running", "20261003T000000000Z-a3f9c21b"),
            new pl.wsztajerowski.baas.infra.Ec2ProvisioningService.LiveRunner("i-0def", "pending", null)));

        assertThat(message)
            .contains("2 runs are still in flight")
            .contains("20261003T000000000Z-a3f9c21b", "i-0abc", "running")
            .contains("(no run id tag)", "i-0def", "pending")
            .contains("baas runs terminate <runId>")
            .doesNotContain("terminate them manually");
    }
}

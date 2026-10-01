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
}

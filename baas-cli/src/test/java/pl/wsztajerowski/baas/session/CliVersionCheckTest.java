package pl.wsztajerowski.baas.session;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliVersionCheckTest {

    private final List<String> warnings = new ArrayList<>();

    private CliVersionCheck.Outcome check(String running, String recorded, CliVersionCheck.Role role) {
        return new CliVersionCheck(running, "o/r", warnings::add)
            .check("dep", role, () -> Optional.ofNullable(recorded));
    }

    @Test
    void anOlderCliIsRefusedPatchLevelIncluded() {
        assertThatThrownBy(() -> check("8.1.0", "8.1.1", CliVersionCheck.Role.OPERATOR))
            .isInstanceOf(CliVersionCheck.Refused.class)
            .hasMessageContaining("Deployment dep was set up with baas 8.1.1; this is baas 8.1.0, which is older")
            .hasMessageContaining(CliVersionCheck.installCommand("o/r"));
    }

    @Test
    void setupIsRefusedLikeAnyCommand() {
        assertThatThrownBy(() -> check("8.1.0", "8.2.0", CliVersionCheck.Role.SETUP))
            .isInstanceOf(CliVersionCheck.Refused.class);
    }

    @Test
    void versionsCompareNumerically() {
        assertThat(check("8.10.0", "8.9.0", CliVersionCheck.Role.OPERATOR)).isEqualTo(CliVersionCheck.Outcome.AHEAD);
    }

    @Test
    void theSameReleaseProceedsSilently() {
        assertThat(check("8.2.0", "8.2.0", CliVersionCheck.Role.ADMIN)).isEqualTo(CliVersionCheck.Outcome.CURRENT);
        assertThat(warnings).isEmpty();
    }

    @Test
    void nothingRecordedProceedsSilently() {
        assertThat(check("8.2.0", null, CliVersionCheck.Role.OPERATOR)).isEqualTo(CliVersionCheck.Outcome.NONE_RECORDED);
        assertThat(check("8.2.0", "", CliVersionCheck.Role.OPERATOR)).isEqualTo(CliVersionCheck.Outcome.NONE_RECORDED);
        assertThat(warnings).isEmpty();
    }

    @Test
    void aNewerAdminIsToldToRunSetup() {
        check("8.2.0", "8.1.0", CliVersionCheck.Role.ADMIN);
        assertThat(warnings).singleElement().asString()
            .contains("Record it, so older CLIs are refused: baas --deployment dep admin deployment setup");
    }

    @Test
    void aNewerOperatorIsToldToAskTheAdmin() {
        check("8.2.0", "8.1.0", CliVersionCheck.Role.OPERATOR);
        assertThat(warnings).singleElement().asString().contains("Ask its admin to record the newer release");
    }

    @Test
    void setupSaysNothingWhenAhead() {
        assertThat(check("8.2.0", "8.1.0", CliVersionCheck.Role.SETUP)).isEqualTo(CliVersionCheck.Outcome.AHEAD);
        assertThat(warnings).isEmpty();
    }

    @Test
    void anUnreleasedBuildNeverAsks() {
        var checker = new CliVersionCheck(BaasVersion.PLACEHOLDER, "o/r", warnings::add);
        assertThat(checker.check("dep", CliVersionCheck.Role.OPERATOR, () -> {
            throw new AssertionError("an unreleased build must not read the recorded version");
        })).isEqualTo(CliVersionCheck.Outcome.UNRELEASED);
        assertThat(warnings).singleElement().asString().startsWith("Unreleased build (baas 0.0.0-semantically-released)");
        assertThat(checker.recordable()).isEmpty();
    }

    @Test
    void aPreReleaseCountsAsUnreleased() {
        assertThat(check("8.0.0-rc.1", "9.0.0", CliVersionCheck.Role.OPERATOR)).isEqualTo(CliVersionCheck.Outcome.UNRELEASED);
    }

    @Test
    void onlyAReleasedVersionIsRecordable() {
        assertThat(new CliVersionCheck("8.2.0", "o/r", warnings::add).recordable()).contains("8.2.0");
    }
}

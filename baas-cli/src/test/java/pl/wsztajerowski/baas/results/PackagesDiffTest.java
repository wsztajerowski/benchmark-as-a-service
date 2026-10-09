package pl.wsztajerowski.baas.results;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PackagesDiffTest {

    private static final String BEFORE = """
        glibc-2.34-117.amzn2023.0.1.x86_64
        openssl-libs-3.0.8-1.amzn2023.0.14.x86_64
        gpg-pubkey-d832c631-6515c85e
        kernel6.1-6.1.109-118.189.amzn2023.x86_64
        removed-pkg-1.0-1.amzn2023.noarch
        """;

    private static final String AFTER = """
        glibc-2.34-117.amzn2023.0.1.x86_64
        openssl-libs-3.0.8-1.amzn2023.0.16.x86_64
        gpg-pubkey-d832c631-6515c85e
        kernel6.1-6.1.109-118.189.amzn2023.x86_64
        kernel6.1-6.1.112-122.189.amzn2023.x86_64
        zstd-libs-1.5.5-1.amzn2023.0.2.x86_64
        """;

    @Test
    void aVersionChangeIsReportedByName() {
        var diff = PackagesDiff.of(BEFORE, AFTER);

        assertThat(diff.changed()).extracting(PackagesDiff.Change::name).contains("openssl-libs");
        assertThat(diff.changed()).filteredOn(change -> change.name().equals("openssl-libs")).singleElement()
            .satisfies(change -> {
                assertThat(change.before()).isEqualTo("3.0.8-1.amzn2023.0.14");
                assertThat(change.after()).isEqualTo("3.0.8-1.amzn2023.0.16");
            });
    }

    @Test
    void anInstallOnlyPackageKeepsEveryVersion() {
        assertThat(PackagesDiff.of(BEFORE, AFTER).changed())
            .filteredOn(change -> change.name().equals("kernel6.1")).singleElement()
            .satisfies(change -> assertThat(change.after()).contains("6.1.109", "6.1.112"));
    }

    @Test
    void addedAndRemovedPackagesAreListed() {
        var diff = PackagesDiff.of(BEFORE, AFTER);

        assertThat(diff.added()).singleElement().asString().startsWith("zstd-libs ");
        assertThat(diff.removed()).singleElement().asString().startsWith("removed-pkg ");
    }

    @Test
    void aKeyWithoutAnArchitectureParses() {
        assertThat(PackagesDiff.parse("gpg-pubkey-d832c631-6515c85e")).containsKey("gpg-pubkey");
    }

    @Test
    void equalListsDifferInNothing() {
        assertThat(PackagesDiff.of(BEFORE, BEFORE).isEmpty()).isTrue();
    }
}

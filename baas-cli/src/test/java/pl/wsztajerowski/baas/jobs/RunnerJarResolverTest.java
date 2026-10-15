package pl.wsztajerowski.baas.jobs;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import pl.wsztajerowski.baas.aws.Checksums;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunnerJarResolverTest {

    private static final byte[] PAYLOAD = "pretend-jar".getBytes(StandardCharsets.UTF_8);

    @Test
    void aMismatchIsAHardFailure() {
        assertThatThrownBy(() -> RunnerJarResolver.verify(PAYLOAD, "0".repeat(64)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("checksum")
            .hasMessageContaining("Nothing was uploaded");
    }

    @Test
    void aBlankChecksumIsAFailureNotASkip() {
        assertThatThrownBy(() -> RunnerJarResolver.verify(PAYLOAD, "  "))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("checksum");
    }

    @Test
    void aMatchingChecksumPasses() {
        assertThatCode(() -> RunnerJarResolver.verify(PAYLOAD, Checksums.sha256Hex(PAYLOAD)))
            .doesNotThrowAnyException();
    }

    /** sha256sum's own output is lowercase with a trailing newline; other tools shout. */
    @Test
    void theChecksumComparisonIgnoresCaseAndSurroundingWhitespace() {
        String upper = Checksums.sha256Hex(PAYLOAD).toUpperCase();

        assertThatCode(() -> RunnerJarResolver.verify(PAYLOAD, "  " + upper + "  \n"))
            .doesNotThrowAnyException();
    }

    /** A `sha256sum <file>` line is "<hex>  <name>"; only the first field is the digest. */
    @Test
    void theChecksumComparisonAcceptsASha256sumLine() {
        String line = Checksums.sha256Hex(PAYLOAD) + "  benchmark-runner.jar\n";

        assertThatCode(() -> RunnerJarResolver.verify(PAYLOAD, line)).doesNotThrowAnyException();
    }

    @Test
    void sha256HexIsLowercaseAndFixedWidth() {
        assertThat(Checksums.sha256Hex(PAYLOAD))
            .hasSize(64)
            .matches("[0-9a-f]{64}");
    }

    @Test
    void theAssetUrlNamesTheConfiguredRepositoryAndVersion() {
        assertThat(RunnerJarResolver.assetUrl("acme/baas", "1.4.2", "benchmark-runner.jar"))
            .isEqualTo("https://github.com/acme/baas/releases/download/v1.4.2/benchmark-runner.jar");
    }

    @Test
    void aBlankSourceRepositoryIsRejectedRatherThanDefaulted() {
        assertThatThrownBy(() -> RunnerJarResolver.assetUrl("  ", "1.4.2", "benchmark-runner.jar"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("repository");
    }

    /**
     * The repository is fixed when the CLI is built, so the rejection names the build property and
     * no configuration key: there is none left to set.
     */
    @Test
    void theRejectionNamesTheBuildPropertyNotAConfigurationKey() {
        assertThatThrownBy(() -> RunnerJarResolver.assetUrl(null, "1.4.2", "benchmark-runner.jar"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("-Dbaas.sourceRepo")
            .hasMessageNotContaining("config.yaml")
            .hasMessageNotContaining("runner.sourceRepo");
    }

    @Test
    void theBuildNamesTheUpstreamRepositoryByDefault() {
        assertThat(pl.wsztajerowski.baas.cli.BaasVersion.sourceRepo())
            .isEqualTo("wsztajerowski/benchmark-as-a-service");
    }

    // ─── Seeding: the put is conditional, and a refusal means the slot is seeded ──
    //
    // The 412 refusal is exercised against LocalStack in RunnerJarResolverIT. A 409 is S3's answer
    // when two conditional puts of one key are in flight at once, which LocalStack cannot be made
    // to produce on demand, so it and the rethrow are pinned here against a stub.

    private final List<PutObjectRequest> puts = new ArrayList<>();

    @Test
    void theSeedAsksS3ToRefuseAnExistingObject() {
        RunnerJarResolver.seed(answering(null), "b", "k", new byte[]{1});

        assertThat(puts).singleElement().extracting(PutObjectRequest::ifNoneMatch).isEqualTo("*");
    }

    @Test
    void aConcurrentConditionalPutConflictMeansAnotherLaunchIsSeeding() {
        assertThat(RunnerJarResolver.seed(answering(409), "b", "k", new byte[]{1})).isFalse();
    }

    @Test
    void aPreconditionFailureMeansTheSlotIsAlreadySeeded() {
        assertThat(RunnerJarResolver.seed(answering(412), "b", "k", new byte[]{1})).isFalse();
    }

    @Test
    void anyOtherRefusalStillFailsTheLaunch() {
        assertThatThrownBy(() -> RunnerJarResolver.seed(answering(403), "b", "k", new byte[]{1}))
            .isInstanceOf(S3Exception.class);
    }

    /** An S3 client whose put succeeds when {@code status} is null and otherwise fails with it. */
    private S3Client answering(Integer status) {
        return new S3Client() {
            @Override
            public PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
                puts.add(request);
                if (status != null) {
                    throw (S3Exception) S3Exception.builder().statusCode(status).build();
                }
                return PutObjectResponse.builder().build();
            }

            @Override
            public String serviceName() {
                return "s3";
            }

            @Override
            public void close() {
            }
        };
    }
}

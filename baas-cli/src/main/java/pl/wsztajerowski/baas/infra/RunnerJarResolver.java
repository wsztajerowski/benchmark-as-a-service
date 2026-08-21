package pl.wsztajerowski.baas.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.model.RunLayout;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

/**
 * Seeds and resolves the version-pinned runner JAR at {@code releases/<version>/}.
 *
 * <p>The download moved from the EC2 instance to the laptop, which is what makes verification
 * possible at all — CLAUDE.md carried "runner JAR downloaded without checksum verification" as an
 * accepted risk precisely because a throwaway instance mid-boot had nothing to verify against.
 * Moving it also deletes the instance's {@code api.github.com} egress, and makes the source
 * repository configuration rather than a string baked into a shell script (finding A7).
 */
public final class RunnerJarResolver {

    private static final Logger logger = LoggerFactory.getLogger(RunnerJarResolver.class);

    private static final String JAR_ASSET = RunLayout.RUNNER_JAR_NAME;
    private static final String CHECKSUM_ASSET = RunLayout.RUNNER_JAR_NAME + ".sha256";
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private RunnerJarResolver() {
    }

    /**
     * Upload-if-absent, never overwrite. A present object is used as-is, so a corrupted one does
     * not self-repair — the fix is deleting the key so the next run re-seeds it. Overwriting
     * instead would make {@code releases/<version>/} mutable, which is the one property the whole
     * pinning argument rests on.
     */
    public static String resolve(S3Client s3, String bucket, String version, String sourceRepo) {
        String key = RunLayout.runnerJarKey(version);
        if (exists(s3, bucket, key)) {
            logger.debug("Runner JAR already seeded at s3://{}/{}", bucket, key);
            return key;
        }
        logger.info("Seeding runner JAR {} from {} release v{}...", key, sourceRepo, version);
        byte[] jar = fetchAsset(sourceRepo, version, JAR_ASSET);
        String publishedSha = new String(
            fetchAsset(sourceRepo, version, CHECKSUM_ASSET), StandardCharsets.UTF_8);
        verify(jar, publishedSha);
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
            RequestBody.fromBytes(jar));
        return key;
    }

    static boolean exists(S3Client s3, String bucket, String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    static String assetUrl(String sourceRepo, String version, String assetName) {
        if (sourceRepo == null || sourceRepo.isBlank()) {
            throw new IllegalArgumentException(
                "No runner source repository configured, so there is no release to pin to. "
                    + "Set aws.runnerSourceRepo in ~/.baas/config.yaml.");
        }
        return "https://github.com/" + sourceRepo.strip() + "/releases/download/v" + version
            + "/" + assetName;
    }

    /**
     * Fails naming the repository, the version and the asset. A release that exists but carries no
     * checksum asset is as much a failure as a missing release — verification is not optional.
     */
    static byte[] fetchAsset(String sourceRepo, String version, String assetName) {
        String url = assetUrl(sourceRepo, version, assetName);
        try (HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(TIMEOUT)
            .build()) {
            HttpResponse<byte[]> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                    "Could not fetch " + assetName + " for version " + version + " from "
                        + sourceRepo + " (HTTP " + response.statusCode() + " at " + url
                        + "). Nothing was uploaded.");
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException(
                "Could not fetch " + assetName + " for version " + version + " from " + sourceRepo
                    + " (" + url + "): " + e.getMessage() + ". Nothing was uploaded.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted fetching " + url, e);
        }
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * An absent checksum is a failure, not a skip. Treating it as one would let the single moment
     * verification became possible pass without taking it.
     */
    public static void verify(byte[] jar, String publishedSha256) {
        if (publishedSha256 == null || publishedSha256.isBlank()) {
            throw new IllegalStateException(
                "No published checksum for the runner JAR — refusing to upload it unverified. "
                    + "Nothing was uploaded.");
        }
        // `sha256sum <file>` prints "<hex>  <name>"; only the first field is the digest.
        String expected = publishedSha256.strip().split("\\s+", 2)[0].toLowerCase();
        String actual = sha256Hex(jar);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                "Runner JAR checksum mismatch: expected " + expected + ", got " + actual
                    + ". Nothing was uploaded.");
        }
    }
}

package pl.wsztajerowski.baas.jobs;

import pl.wsztajerowski.baas.aws.Checksums;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.model.JobLayout;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

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

    private static final String JAR_ASSET = JobLayout.RUNNER_JAR_NAME;
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private RunnerJarResolver() {
    }

    /**
     * Upload-if-absent, never overwrite, and on every launch a check that the object is this CLI's
     * own runner: its S3 SHA-256 checksum must equal {@code runnerSha256}, the digest the build
     * wrote into the CLI. The operator role may write the bucket, so a slot seeded correctly once
     * could later hold anything; the check makes a replaced runner a refused launch rather than one
     * every later job of the version executes (review X1). It needs no network — GitHub is still
     * reached only to seed — and S3 computed the checksum from the bytes actually written, so the
     * writer cannot choose it.
     *
     * <p>A present object is never repaired: the fix is deleting the key so the next job re-seeds
     * it. Overwriting instead would make {@code releases/<version>/} mutable, which is the one
     * property the whole pinning argument rests on. What enforces "never overwrite" is the
     * conditional put in {@link #seed}, so two first jobs of a version write it once.
     */
    public static String resolve(S3Client s3, String bucket, String version, String sourceRepo, String runnerSha256) {
        if (runnerSha256 == null || runnerSha256.isBlank()) {
            throw new IllegalStateException(
                "This build of the CLI carries no runner digest, so it cannot tell whether the runner in "
                    + "the bucket is its own. Run against a local build: baas run --runner-jar <path> ... "
                    + "Nothing was launched.");
        }
        String key = JobLayout.runnerJarKey(version);
        Stored stored = head(s3, bucket, key);
        if (stored == null) {
            logger.info("Seeding runner JAR {} from {} release v{}...", key, sourceRepo, version);
            byte[] jar = fetchAsset(sourceRepo, version, JAR_ASSET);
            verify(jar, runnerSha256);
            seed(s3, bucket, key, jar);
            // Read back: it is this launch's object, or a concurrent launch's (412/409), and the
            // second must pass the same check.
            stored = head(s3, bucket, key);
        }
        requireOwnRunner(stored, runnerSha256, bucket, key);
        return key;
    }

    /** What a head of the slot found: {@code sha256} is null when the object carries no SHA-256 checksum. */
    record Stored(String sha256) {
    }

    static void requireOwnRunner(Stored stored, String runnerSha256, String bucket, String key) {
        String expected = runnerSha256.strip().toLowerCase();
        if (stored != null && expected.equals(stored.sha256())) {
            return;
        }
        String found = stored == null ? "no object at all"
            : stored.sha256() == null ? "an object with no SHA-256 checksum, seeded before this check existed or written by hand"
            : "an object whose SHA-256 is " + stored.sha256();
        throw new IllegalStateException(
            "s3://" + bucket + "/" + key + " is not the runner this CLI was released with: expected SHA-256 "
                + expected + ", found " + found + ". Nothing was launched.\n"
                + "  Delete it with deployer credentials, and the next job re-seeds it from the release:\n"
                + "  aws s3 rm s3://" + bucket + "/" + key);
    }

    /**
     * Writes the slot only if S3 holds nothing at the key, asking S3 to store a SHA-256 checksum of
     * it — what every later launch compares. S3 answers 412 when the object appeared after the head,
     * and 409 when another conditional put of it is still in flight; either way another launch is
     * seeding the same verified release asset, so the slot is treated as seeded. Should that other
     * upload fail after a 409, the read-back finds no object and this launch is refused, and the
     * next job re-seeds.
     *
     * @return whether this call wrote the object
     */
    static boolean seed(S3Client s3, String bucket, String key, byte[] jar) {
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).ifNoneMatch("*")
                    .checksumAlgorithm(ChecksumAlgorithm.SHA256).build(),
                RequestBody.fromBytes(jar));
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 412 || e.statusCode() == 409) {
                logger.info("Runner JAR s3://{}/{} was seeded by another launch (HTTP {}); using it",
                    bucket, key, e.statusCode());
                return false;
            }
            throw e;
        }
    }

    /**
     * The slot's stored SHA-256 as lowercase hex, or null for no object. A multipart object's
     * composite checksum ({@code <base64>-<parts>}) is no file digest, so it reads as none.
     */
    static Stored head(S3Client s3, String bucket, String key) {
        try {
            var response = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key)
                .checksumMode(ChecksumMode.ENABLED).build());
            String checksum = response.checksumSHA256();
            if (checksum == null || checksum.contains("-")) {
                return new Stored(null);
            }
            return new Stored(HexFormat.of().formatHex(Base64.getDecoder().decode(checksum)));
        } catch (NoSuchKeyException e) {
            return null;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    static String assetUrl(String sourceRepo, String version, String assetName) {
        if (sourceRepo == null || sourceRepo.isBlank()) {
            throw new IllegalArgumentException(
                "This build names no runner source repository, so there is no release to pin to. "
                    + "Build the CLI with -Dbaas.sourceRepo=<owner>/<repository>.");
        }
        return "https://github.com/" + sourceRepo.strip() + "/releases/download/v" + version
            + "/" + assetName;
    }

    /**
     * Fails naming the repository, the version and the asset.
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


    /**
     * Checks a downloaded runner against the digest this CLI was built with — not against the
     * release's own {@code .sha256} asset, which anyone able to replace the JAR could replace beside
     * it (review X8). An absent checksum is a failure, not a skip.
     */
    public static void verify(byte[] jar, String expectedSha256) {
        if (expectedSha256 == null || expectedSha256.isBlank()) {
            throw new IllegalStateException(
                "No checksum to verify the runner JAR against — refusing to upload it unverified. "
                    + "Nothing was uploaded.");
        }
        // `sha256sum <file>` prints "<hex>  <name>"; only the first field is the digest.
        String expected = expectedSha256.strip().split("\\s+", 2)[0].toLowerCase();
        String actual = Checksums.sha256Hex(jar);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                "Runner JAR checksum mismatch: expected " + expected + ", got " + actual
                    + ". Nothing was uploaded.");
        }
    }
}

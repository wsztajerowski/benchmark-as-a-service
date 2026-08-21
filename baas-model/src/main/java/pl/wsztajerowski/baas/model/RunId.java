package pl.wsztajerowski.baas.model;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The only place a run identifier is minted.
 *
 * <p>Time-ordered so an S3 listing reads chronologically — S3 orders lexicographically and offers
 * no sort-by-date for prefixes — and random-suffixed so two runs starting in the same millisecond
 * cannot collide. Nothing parses this value, so the format is a readability convention rather than
 * a contract; the one hard rule is the alphabet, because the id is the last {@code #}-separated
 * field of every sort key and the partition key of {@code requestId-index}.
 *
 * <p>Millisecond precision is forced rather than chosen: {@code StoredMeasurement} truncates
 * {@code createdAt} to milliseconds, so a second-precision id would be a lossy view of the value
 * it claims to name.
 */
public final class RunId {

    /** {@code 20260820T174432812Z} (19) + {@code -} (1) + 8 hex (8). */
    public static final int LENGTH = 28;

    private static final DateTimeFormatter INSTANT =
        DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);

    private static final SecureRandom RANDOM = new SecureRandom();

    private RunId() {
    }

    public static String generate() {
        return generate(Instant.now());
    }

    public static String generate(Instant instant) {
        byte[] entropy = new byte[4];
        RANDOM.nextBytes(entropy);
        StringBuilder hex = new StringBuilder(8);
        for (byte b : entropy) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return INSTANT.format(instant) + "-" + hex;
    }
}

package pl.wsztajerowski.baas.infra;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** The digest the release publishes beside each asset, and the one an image extension is labelled by. */
public final class Checksums {

    private Checksums() {
    }

    /** Lowercase, fixed-width hex — the form {@code sha256sum} prints. */
    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

package io.github.rkaushikdev.carrierworks.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 digests as lower case hex, which is how the bench addresses images, files and keys.
 */
public final class Digests {

    private Digests() {}

    /** The SHA-256 of some bytes, as 64 lower case hex digits. */
    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("every Java runtime carries SHA-256", missing);
        }
    }

    /** The SHA-256 of a string's UTF-8 bytes, as 64 lower case hex digits. */
    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}

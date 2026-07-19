package net.explorersfriend.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers for JAR identity and content-addressed caches. Stateless, thread-safe. */
public final class Hashes {

    private static final int BUFFER_SIZE = 64 * 1024;

    private Hashes() {
    }

    public static String sha256Hex(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return sha256Hex(in);
        }
    }

    public static String sha256Hex(InputStream in) throws IOException {
        MessageDigest digest = newSha256();
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(newSha256().digest(data));
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-256 support", e);
        }
    }
}

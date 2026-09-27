package com.example.gym.face;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Stores face JPEGs on the local faces volume ({@code app.faces.dir}, a Docker volume on the VPS).
 * Keys are relative paths: {@code {tenant}/{member}/{version}.jpg} for member faces and
 * {@code uploads/{tenant}/{uuid}.jpg} for images uploaded by the gateway.
 */
@Service
public class FaceStorageService {

    private static final Logger log = LoggerFactory.getLogger(FaceStorageService.class);

    private final Path root;

    public FaceStorageService(@Value("${app.faces.dir:/var/lib/gym/faces}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            log.error("Face photo directory {} cannot be created ({}); photo uploads will fail. "
                    + "Set APP_FACES_DIR to a writable, persistent path.", root, ex.toString());
            return;
        }
        if (!Files.isWritable(root)) {
            log.error("Face photo directory {} is not writable; photo uploads will fail.", root);
        }
    }

    public static String memberKey(Long tenantId, Long memberId, int version) {
        return tenantId + "/" + memberId + "/" + version + ".jpg";
    }

    public static String uploadKey(Long tenantId) {
        return "uploads/" + tenantId + "/" + UUID.randomUUID() + ".jpg";
    }

    public void write(String key, byte[] bytes) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".face", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not store face image", ex);
        }
    }

    public byte[] read(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read face image", ex);
        }
    }

    public boolean exists(String key) {
        return key != null && Files.exists(resolve(key));
    }

    public void deleteQuietly(String key) {
        if (key == null) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ignored) {
            // Orphaned files are harmless; the next write uses a new versioned key.
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("Invalid face key");
        }
        return p;
    }
}

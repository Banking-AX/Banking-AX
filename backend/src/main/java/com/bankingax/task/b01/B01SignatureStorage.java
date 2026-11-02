package com.bankingax.task.b01;

import com.bankingax.core.api.DomainException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
final class B01SignatureStorage {
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private final Path root;
    private final long maxBytes;

    B01SignatureStorage(@Value("${app.b01.signature-storage-root:./var/b01-signatures}") String root,
                        @Value("${app.b01.signature-max-bytes:1048576}") long maxBytes) {
        this.root = Path.of(root).toAbsolutePath().normalize(); this.maxBytes = maxBytes;
    }

    StoredSignature store(MultipartFile file) {
        try {
            byte[] bytes = file == null ? new byte[0] : file.getBytes();
            if (bytes.length == 0 || bytes.length > maxBytes || !"image/png".equalsIgnoreCase(file.getContentType())
                    || !startsWith(bytes, PNG)) throw invalid();
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() < 20 || image.getHeight() < 20 || !hasInk(image)) throw invalid();
            Files.createDirectories(root);
            restrict(root, "rwx------");
            String key = UUID.randomUUID() + ".png";
            Path target = root.resolve(key).normalize();
            if (!target.startsWith(root)) throw invalid();
            Path temporary = Files.createTempFile(root, "signature-", ".tmp");
            Files.write(temporary, bytes);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            restrict(target, "rw-------");
            String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new StoredSignature(key, checksum);
        } catch (DomainException exception) { throw exception; }
        catch (Exception exception) { throw new DomainException("SIGNATURE_STORAGE_FAILED", "The mock signature could not be stored.", true); }
    }

    void deleteQuietly(String key) {
        if (key == null) return;
        try { Path path = root.resolve(key).normalize(); if (path.startsWith(root)) Files.deleteIfExists(path); }
        catch (Exception ignored) { }
    }

    private static boolean hasInk(BufferedImage image) {
        long ink = 0, pixels = (long) image.getWidth() * image.getHeight();
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int argb = image.getRGB(x, y), alpha = argb >>> 24;
            int r = argb >> 16 & 255, g = argb >> 8 & 255, b = argb & 255;
            if (alpha > 20 && (r < 235 || g < 235 || b < 235)) ink++;
        }
        return ink >= Math.max(10, pixels / 1000);
    }
    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }
    private static void restrict(Path path, String permissions) {
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions)); }
        catch (UnsupportedOperationException ignored) { }
        catch (Exception exception) { throw new DomainException("SIGNATURE_STORAGE_FAILED", "Private signature storage could not be secured.", true); }
    }
    private static DomainException invalid() { return new DomainException("INVALID_SIGNATURE_FILE", "A non-empty PNG signature image is required."); }
    record StoredSignature(String storageKey, String sha256) { }
}

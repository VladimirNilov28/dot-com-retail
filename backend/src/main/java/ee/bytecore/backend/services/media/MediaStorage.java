package ee.bytecore.backend.services.media;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Durable local-directory byte storage; keys are server generated, never filenames. */
@Component
public class MediaStorage {

    /** Generated keys: random UUID plus a sniffed-format extension. */
    public static final Pattern KEY =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png)");

    private final Path root;

    public MediaStorage(@Value("${media.storage-dir:./media-data}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    public void store(String key, byte[] bytes) {
        Path target = resolve(key);
        try {
            Files.createDirectories(root);
            Path temporary = Files.createTempFile(root, ".upload-", ".tmp");
            try {
                Files.write(temporary, bytes);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store media", e);
        }
    }

    public Optional<byte[]> read(String key) {
        if (!KEY.matcher(key).matches()) return Optional.empty();
        try {
            return Optional.of(Files.readAllBytes(resolve(key)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read media", e);
        }
    }

    /** Best effort: a failure leaves an unreferenced file, never a dangling database row. */
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException | RuntimeException e) {
            // intentionally swallowed; see method contract
        }
    }

    private Path resolve(String key) {
        if (!KEY.matcher(key).matches()) throw new IllegalArgumentException("Invalid media key");
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Invalid media key");
        return path;
    }
}

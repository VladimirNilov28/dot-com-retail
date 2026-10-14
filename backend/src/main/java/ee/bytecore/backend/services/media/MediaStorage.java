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

/**
 * Durable local-directory byte storage; keys are server generated, never filenames. The uploaded
 * original lives at {@code <root>/<key>}; resized delivery variants at
 * {@code <root>/variants/<size>/<key>}, where {@code size} comes from the {@link ImageSize} enum.
 */
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
        write(resolve(key), bytes);
    }

    /** Atomically replaces the variant file, so concurrent readers never observe a partial image. */
    public void storeVariant(String key, ImageSize size, byte[] bytes) {
        write(resolve(key, size), bytes);
    }

    private void write(Path target, byte[] bytes) {
        try {
            Path directory = target.getParent();
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, ".upload-", ".tmp");
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
        if (key == null || !KEY.matcher(key).matches()) return Optional.empty();
        return readPath(resolve(key));
    }

    public Optional<byte[]> readVariant(String key, ImageSize size) {
        if (key == null || size == null || !KEY.matcher(key).matches()) return Optional.empty();
        return readPath(resolve(key, size));
    }

    private Optional<byte[]> readPath(Path path) {
        try {
            return Optional.of(Files.readAllBytes(path));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read media", e);
        }
    }

    /**
     * Removes the original and every variant. Best effort: a failure leaves an unreferenced file,
     * never a dangling database row.
     */
    public void delete(String key) {
        deleteQuietly(() -> resolve(key));
        for (ImageSize size : ImageSize.values()) deleteQuietly(() -> resolve(key, size));
    }

    private static void deleteQuietly(java.util.function.Supplier<Path> path) {
        try {
            Files.deleteIfExists(path.get());
        } catch (IOException | RuntimeException e) {
            // intentionally swallowed; see delete contract
        }
    }

    private Path resolve(String key) {
        if (key == null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("Invalid media key");
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Invalid media key");
        return path;
    }

    private Path resolve(String key, ImageSize size) {
        if (key == null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("Invalid media key");
        Path directory = root.resolve("variants").resolve(size.param());
        Path path = directory.resolve(key).normalize();
        if (!path.startsWith(directory)) throw new IllegalArgumentException("Invalid media key");
        return path;
    }
}

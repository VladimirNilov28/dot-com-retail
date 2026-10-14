package ee.bytecore.backend.services.media;

import java.util.Locale;
import java.util.Optional;

/** Delivery variants of a product image, bounded by the longest edge in pixels. Never upscaled. */
public enum ImageSize {
    THUMB(320),
    MEDIUM(800),
    FULL(2000);

    private final int maxEdge;

    ImageSize(int maxEdge) {
        this.maxEdge = maxEdge;
    }

    public int maxEdge() {
        return maxEdge;
    }

    /** Lower-case name used in URLs ({@code ?size=thumb}) and as the variant directory name. */
    public String param() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** {@code null} selects {@link #FULL}; anything other than a known lower-case name is empty. */
    public static Optional<ImageSize> fromParam(String value) {
        if (value == null) return Optional.of(FULL);
        for (ImageSize size : values()) if (size.param().equals(value)) return Optional.of(size);
        return Optional.empty();
    }
}

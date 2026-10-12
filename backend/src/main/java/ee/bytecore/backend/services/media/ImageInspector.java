package ee.bytecore.backend.services.media;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Determines the real format and dimensions of untrusted bytes. Neither the filename
 * nor the declared content type is consulted: the magic bytes choose the format and
 * the matching ImageIO reader must then parse the header, without decoding pixels.
 */
public final class ImageInspector {

    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final int MIN_DIMENSION = 200;
    public static final int MAX_DIMENSION = 8000;
    public static final long MAX_PIXELS = 40_000_000L;

    public record Inspected(String contentType, String extension, int width, int height) {}

    private ImageInspector() {}

    public static Inspected inspect(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new InvalidImageException(400, "The image file is empty.");
        if (bytes.length > MAX_BYTES) throw new InvalidImageException(413, "The image must be 5 MB or smaller.");
        String format = sniff(bytes);
        if (format == null) throw new InvalidImageException(415, "Only JPEG and PNG images are supported.");
        int width;
        int height;
        try (ImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
            if (!readers.hasNext()) throw new InvalidImageException(415, "This image format is not supported.");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                width = reader.getWidth(0);
                height = reader.getHeight(0);
            } finally {
                reader.dispose();
            }
        } catch (InvalidImageException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new InvalidImageException(400, "The file is not a readable image.");
        }
        if (width < MIN_DIMENSION || height < MIN_DIMENSION) {
            throw new InvalidImageException(400, "The image must be at least 200 x 200 pixels.");
        }
        if (width > MAX_DIMENSION || height > MAX_DIMENSION || (long) width * height > MAX_PIXELS) {
            throw new InvalidImageException(400, "The image dimensions are too large (maximum 8000 px per side).");
        }
        return "jpeg".equals(format)
                ? new Inspected("image/jpeg", "jpg", width, height)
                : new Inspected("image/png", "png", width, height);
    }

    private static String sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "jpeg";
        if (b.length >= 8
                && (b[0] & 0xFF) == 0x89
                && b[1] == 'P'
                && b[2] == 'N'
                && b[3] == 'G'
                && b[4] == 0x0D
                && b[5] == 0x0A
                && b[6] == 0x1A
                && b[7] == 0x0A) return "png";
        return null;
    }
}

package ee.bytecore.backend.services.media;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Produces a delivery variant of an already-validated JPEG/PNG: the longest edge is bounded by
 * {@link ImageSize#maxEdge()}, the aspect ratio is kept, images are never upscaled, and the pixels
 * are re-encoded into a fresh file so no source metadata (EXIF, GPS, comments, ICC chunks) survives.
 * The output keeps the source format, so PNG transparency is preserved.
 */
public final class ImageResizer {

    static final float JPEG_QUALITY = 0.85f;

    public record Rendered(byte[] bytes, String contentType, int width, int height) {}

    private ImageResizer() {}

    /** @throws InvalidImageException (400) when the pixels cannot be decoded */
    public static Rendered render(byte[] source, String contentType, ImageSize size) {
        boolean jpeg = "image/jpeg".equals(contentType);
        if (!jpeg && !"image/png".equals(contentType)) {
            throw new InvalidImageException(415, "Only JPEG and PNG images are supported.");
        }
        Decoded decoded = decode(source, jpeg ? "jpeg" : "png", size.maxEdge());
        int[] target = bounded(decoded.width(), decoded.height(), size.maxEdge());
        boolean alpha = !jpeg && decoded.image().getColorModel().hasAlpha();
        BufferedImage scaled = scale(decoded.image(), target[0], target[1], alpha);
        return new Rendered(
                jpeg ? encodeJpeg(scaled) : encodePng(scaled), contentType, scaled.getWidth(), scaled.getHeight());
    }

    /** Longest edge at most {@code maxEdge}, aspect ratio kept, never larger than the source. */
    static int[] bounded(int width, int height, int maxEdge) {
        int longest = Math.max(width, height);
        if (longest <= maxEdge) return new int[] {width, height};
        double ratio = (double) maxEdge / longest;
        return new int[] {
            Math.max(1, (int) Math.round(width * ratio)), Math.max(1, (int) Math.round(height * ratio))
        };
    }

    /**
     * Decodes with source subsampling so a large upload never materialises at full resolution when
     * only a small variant is needed; at least 2x the target is kept for a smooth final downscale.
     * The returned width/height are the original ones so the target keeps the exact aspect ratio.
     */
    private record Decoded(BufferedImage image, int width, int height) {}

    private static Decoded decode(byte[] source, String format, int maxEdge) {
        try (ImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(source))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
            if (!readers.hasNext()) throw new InvalidImageException(415, "This image format is not supported.");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                int longest = Math.max(width, height);
                int step = Math.max(1, longest / (maxEdge * 2));
                ImageReadParam param = reader.getDefaultReadParam();
                if (step > 1) param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage image = reader.read(0, param);
                if (image == null) throw new InvalidImageException(400, "The file is not a readable image.");
                return new Decoded(image, width, height);
            } finally {
                reader.dispose();
            }
        } catch (InvalidImageException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new InvalidImageException(400, "The image could not be processed.");
        }
    }

    /** Progressive halving then one final bicubic step: cheap and avoids aliasing on large ratios. */
    private static BufferedImage scale(BufferedImage source, int width, int height, boolean alpha) {
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage current = source;
        int w = source.getWidth();
        int h = source.getHeight();
        do {
            w = Math.max(width, w / 2);
            h = Math.max(height, h / 2);
            if (current != source && current.getWidth() == w && current.getHeight() == h) break;
            current = draw(current, w, h, type, alpha);
        } while (w != width || h != height);
        return current;
    }

    private static BufferedImage draw(BufferedImage source, int width, int height, int type, boolean alpha) {
        BufferedImage target = new BufferedImage(width, height, type);
        Graphics2D g = target.createGraphics();
        try {
            if (!alpha) {
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, width, height);
            }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    private static byte[] encodeJpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode image", e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static byte[] encodePng(BufferedImage image) {
        var out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, "png", out)) throw new IllegalStateException("No PNG writer");
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode image", e);
        }
        return out.toByteArray();
    }
}

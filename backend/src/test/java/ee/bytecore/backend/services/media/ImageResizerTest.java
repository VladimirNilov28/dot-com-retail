package ee.bytecore.backend.services.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ImageResizerTest {

    static byte[] image(String format, int width, int height, int type) throws Exception {
        BufferedImage source = new BufferedImage(width, height, type);
        var g = source.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, width / 2, height);
        g.dispose();
        var out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(source, format, out)).isTrue();
        return out.toByteArray();
    }

    static BufferedImage decode(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @Test
    void landscapeJpegIsBoundedPerSizeKeepingAspectRatioAndFormat() throws Exception {
        byte[] jpeg = image("jpeg", 3000, 2000, BufferedImage.TYPE_INT_RGB);
        var thumb = ImageResizer.render(jpeg, "image/jpeg", ImageSize.THUMB);
        var medium = ImageResizer.render(jpeg, "image/jpeg", ImageSize.MEDIUM);
        var full = ImageResizer.render(jpeg, "image/jpeg", ImageSize.FULL);

        assertThat(new int[] {thumb.width(), thumb.height()}).containsExactly(320, 213);
        assertThat(new int[] {medium.width(), medium.height()}).containsExactly(800, 533);
        assertThat(new int[] {full.width(), full.height()}).containsExactly(2000, 1333);
        for (var rendered : new ImageResizer.Rendered[] {thumb, medium, full}) {
            assertThat(rendered.contentType()).isEqualTo("image/jpeg");
            assertThat(rendered.bytes()[0] & 0xFF).as("JPEG SOI marker").isEqualTo(0xFF);
            assertThat(rendered.bytes()[1] & 0xFF).isEqualTo(0xD8);
            BufferedImage decoded = decode(rendered.bytes());
            assertThat(decoded.getWidth()).isEqualTo(rendered.width());
            assertThat(decoded.getHeight()).isEqualTo(rendered.height());
        }
        assertThat(thumb.bytes().length).isLessThan(medium.bytes().length);
    }

    @Test
    void portraitPngIsBoundedOnItsHeightAndKeepsTransparency() throws Exception {
        byte[] png = image("png", 600, 1200, BufferedImage.TYPE_INT_ARGB);
        var thumb = ImageResizer.render(png, "image/png", ImageSize.THUMB);

        assertThat(new int[] {thumb.width(), thumb.height()}).containsExactly(160, 320);
        assertThat(thumb.contentType()).isEqualTo("image/png");
        BufferedImage decoded = decode(thumb.bytes());
        assertThat(decoded.getColorModel().hasAlpha()).isTrue();
        assertThat(decoded.getRGB(159, 0) >>> 24).as("right half stays transparent").isZero();
    }

    @Test
    void smallerImagesAreNeverUpscaled() throws Exception {
        byte[] png = image("png", 300, 250, BufferedImage.TYPE_INT_RGB);
        for (ImageSize size : ImageSize.values()) {
            var rendered = ImageResizer.render(png, "image/png", size);
            assertThat(new int[] {rendered.width(), rendered.height()}).containsExactly(300, 250);
        }
        var medium = ImageResizer.render(image("jpeg", 640, 480, BufferedImage.TYPE_INT_RGB), "image/jpeg", ImageSize.FULL);
        assertThat(new int[] {medium.width(), medium.height()}).containsExactly(640, 480);
    }

    @Test
    void reEncodingStripsSourceMetadata() throws Exception {
        String secret = "GPS 59.437N 24.7536E owner-camera-serial";
        byte[] png = pngWithTextChunk(400, 400, secret);
        assertThat(new String(png, StandardCharsets.ISO_8859_1)).contains(secret);

        for (ImageSize size : ImageSize.values()) {
            var rendered = ImageResizer.render(png, "image/png", size);
            assertThat(new String(rendered.bytes(), StandardCharsets.ISO_8859_1)).doesNotContain(secret);
        }
    }

    @Test
    void undecodablePixelsAreRejected() {
        byte[] truncated = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01};
        assertThatThrownBy(() -> ImageResizer.render(truncated, "image/jpeg", ImageSize.THUMB))
                .isInstanceOf(InvalidImageException.class);
        assertThatThrownBy(() -> ImageResizer.render(truncated, "image/gif", ImageSize.THUMB))
                .isInstanceOf(InvalidImageException.class);
    }

    @Test
    void boundedKeepsAspectRatioAndNeverGrows() {
        assertThat(ImageResizer.bounded(8000, 200, 320)).containsExactly(320, 8);
        assertThat(ImageResizer.bounded(200, 200, 320)).containsExactly(200, 200);
        assertThat(ImageResizer.bounded(801, 800, 800)).containsExactly(800, 799);
    }

    @Test
    void sizeParameterAcceptsOnlyKnownLowerCaseNames() {
        assertThat(ImageSize.fromParam(null)).contains(ImageSize.FULL);
        assertThat(ImageSize.fromParam("thumb")).contains(ImageSize.THUMB);
        assertThat(ImageSize.fromParam("medium")).contains(ImageSize.MEDIUM);
        assertThat(ImageSize.fromParam("full")).contains(ImageSize.FULL);
        assertThat(ImageSize.fromParam("THUMB")).isEmpty();
        assertThat(ImageSize.fromParam("../x")).isEmpty();
        assertThat(ImageSize.fromParam("")).isEmpty();
    }

    private static byte[] pngWithTextChunk(int width, int height, String text) throws Exception {
        BufferedImage source = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        IIOMetadata metadata = writer.getDefaultImageMetadata(
                ImageTypeSpecifier.createFromRenderedImage(source), writer.getDefaultWriteParam());
        IIOMetadataNode entry = new IIOMetadataNode("tEXtEntry");
        entry.setAttribute("keyword", "Comment");
        entry.setAttribute("value", text);
        IIOMetadataNode textNode = new IIOMetadataNode("tEXt");
        textNode.appendChild(entry);
        IIOMetadataNode root = new IIOMetadataNode("javax_imageio_png_1.0");
        root.appendChild(textNode);
        metadata.mergeTree("javax_imageio_png_1.0", root);
        var out = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(new IIOImage(source, null, metadata));
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}

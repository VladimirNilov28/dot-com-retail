package ee.bytecore.backend.services.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ImageInspectorTest {

    private static byte[] image(String format, int width, int height) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }

    @Test
    void shouldAcceptPngAndReportRealDimensions() throws Exception {
        var inspected = ImageInspector.inspect(image("png", 300, 240));
        assertThat(inspected.contentType()).isEqualTo("image/png");
        assertThat(inspected.extension()).isEqualTo("png");
        assertThat(inspected.width()).isEqualTo(300);
        assertThat(inspected.height()).isEqualTo(240);
    }

    @Test
    void shouldAcceptJpeg() throws Exception {
        var inspected = ImageInspector.inspect(image("jpg", 400, 400));
        assertThat(inspected.contentType()).isEqualTo("image/jpeg");
        assertThat(inspected.extension()).isEqualTo("jpg");
    }

    @Test
    void shouldRejectNonImageContentRegardlessOfClaimedType() {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ImageInspector.inspect(html)).isInstanceOf(InvalidImageException.class);
    }

    @Test
    void shouldRejectEmptyAndTruncatedImages() throws Exception {
        assertThatThrownBy(() -> ImageInspector.inspect(new byte[0])).isInstanceOf(InvalidImageException.class);
        byte[] png = image("png", 300, 300);
        byte[] head = java.util.Arrays.copyOf(png, 12);
        assertThatThrownBy(() -> ImageInspector.inspect(head)).isInstanceOf(InvalidImageException.class);
    }

    @Test
    void shouldRejectTooSmallImages() throws Exception {
        assertThatThrownBy(() -> ImageInspector.inspect(image("png", 50, 50)))
                .isInstanceOf(InvalidImageException.class);
    }

    @Test
    void shouldRejectOversizedPayload() {
        byte[] big = new byte[ImageInspector.MAX_BYTES + 1];
        assertThatThrownBy(() -> ImageInspector.inspect(big))
                .isInstanceOfSatisfying(
                        InvalidImageException.class, e -> assertThat(e.status()).isEqualTo(413));
    }
}

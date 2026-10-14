package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.imageio.ImageIO;

import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.product.ProductImage;
import ee.bytecore.backend.repositories.product.ProductImageRepository;
import ee.bytecore.backend.repositories.product.ProductRepository;
import ee.bytecore.backend.services.media.ImageSize;
import ee.bytecore.backend.services.media.InvalidImageException;
import ee.bytecore.backend.services.media.MediaStorage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

@Tag("unit")
class ProductImageServiceTest {

    @TempDir
    Path dir;

    ProductImageRepository images = Mockito.mock(ProductImageRepository.class);
    ProductRepository products = Mockito.mock(ProductRepository.class);
    ProductImageService service;
    MediaStorage storage;
    List<ProductImage> table = new ArrayList<>();
    long ids = 1;

    @BeforeEach
    void setUp() {
        storage = new MediaStorage(dir.toString());
        service = new ProductImageService(images, products, storage);
        Product product = Product.create("Desk lamp", "desk-lamp", null);
        when(products.findById(5L)).thenReturn(Optional.of(product));
        when(images.countByProductId(5L)).thenAnswer(i -> (long) table.size());
        when(images.saveAndFlush(any(ProductImage.class))).thenAnswer(i -> {
            ProductImage image = i.getArgument(0);
            table.add(image);
            return image;
        });
        when(images.findAllByProductIdOrderByPositionAscIdAsc(5L)).thenAnswer(i -> new ArrayList<>(table));
        when(images.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(images.countByStorageKey(any())).thenAnswer(i -> table.stream()
                .filter(t -> t.getStorageKey().equals(i.getArgument(0)))
                .count());
    }

    private static void id(ProductImage image, long id) {
        org.springframework.test.util.ReflectionTestUtils.setField(image, "id", id);
    }

    private static byte[] png() throws Exception {
        return encode("png", 300, 300);
    }

    private static byte[] encode(String format, int width, int height) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }

    private static int[] size(byte[] bytes) throws Exception {
        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        return new int[] {image.getWidth(), image.getHeight()};
    }

    private Path variant(ImageSize size, String key) {
        return dir.resolve("variants").resolve(size.param()).resolve(key);
    }

    @Test
    void shouldStoreUnderGeneratedKeyAndDefaultAltToProductName() throws Exception {
        ProductImage saved = service.upload(5L, png(), "  ");
        assertThat(saved.getStorageKey()).matches(MediaStorage.KEY);
        assertThat(saved.getAltText()).isEqualTo("Desk lamp");
        assertThat(saved.getPosition()).isZero();
        assertThat(Files.exists(dir.resolve(saved.getStorageKey()))).isTrue();
    }

    @Test
    void shouldRejectInvalidContentWithoutStoringAnything() {
        assertThatThrownBy(() -> service.upload(5L, "not an image".getBytes(), "x"))
                .isInstanceOf(InvalidImageException.class);
        assertThat(table).isEmpty();
        assertThat(dir.toFile().list()).isEmpty();
    }

    @Test
    void shouldAppendAndReorderRequiringEveryImageExactlyOnce() throws Exception {
        ProductImage a = service.upload(5L, png(), "a");
        ProductImage b = service.upload(5L, png(), "b");
        id(a, 1L);
        id(b, 2L);
        assertThat(b.getPosition()).isEqualTo(1);

        assertThatThrownBy(() -> service.reorder(5L, List.of(1L))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reorder(5L, List.of(1L, 1L))).isInstanceOf(IllegalArgumentException.class);

        service.reorder(5L, List.of(2L, 1L));
        assertThat(b.getPosition()).isZero();
        assertThat(a.getPosition()).isEqualTo(1);
    }

    @Test
    void shouldCapGalleryAtTwelveImages() throws Exception {
        for (int i = 0; i < ProductImageService.MAX_IMAGES_PER_PRODUCT; i++) service.upload(5L, png(), "i" + i);
        assertThatThrownBy(() -> service.upload(5L, png(), "one more")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectBlankAndOverlongAltTextOnUpdate() throws Exception {
        ProductImage a = service.upload(5L, png(), "a");
        id(a, 1L);
        when(images.findById(1L)).thenReturn(Optional.of(a));
        assertThatThrownBy(() -> service.updateAlt(1L, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateAlt(1L, "x".repeat(201))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldKeepSharedFileUntilLastReferenceIsDeleted() throws Exception {
        ProductImage a = service.upload(5L, png(), "a");
        id(a, 1L);
        ProductImage twin = ProductImage.create(6L, a.getStorageKey(), "image/png", 10, 300, 300, "twin", 0);
        table.add(twin);
        when(images.findById(1L)).thenReturn(Optional.of(a));
        Mockito.doAnswer(i -> table.remove(i.<ProductImage>getArgument(0)))
                .when(images)
                .delete(any(ProductImage.class));

        assertThat(service.delete(1L)).isTrue();
        assertThat(Files.exists(dir.resolve(a.getStorageKey()))).isTrue();

        when(images.findById(2L)).thenReturn(Optional.of(twin));
        id(twin, 2L);
        when(images.findAllByProductIdOrderByPositionAscIdAsc(6L)).thenReturn(List.of());
        assertThat(service.delete(2L)).isTrue();
        assertThat(Files.exists(dir.resolve(a.getStorageKey()))).isFalse();
    }

    @Test
    void uploadStoresBoundedVariantsNextToTheOriginal() throws Exception {
        ProductImage saved = service.upload(5L, encode("jpeg", 2400, 1600), "wide");
        String key = saved.getStorageKey();
        assertThat(key).endsWith(".jpg");
        assertThat(saved.getWidth()).isEqualTo(2400);
        assertThat(size(Files.readAllBytes(variant(ImageSize.THUMB, key)))).containsExactly(320, 213);
        assertThat(size(Files.readAllBytes(variant(ImageSize.MEDIUM, key)))).containsExactly(800, 533);
        assertThat(size(Files.readAllBytes(variant(ImageSize.FULL, key)))).containsExactly(2000, 1333);

        when(images.findByStorageKey(key)).thenReturn(Optional.of(saved));
        var thumb = service.deliver(key, ImageSize.THUMB).orElseThrow();
        assertThat(thumb.contentType()).isEqualTo("image/jpeg");
        assertThat(size(thumb.bytes())).containsExactly(320, 213);
    }

    @Test
    void missingVariantIsGeneratedOnceOnFirstDeliveryAndReused() throws Exception {
        // An upload that predates variants: only the original exists on disk.
        String key = "123e4567-e89b-42d3-a456-426614174000.png";
        storage.store(key, encode("png", 1000, 500));
        ProductImage legacy = ProductImage.create(5L, key, "image/png", 10, 1000, 500, "legacy", 0);
        when(images.findByStorageKey(key)).thenReturn(Optional.of(legacy));
        assertThat(Files.exists(variant(ImageSize.MEDIUM, key))).isFalse();

        var medium = service.deliver(key, ImageSize.MEDIUM).orElseThrow();
        assertThat(medium.contentType()).isEqualTo("image/png");
        assertThat(size(medium.bytes())).containsExactly(800, 400);
        assertThat(Files.readAllBytes(variant(ImageSize.MEDIUM, key))).isEqualTo(medium.bytes());
        assertThat(Files.exists(variant(ImageSize.THUMB, key))).isFalse();

        var full = service.deliver(key, ImageSize.FULL).orElseThrow();
        assertThat(size(full.bytes())).as("never upscaled").containsExactly(1000, 500);
        long modified = Files.getLastModifiedTime(variant(ImageSize.MEDIUM, key)).toMillis();
        assertThat(service.deliver(key, ImageSize.MEDIUM).orElseThrow().bytes()).isEqualTo(medium.bytes());
        assertThat(Files.getLastModifiedTime(variant(ImageSize.MEDIUM, key)).toMillis()).isEqualTo(modified);
        try (var files = Files.walk(dir)) {
            assertThat(files.filter(f -> f.getFileName().toString().endsWith(".tmp"))).isEmpty();
        }
    }

    @Test
    void concurrentFirstRequestsAllReceiveTheSameCompleteVariant() throws Exception {
        String key = "123e4567-e89b-42d3-a456-426614174001.jpg";
        storage.store(key, encode("jpeg", 1600, 1200));
        when(images.findByStorageKey(key))
                .thenReturn(Optional.of(ProductImage.create(5L, key, "image/jpeg", 10, 1600, 1200, "x", 0)));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<byte[]>>();
            for (int i = 0; i < 16; i++)
                futures.add(pool.submit(() -> service.deliver(key, ImageSize.THUMB).orElseThrow().bytes()));
            byte[] first = futures.get(0).get();
            for (var f : futures) assertThat(f.get()).isEqualTo(first);
            assertThat(size(first)).containsExactly(320, 240);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unregisteredOrMissingOriginalIsNotDelivered() throws Exception {
        String key = "123e4567-e89b-42d3-a456-426614174002.png";
        when(images.findByStorageKey(key)).thenReturn(Optional.empty());
        assertThat(service.deliver(key, ImageSize.THUMB)).isEmpty();
        when(images.findByStorageKey(key))
                .thenReturn(Optional.of(ProductImage.create(5L, key, "image/png", 10, 300, 300, "x", 0)));
        assertThat(service.deliver(key, ImageSize.THUMB)).isEmpty();
        assertThat(service.deliver("../../etc/passwd", ImageSize.THUMB)).isEmpty();
        Mockito.verify(images, Mockito.never()).findByStorageKey("../../etc/passwd");
    }

    @Test
    void undecodableLegacyOriginalFallsBackToTheOriginalBytes() {
        String key = "123e4567-e89b-42d3-a456-426614174003.jpg";
        byte[] broken = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
        storage.store(key, broken);
        when(images.findByStorageKey(key))
                .thenReturn(Optional.of(ProductImage.create(5L, key, "image/jpeg", 6, 300, 300, "x", 0)));
        var delivered = service.deliver(key, ImageSize.THUMB).orElseThrow();
        assertThat(delivered.bytes()).isEqualTo(broken);
        assertThat(Files.exists(variant(ImageSize.THUMB, key))).isFalse();
    }

    @Test
    void deletingTheLastReferenceRemovesOriginalAndEveryVariant() throws Exception {
        ProductImage a = service.upload(5L, png(), "a");
        id(a, 1L);
        when(images.findById(1L)).thenReturn(Optional.of(a));
        Mockito.doAnswer(i -> table.remove(i.<ProductImage>getArgument(0)))
                .when(images)
                .delete(any(ProductImage.class));
        for (ImageSize size : ImageSize.values()) assertThat(Files.exists(variant(size, a.getStorageKey()))).isTrue();

        assertThat(service.delete(1L)).isTrue();

        assertThat(Files.exists(dir.resolve(a.getStorageKey()))).isFalse();
        for (ImageSize size : ImageSize.values()) assertThat(Files.exists(variant(size, a.getStorageKey()))).isFalse();
    }
}

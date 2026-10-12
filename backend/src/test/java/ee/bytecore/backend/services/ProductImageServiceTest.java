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
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
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
}

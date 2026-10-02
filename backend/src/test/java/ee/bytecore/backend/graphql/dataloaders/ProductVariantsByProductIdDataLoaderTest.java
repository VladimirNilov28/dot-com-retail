package ee.bytecore.backend.graphql.dataloaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.repositories.product.ProductVariantRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductVariantsByProductIdDataLoaderTest {

    @Mock
    ProductVariantRepository productVariantRepository;

    ProductVariantsByProductIdDataLoader loader;

    private Product productOne;
    private Product productTwo;
    private ProductVariant variantOfOne;

    @BeforeEach
    void setUp() {
        loader = new ProductVariantsByProductIdDataLoader(productVariantRepository);

        productOne = Product.create("T-Shirt", "t-shirt", null);
        productOne.setId(1L);
        productTwo = Product.create("Hoodie", "hoodie", null);
        productTwo.setId(2L);

        variantOfOne = ProductVariant.create(productOne, "TSHIRT-M", new BigDecimal("19.99"));
        variantOfOne.setId(10L);
    }

    @Test
    void shouldBatchLookupInOneRepositoryCallTest() throws Exception {
        when(productVariantRepository.findAllByProductIdIn(anyList())).thenReturn(List.of(variantOfOne));

        Map<Long, List<ProductVariant>> result =
                loader.load(Set.of(1L, 2L)).toCompletableFuture().get();

        verify(productVariantRepository, times(1)).findAllByProductIdIn(anyList());
        assertThat(result.get(1L)).containsExactly(variantOfOne);
    }

    @Test
    void shouldMapMissingKeyToEmptyListRatherThanOmittingItTest() throws Exception {
        when(productVariantRepository.findAllByProductIdIn(anyList())).thenReturn(List.of(variantOfOne));

        Map<Long, List<ProductVariant>> result =
                loader.load(Set.of(1L, 2L)).toCompletableFuture().get();

        assertThat(result).containsKey(2L);
        assertThat(result.get(2L)).isEmpty();
    }
}

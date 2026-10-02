package ee.bytecore.backend.graphql.dataloaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ee.bytecore.backend.entities.category.Category;
import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.repositories.product.ProductRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CategoriesByProductIdDataLoaderTest {

    @Mock
    ProductRepository productRepository;

    CategoriesByProductIdDataLoader loader;

    private Product productOne;
    private Category shoesCategory;

    @BeforeEach
    void setUp() {
        loader = new CategoriesByProductIdDataLoader(productRepository);

        shoesCategory = Category.create("Shoes", "shoes", null);
        shoesCategory.setId(50L);

        productOne = Product.create("Sneakers", "sneakers", null);
        productOne.setId(1L);
        productOne.setCategories(new HashSet<>(Set.of(shoesCategory)));
    }

    @Test
    void shouldBatchLookupInOneRepositoryCallAndMapToCorrectKeysTest() throws Exception {
        when(productRepository.findAllByIdInWithCategories(anyList())).thenReturn(List.of(productOne));

        Map<Long, List<Category>> result =
                loader.load(Set.of(1L, 2L)).toCompletableFuture().get();

        verify(productRepository, times(1)).findAllByIdInWithCategories(anyList());
        assertThat(result.get(1L)).containsExactly(shoesCategory);
        assertThat(result.get(2L)).isEmpty();
    }
}

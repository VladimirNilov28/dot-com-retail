package ee.bytecore.backend.graphql.dataloaders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import ee.bytecore.backend.entities.category.Category;
import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.repositories.product.ProductRepository;

import com.netflix.graphql.dgs.DgsDataLoader;
import org.dataloader.MappedBatchLoader;

@DgsDataLoader(name = "categoriesByProductId")
public class CategoriesByProductIdDataLoader implements MappedBatchLoader<Long, List<Category>> {

    private final ProductRepository productRepository;

    public CategoriesByProductIdDataLoader(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public CompletionStage<Map<Long, List<Category>>> load(Set<Long> productIds) {
        return CompletableFuture.supplyAsync(() -> {
            List<Product> products = productRepository.findAllByIdInWithCategories(new ArrayList<>(productIds));

            Map<Long, List<Category>> result = new HashMap<>();
            for (Long productId : productIds) {
                result.put(productId, List.of());
            }
            for (Product product : products) {
                result.put(product.getId(), new ArrayList<>(product.getCategories()));
            }
            return result;
        });
    }
}

package ee.bytecore.backend.graphql.dataloaders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.repositories.product.ProductVariantRepository;

import com.netflix.graphql.dgs.DgsDataLoader;
import org.dataloader.MappedBatchLoader;

@DgsDataLoader(name = "variantsByProductId")
public class ProductVariantsByProductIdDataLoader implements MappedBatchLoader<Long, List<ProductVariant>> {

    private final ProductVariantRepository productVariantRepository;

    public ProductVariantsByProductIdDataLoader(ProductVariantRepository productVariantRepository) {
        this.productVariantRepository = productVariantRepository;
    }

    /**
     * Runs synchronously on the calling (request) thread rather than via
     * {@code CompletableFuture.supplyAsync} - the latter hops onto the
     * common {@code ForkJoinPool}, which has neither the Hibernate
     * session (OSIV) nor the Spring Security context bound to it. Any
     * nested resolver reached only through this DataLoader (e.g.
     * {@code ProductVariant.product}, {@code ProductVariant.inventory})
     * would then fail with {@code LazyInitializationException} or
     * {@code AuthenticationCredentialsNotFoundException}. Batching (one
     * query per distinct set of product ids) is unaffected.
     */
    @Override
    public CompletionStage<Map<Long, List<ProductVariant>>> load(Set<Long> productIds) {
        List<ProductVariant> all = productVariantRepository.findAllByProductIdIn(new ArrayList<>(productIds));
        Map<Long, List<ProductVariant>> grouped = all.stream()
                .collect(Collectors.groupingBy(variant -> variant.getProduct().getId()));

        Map<Long, List<ProductVariant>> result = new HashMap<>();
        for (Long productId : productIds) {
            result.put(productId, grouped.getOrDefault(productId, List.of()));
        }
        return CompletableFuture.completedFuture(result);
    }
}

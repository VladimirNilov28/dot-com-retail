package ee.bytecore.backend.graphql.dataloaders;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import ee.bytecore.backend.entities.product.Brand;
import ee.bytecore.backend.repositories.product.BrandRepository;

import com.netflix.graphql.dgs.DgsDataLoader;
import org.dataloader.MappedBatchLoader;

@DgsDataLoader(name = "brandByProductId")
public class BrandByProductIdDataLoader implements MappedBatchLoader<Long, Brand> {

    private final BrandRepository brands;

    public BrandByProductIdDataLoader(BrandRepository brands) {
        this.brands = brands;
    }

    /** Products without a brand are simply absent from the result map (resolved as null). */
    @Override
    public CompletionStage<Map<Long, Brand>> load(Set<Long> productIds) {
        Map<Long, Brand> result = new HashMap<>();
        for (Object[] row : brands.findAllByProductIdIn(productIds)) {
            result.put((Long) row[0], (Brand) row[1]);
        }
        return CompletableFuture.completedFuture(result);
    }
}

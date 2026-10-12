package ee.bytecore.backend.graphql.dataloaders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import ee.bytecore.backend.entities.product.ProductImage;
import ee.bytecore.backend.repositories.product.ProductImageRepository;

import com.netflix.graphql.dgs.DgsDataLoader;
import org.dataloader.MappedBatchLoader;

/** Batches the gallery for listings/search where many products select {@code images}. */
@DgsDataLoader(name = "imagesByProductId")
public class ImagesByProductIdDataLoader implements MappedBatchLoader<Long, List<ProductImage>> {

    private final ProductImageRepository images;

    public ImagesByProductIdDataLoader(ProductImageRepository images) {
        this.images = images;
    }

    @Override
    public CompletionStage<Map<Long, List<ProductImage>>> load(Set<Long> productIds) {
        Map<Long, List<ProductImage>> result = new HashMap<>();
        for (Long id : productIds) result.put(id, new ArrayList<>());
        for (ProductImage image : images.findAllByProductIdInOrderByPositionAscIdAsc(productIds)) {
            result.get(image.getProductId()).add(image);
        }
        return CompletableFuture.completedFuture(result);
    }
}

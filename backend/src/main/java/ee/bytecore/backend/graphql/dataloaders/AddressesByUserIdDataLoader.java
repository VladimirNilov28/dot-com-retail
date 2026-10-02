package ee.bytecore.backend.graphql.dataloaders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import ee.bytecore.backend.entities.user.UserAddress;
import ee.bytecore.backend.repositories.user.UserAddressRepository;

import com.netflix.graphql.dgs.DgsDataLoader;
import org.dataloader.MappedBatchLoader;

@DgsDataLoader(name = "addressesByUserId")
public class AddressesByUserIdDataLoader implements MappedBatchLoader<Long, List<UserAddress>> {

    private final UserAddressRepository userAddressRepository;

    public AddressesByUserIdDataLoader(UserAddressRepository userAddressRepository) {
        this.userAddressRepository = userAddressRepository;
    }

    /**
     * Runs synchronously on the calling (request) thread - see
     * {@code ProductVariantsByProductIdDataLoader} for why
     * {@code CompletableFuture.supplyAsync} is unsafe here.
     */
    @Override
    public CompletionStage<Map<Long, List<UserAddress>>> load(Set<Long> userIds) {
        List<UserAddress> all = userAddressRepository.findAllByUserIdIn(new ArrayList<>(userIds));
        Map<Long, List<UserAddress>> grouped = all.stream()
                .collect(Collectors.groupingBy(address -> address.getUser().getId()));

        Map<Long, List<UserAddress>> result = new HashMap<>();
        for (Long userId : userIds) {
            result.put(userId, grouped.getOrDefault(userId, List.of()));
        }
        return CompletableFuture.completedFuture(result);
    }
}

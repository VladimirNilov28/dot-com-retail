package ee.bytecore.backend.graphql.dataloaders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import ee.bytecore.backend.entities.user.UserPaymentMethod;
import ee.bytecore.backend.repositories.user.UserPaymentMethodRepository;

import com.netflix.graphql.dgs.DgsDataLoader;
import org.dataloader.MappedBatchLoader;

@DgsDataLoader(name = "paymentMethodsByUserId")
public class PaymentMethodsByUserIdDataLoader implements MappedBatchLoader<Long, List<UserPaymentMethod>> {

    private final UserPaymentMethodRepository userPaymentMethodRepository;

    public PaymentMethodsByUserIdDataLoader(UserPaymentMethodRepository userPaymentMethodRepository) {
        this.userPaymentMethodRepository = userPaymentMethodRepository;
    }

    /**
     * Runs synchronously on the calling (request) thread - see
     * {@code ProductVariantsByProductIdDataLoader} for why
     * {@code CompletableFuture.supplyAsync} is unsafe here.
     */
    @Override
    public CompletionStage<Map<Long, List<UserPaymentMethod>>> load(Set<Long> userIds) {
        List<UserPaymentMethod> all = userPaymentMethodRepository.findAllByUserIdIn(new ArrayList<>(userIds));
        Map<Long, List<UserPaymentMethod>> grouped =
                all.stream().collect(Collectors.groupingBy(pm -> pm.getUser().getId()));

        Map<Long, List<UserPaymentMethod>> result = new HashMap<>();
        for (Long userId : userIds) {
            result.put(userId, grouped.getOrDefault(userId, List.of()));
        }
        return CompletableFuture.completedFuture(result);
    }
}

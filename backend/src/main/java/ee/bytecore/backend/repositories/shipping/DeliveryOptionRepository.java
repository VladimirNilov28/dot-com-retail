package ee.bytecore.backend.repositories.shipping;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.shipping.DeliveryOption;

public interface DeliveryOptionRepository extends JpaRepository<DeliveryOption, Long> {
    List<DeliveryOption> findAllByOrderBySortOrderAscIdAsc();

    List<DeliveryOption> findAllByActiveTrueOrderBySortOrderAscIdAsc();

    Optional<DeliveryOption> findByCode(String code);
}

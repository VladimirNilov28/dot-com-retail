package ee.bytecore.backend.repositories.inventory;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import ee.bytecore.backend.entities.inventory.Inventory;

import jakarta.persistence.LockModeType;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {
    List<Inventory> findAllByWarehouseId(Long warehouseId);

    List<Inventory> findAllByWarehouseIdIn(List<Long> warehouseIds);

    List<Inventory> findAllByProductVariantId(Long productVariantId);

    List<Inventory> findAllByProductVariantIdIn(List<Long> productVariantIds);

    Optional<Inventory> findByProductVariantIdAndWarehouseId(Long productVariantId, Long warehouseId);

    /**
     * Locks every inventory row for a variant for the duration of the
     * caller's transaction, ordered deterministically by id (the MVP
     * multi-warehouse tie-break rule), so concurrent checkouts against the
     * same variant serialize instead of overselling.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inventory i where i.productVariant.id = :productVariantId order by i.id asc")
    List<Inventory> lockAllByProductVariantIdOrderByIdAsc(Long productVariantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inventory i where i.id = :id")
    Optional<Inventory> lockById(Long id);
}

package ee.bytecore.backend.repositories.inventory;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.inventory.Inventory;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {
    List<Inventory> findAllByWarehouseId(Long warehouseId);

    List<Inventory> findAllByProductVariantId(Long productVariantId);

    Optional<Inventory> findByProductVariantIdAndWarehouseId(Long productVariantId, Long warehouseId);
}

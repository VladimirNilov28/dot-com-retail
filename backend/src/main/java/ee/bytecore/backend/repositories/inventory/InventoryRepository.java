package ee.bytecore.backend.repositories.inventory;

import ee.bytecore.backend.entities.inventory.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {
}

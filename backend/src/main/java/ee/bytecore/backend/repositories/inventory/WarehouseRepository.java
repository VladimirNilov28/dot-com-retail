package ee.bytecore.backend.repositories.inventory;

import ee.bytecore.backend.entities.inventory.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {
}

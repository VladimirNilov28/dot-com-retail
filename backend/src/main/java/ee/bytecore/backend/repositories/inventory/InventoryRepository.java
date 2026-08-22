package ee.bytecore.backend.repositories.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.inventory.Inventory;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {}

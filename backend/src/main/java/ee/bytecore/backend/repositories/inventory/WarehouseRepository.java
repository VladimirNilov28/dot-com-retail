package ee.bytecore.backend.repositories.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.inventory.Warehouse;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {}

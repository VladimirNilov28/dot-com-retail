package ee.bytecore.backend.services;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.entities.inventory.Inventory;
import ee.bytecore.backend.entities.inventory.Warehouse;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.repositories.inventory.InventoryRepository;
import ee.bytecore.backend.repositories.inventory.WarehouseRepository;
import ee.bytecore.backend.repositories.product.ProductVariantRepository;

import jakarta.persistence.EntityNotFoundException;

@Service
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final ProductVariantRepository productVariantRepository;
    private final WarehouseRepository warehouseRepository;

    public InventoryService(
            InventoryRepository inventoryRepository,
            ProductVariantRepository productVariantRepository,
            WarehouseRepository warehouseRepository) {
        this.inventoryRepository = inventoryRepository;
        this.productVariantRepository = productVariantRepository;
        this.warehouseRepository = warehouseRepository;
    }

    public List<Inventory> findAllByWarehouseId(Long warehouseId) {
        return inventoryRepository.findAllByWarehouseId(warehouseId);
    }

    public List<Inventory> findAllByProductVariantId(Long productVariantId) {
        return inventoryRepository.findAllByProductVariantId(productVariantId);
    }

    @Transactional
    public Inventory setInventory(Long productVariantId, Long warehouseId, Integer quantity) {
        if (quantity == null || quantity < 0) {
            throw new IllegalArgumentException("Quantity must not be negative");
        }
        return inventoryRepository
                .findByProductVariantIdAndWarehouseId(productVariantId, warehouseId)
                .map(existing -> {
                    existing.setQuantity(quantity);
                    return inventoryRepository.save(existing);
                })
                .orElseGet(() -> {
                    ProductVariant variant = productVariantRepository
                            .findById(productVariantId)
                            .orElseThrow(() -> new EntityNotFoundException(
                                    String.format("ProductVariant with id %s not found", productVariantId)));
                    Warehouse warehouse = warehouseRepository
                            .findById(warehouseId)
                            .orElseThrow(() -> new EntityNotFoundException(
                                    String.format("Warehouse with id %s not found", warehouseId)));
                    return inventoryRepository.save(Inventory.create(variant, warehouse, quantity));
                });
    }
}

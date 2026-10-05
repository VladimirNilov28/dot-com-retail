package ee.bytecore.backend.services;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.entities.inventory.Inventory;
import ee.bytecore.backend.entities.inventory.Warehouse;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.exceptions.InsufficientStockException;
import ee.bytecore.backend.repositories.inventory.InventoryRepository;
import ee.bytecore.backend.repositories.inventory.WarehouseRepository;
import ee.bytecore.backend.repositories.product.ProductVariantRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;

@Service
public class InventoryService {

    @Autowired
    private ObjectProvider<EntityManager> entityManager;

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

    /**
     * Locks and picks the single inventory row (lowest id first, i.e. the
     * MVP multi-warehouse tie-break rule) that can fulfil the full requested
     * quantity for a variant, then decrements it in place. Split fulfillment
     * across multiple rows/warehouses is intentionally out of scope: if no
     * single row has enough stock, this fails even if the sum across rows
     * would be sufficient.
     */
    @Transactional
    public Inventory allocateAndDecrement(Long productVariantId, int quantity) {
        List<Inventory> candidates = inventoryRepository.lockAllByProductVariantIdOrderByIdAsc(productVariantId);
        // Preview may already have loaded these rows before a lock wait.
        // Refresh under the acquired locks rather than decrement stale managed quantities.
        candidates.forEach(entityManager.getObject()::refresh);
        int bestAvailable =
                candidates.stream().mapToInt(Inventory::getQuantity).max().orElse(0);
        Inventory chosen = candidates.stream()
                .filter(inventory -> inventory.getQuantity() >= quantity)
                .findFirst()
                .orElseThrow(() -> new InsufficientStockException(productVariantId, quantity, bestAvailable));
        chosen.setQuantity(chosen.getQuantity() - quantity);
        return inventoryRepository.save(chosen);
    }

    /**
     * Restores previously decremented stock to its exact source row, used
     * for early order cancellation restock.
     */
    @Transactional
    public void restore(Long inventoryId, int quantity) {
        Inventory inventory = inventoryRepository
                .lockById(inventoryId)
                .orElseThrow(() ->
                        new EntityNotFoundException(String.format("Inventory with id %s not found", inventoryId)));
        inventory.setQuantity(inventory.getQuantity() + quantity);
        inventoryRepository.save(inventory);
    }
}

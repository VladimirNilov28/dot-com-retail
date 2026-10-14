package ee.bytecore.backend.services;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.entities.inventory.Warehouse;
import ee.bytecore.backend.repositories.inventory.WarehouseRepository;

import jakarta.persistence.EntityNotFoundException;

@Service
public class WarehouseService {

    private final WarehouseRepository warehouseRepository;

    public WarehouseService(WarehouseRepository warehouseRepository) {
        this.warehouseRepository = warehouseRepository;
    }

    public Optional<Warehouse> findById(Long id) {
        return warehouseRepository.findById(id);
    }

    public List<Warehouse> findAll() {
        return warehouseRepository.findAll();
    }

    public Warehouse create(String name, String location) {
        return warehouseRepository.save(Warehouse.create(name, location));
    }

    @Transactional
    public Warehouse update(Long id, String name, String location) {
        Warehouse existing = warehouseRepository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(String.format("Warehouse with id %s not found", id)));

        if (name != null) existing.setName(name);
        if (location != null) existing.setLocation(location);

        return warehouseRepository.save(existing);
    }

    public boolean deleteById(Long id) {
        if (warehouseRepository.findById(id).isEmpty()) {
            return false;
        }
        warehouseRepository.deleteById(id);
        return true;
    }
}

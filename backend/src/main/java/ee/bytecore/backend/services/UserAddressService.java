package ee.bytecore.backend.services;

import java.util.List;
import java.util.Objects;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import ee.bytecore.backend.entities.user.UserAddress;
import ee.bytecore.backend.repositories.user.UserAddressRepository;

import jakarta.persistence.EntityNotFoundException;

@Service
public class UserAddressService {

    private final UserAddressRepository userAddressRepository;

    public UserAddressService(UserAddressRepository userAddressRepository) {
        this.userAddressRepository = userAddressRepository;
    }

    public List<UserAddress> findAllByUserId(Long userId) {
        return userAddressRepository.findAllByUserId(userId);
    }

    public UserAddress create(UserAddress address) {
        return userAddressRepository.save(address);
    }

    public UserAddress findOwned(Long userId, Long addressId) {
        UserAddress address = userAddressRepository
                .findById(addressId)
                .orElseThrow(
                        () -> new EntityNotFoundException(String.format("Address with id %s not found", addressId)));
        requireOwnership(userId, address);
        return address;
    }

    public UserAddress save(UserAddress address) {
        return userAddressRepository.save(address);
    }

    public boolean deleteOwned(Long userId, Long addressId) {
        return userAddressRepository
                .findById(addressId)
                .map(address -> {
                    requireOwnership(userId, address);
                    userAddressRepository.deleteById(addressId);
                    return true;
                })
                .orElse(false);
    }

    private void requireOwnership(Long userId, UserAddress address) {
        Long ownerId = address.getUser() == null ? null : address.getUser().getId();
        if (!Objects.equals(userId, ownerId)) {
            throw new AccessDeniedException("Address does not belong to the current user");
        }
    }
}

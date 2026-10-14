package ee.bytecore.backend.services;

import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import ee.bytecore.backend.config.CheckoutSettings;
import ee.bytecore.backend.entities.user.UserAddress;
import ee.bytecore.backend.repositories.user.UserAddressRepository;
import ee.bytecore.backend.services.CheckoutValues.Address;

import jakarta.persistence.EntityNotFoundException;

@Service
public class UserAddressService {

    private final UserAddressRepository userAddressRepository;
    private final ObjectProvider<CheckoutSettings> checkoutSettings;

    public UserAddressService(
            UserAddressRepository userAddressRepository, ObjectProvider<CheckoutSettings> checkoutSettings) {
        this.userAddressRepository = userAddressRepository;
        this.checkoutSettings = checkoutSettings;
    }

    public List<UserAddress> findAllByUserId(Long userId) {
        return userAddressRepository.findAllByUserId(userId);
    }

    public UserAddress create(UserAddress address) {
        return userAddressRepository.save(validated(address));
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
        return userAddressRepository.save(validated(address));
    }

    /**
     * Saved addresses must be usable at checkout, so they go through the same
     * normalisation (required fields, lengths, no control characters) and the
     * same supported-destination allowlist as checkout addresses.
     */
    UserAddress validated(UserAddress address) {
        Address normalized = CheckoutSupport.normalize(new Address(
                address.getFirstName(),
                address.getLastName(),
                address.getCity(),
                address.getCountry(),
                address.getPostalCode(),
                address.getAddressLine1(),
                address.getAddressLine2(),
                address.getMobile()));
        CheckoutSettings settings = checkoutSettings.getIfAvailable();
        List<String> supported = settings == null ? List.of() : settings.getSupportedCountries();
        if (!supported.contains(normalized.countryCode()))
            throw new IllegalArgumentException("Country is not a supported delivery destination");
        address.setFirstName(normalized.firstName());
        address.setLastName(normalized.lastName());
        address.setCity(normalized.city());
        address.setCountry(normalized.countryCode());
        address.setPostalCode(normalized.postalCode());
        address.setAddressLine1(normalized.addressLine1());
        address.setAddressLine2(normalized.addressLine2());
        address.setMobile(normalized.phone());
        return address;
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

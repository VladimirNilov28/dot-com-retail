package ee.bytecore.backend.services;

import java.util.List;
import java.util.Objects;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import ee.bytecore.backend.entities.user.UserPaymentMethod;
import ee.bytecore.backend.repositories.user.UserPaymentMethodRepository;

@Service
public class UserPaymentMethodService {

    private final UserPaymentMethodRepository userPaymentMethodRepository;

    public UserPaymentMethodService(UserPaymentMethodRepository userPaymentMethodRepository) {
        this.userPaymentMethodRepository = userPaymentMethodRepository;
    }

    public List<UserPaymentMethod> findAllByUserId(Long userId) {
        return userPaymentMethodRepository.findAllByUserId(userId);
    }

    public UserPaymentMethod create(UserPaymentMethod paymentMethod) {
        return userPaymentMethodRepository.save(paymentMethod);
    }

    public boolean deleteOwned(Long userId, Long paymentMethodId) {
        return userPaymentMethodRepository
                .findById(paymentMethodId)
                .map(paymentMethod -> {
                    Long ownerId = paymentMethod.getUser() == null
                            ? null
                            : paymentMethod.getUser().getId();
                    if (!Objects.equals(userId, ownerId)) {
                        throw new AccessDeniedException("Payment method does not belong to the current user");
                    }
                    userPaymentMethodRepository.deleteById(paymentMethodId);
                    return true;
                })
                .orElse(false);
    }
}

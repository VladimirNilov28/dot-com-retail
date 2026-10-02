package ee.bytecore.backend.services;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import ee.bytecore.backend.entities.user.UserPaymentMethod;
import ee.bytecore.backend.repositories.user.UserPaymentMethodRepository;

import jakarta.validation.ConstraintViolationException;

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
        try {
            return userPaymentMethodRepository.save(paymentMethod);
        } catch (ConstraintViolationException e) {
            String violations = e.getConstraintViolations().stream()
                    .map(v -> String.format("%s: %s", v.getPropertyPath(), v.getMessage()))
                    .collect(Collectors.joining("; "));
            throw new IllegalArgumentException(String.format("Invalid payment method: %s", violations));
        }
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

package ee.bytecore.backend.repositories.user;

import ee.bytecore.backend.entities.user.UserPaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPaymentMethodRepository extends JpaRepository<UserPaymentMethod, Long> {
}

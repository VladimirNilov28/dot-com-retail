package ee.bytecore.backend.repositories.user;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.user.UserPaymentMethod;

public interface UserPaymentMethodRepository extends JpaRepository<UserPaymentMethod, Long> {}

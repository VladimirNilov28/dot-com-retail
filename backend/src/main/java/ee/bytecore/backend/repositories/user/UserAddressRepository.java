package ee.bytecore.backend.repositories.user;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.user.UserAddress;

public interface UserAddressRepository extends JpaRepository<UserAddress, Long> {}

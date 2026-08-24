package ee.bytecore.backend.repositories.user;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.user.UserAddress;

public interface UserAddressRepository extends JpaRepository<UserAddress, Long> {
    List<UserAddress> findAllByUserId(Long userId);
}

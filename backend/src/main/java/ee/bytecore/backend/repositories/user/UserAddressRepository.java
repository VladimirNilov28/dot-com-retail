package ee.bytecore.backend.repositories.user;

import ee.bytecore.backend.entities.user.UserAddress;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAddressRepository extends JpaRepository<UserAddress, Long> {

}

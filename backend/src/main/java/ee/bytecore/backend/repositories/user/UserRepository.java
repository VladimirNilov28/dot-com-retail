package ee.bytecore.backend.repositories.user;


import ee.bytecore.backend.entities.user.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}

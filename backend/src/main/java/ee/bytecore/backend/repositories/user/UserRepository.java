package ee.bytecore.backend.repositories.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.user.User;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
}

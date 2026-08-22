package ee.bytecore.backend.repositories.user;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.user.User;

public interface UserRepository extends JpaRepository<User, Long> {}

package ee.bytecore.backend.services;

import java.time.LocalDate;
import java.util.Optional;

import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.enums.UserRole;
import ee.bytecore.backend.exceptions.UserAlreadyExistsException;
import ee.bytecore.backend.exceptions.UserNotFoundException;
import ee.bytecore.backend.integration.KratosClient;
import ee.bytecore.backend.repositories.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final KratosClient kratosClient;

    public UserService(UserRepository userRepository, KratosClient kratosClient) {
        this.userRepository = userRepository;
        this.kratosClient = kratosClient;
    }

    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    public User create(String username, String email, LocalDate dateOfBirth) {
        if (userRepository.existsByUsername(username)) {
            throw new UserAlreadyExistsException(String.format("User with username %s already exists", username));
        }
        if (userRepository.existsByEmail(email)) {
            throw new UserAlreadyExistsException(String.format("User with email %s already exists", email));
        }
        User user = User.create(username, email, dateOfBirth);
        return userRepository.save(user);
    }

    @Transactional
    public User provision(String username, String email, LocalDate dateOfBirth, UserRole role) {
        Optional<User> existing = userRepository.findByUsername(username);
        if (existing.isPresent()) {
            User user = existing.get();
            if (user.getRole() != role) {
                user.setRole(role);
                userRepository.save(user);
            }
            return user;
        }

        User created = create(username, email, dateOfBirth);
        created.setRole(role);
        return userRepository.save(created);
    }

    @Transactional
    public User updateProfile(Long id, String username, String email) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(String.format("User with id %s not found", id)));

        if (!user.getEmail().equals(email)) {
            // Fail-fast, before touching Spring's own state: if Kratos can't
            // be synced, the operation must not leave the two systems
            // inconsistent.
            kratosClient.updateIdentityEmail(user.getEmail(), email);
        }

        user.setUsername(username);
        user.setEmail(email);
        return user;
    }

    public void deleteById(Long id) {
        if (!userRepository.existsById(id)) {
            throw new UserNotFoundException(String.format("User with id %s not found", id));
        }
        userRepository.deleteById(id);
    }

    @Transactional
    public User updateRole(Long id, UserRole role) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(String.format("User with id %s not found", id)));
        user.setRole(role);
        return user;
    }
}

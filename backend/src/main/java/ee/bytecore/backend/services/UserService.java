package ee.bytecore.backend.services;

import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.enums.UserRole;
import ee.bytecore.backend.exceptions.IdentitySyncException;
import ee.bytecore.backend.exceptions.UserAlreadyExistsException;
import ee.bytecore.backend.exceptions.UserNotFoundException;
import ee.bytecore.backend.integration.KratosClient;
import ee.bytecore.backend.repositories.user.UserRepository;

@Service
public class UserService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int MAX_USERNAME_LENGTH = 255;
    private static final int MAX_EMAIL_LENGTH = 255;

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
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username must not be blank");
        }
        if (username.length() > MAX_USERNAME_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("Username must not exceed %d characters", MAX_USERNAME_LENGTH));
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email must not be blank");
        }
        if (email.length() > MAX_EMAIL_LENGTH) {
            throw new IllegalArgumentException(String.format("Email must not exceed %d characters", MAX_EMAIL_LENGTH));
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException(String.format("Email is not valid: %s", email));
        }
        if (userRepository.existsByUsername(username)) {
            throw new UserAlreadyExistsException(String.format("User with username %s already exists", username));
        }
        if (userRepository.existsByEmail(email)) {
            throw new UserAlreadyExistsException(String.format("User with email %s already exists", email));
        }
        User user = User.create(username, email, dateOfBirth);
        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // Two racing registrations/creations can both pass the
            // exists-by checks above; the DB UNIQUE constraint is the final
            // arbiter, so translate its failure into the same clean
            // conflict the pre-check would have produced.
            throw new UserAlreadyExistsException(
                    String.format("User with username %s or email %s already exists", username, email));
        }
    }

    /**
     * Public self-registration entry point. Unlike {@link #provision}, this
     * always creates a brand-new customer (never upserts) and always forces
     * role USER — the caller has no way to influence the role. Registers a
     * matching Kratos identity, linked back via
     * metadata_admin.spring_user_id; if that fails, the just-created Spring
     * user is deleted so no broken/unusable canonical account is left
     * behind.
     */
    @Transactional
    public User registerCustomer(String username, String email, String password, LocalDate dateOfBirth) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Password must not be blank");
        }
        if (dateOfBirth == null) {
            throw new IllegalArgumentException("Date of birth is required");
        }

        User created = create(username, email, dateOfBirth);

        try {
            kratosClient.createIdentity(email, password, created.getId());
        } catch (IdentitySyncException e) {
            // Compensate: an unusable canonical user (no way to
            // authenticate) is worse than no user at all.
            userRepository.deleteById(created.getId());
            throw e;
        }

        return created;
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
        User user = userRepository
                .findById(id)
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
        try {
            userRepository.deleteById(id);
        } catch (DataIntegrityViolationException e) {
            // The user still has related rows (e.g. orders) that reference
            // them via a non-cascading FK - surface a clean domain error
            // instead of leaking the raw SQL/constraint message.
            throw new IllegalArgumentException(
                    String.format("Cannot delete user %s: user has existing orders or other related records", id));
        }
    }

    @Transactional
    public User updateRole(Long id, UserRole role) {
        User user = userRepository
                .findById(id)
                .orElseThrow(() -> new UserNotFoundException(String.format("User with id %s not found", id)));
        user.setRole(role);
        return user;
    }
}

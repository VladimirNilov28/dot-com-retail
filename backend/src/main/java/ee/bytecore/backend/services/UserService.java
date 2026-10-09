package ee.bytecore.backend.services;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.entities.user.UserRegistrationReservation;
import ee.bytecore.backend.enums.UserRole;
import ee.bytecore.backend.exceptions.IdentitySyncException;
import ee.bytecore.backend.exceptions.UserAlreadyExistsException;
import ee.bytecore.backend.exceptions.UserNotFoundException;
import ee.bytecore.backend.integration.HydraClient;
import ee.bytecore.backend.integration.KratosClient;
import ee.bytecore.backend.repositories.user.UserRegistrationReservationRepository;
import ee.bytecore.backend.repositories.user.UserRepository;

@Service
public class UserService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int MAX_USERNAME_LENGTH = 255;
    private static final int MAX_EMAIL_LENGTH = 255;

    private final UserRepository userRepository;
    private final UserRegistrationReservationRepository registrationRepository;
    private final KratosClient kratosClient;
    private final HydraClient hydraClient;
    private final TransactionTemplate deletionTransaction;

    public UserService(
            UserRepository userRepository,
            UserRegistrationReservationRepository registrationRepository,
            KratosClient kratosClient,
            HydraClient hydraClient,
            PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.registrationRepository = registrationRepository;
        this.kratosClient = kratosClient;
        this.hydraClient = hydraClient;
        this.deletionTransaction = new TransactionTemplate(transactionManager);
        this.deletionTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Optional<User> findById(Long id) {
        return userRepository.findById(id).filter(user -> !user.isDeleted() && user.getDeletionIdentityId() == null);
    }

    @Transactional
    public User create(String username, String email, LocalDate dateOfBirth) {
        return create(username, email, dateOfBirth, null, null);
    }

    private User create(String username, String email, LocalDate dateOfBirth, UUID reservationId, UUID identityId) {
        validateRegistrationFields(username, email);
        lockRegistrationClaims(username, email);
        requireUnreserved(username, email, reservationId);
        if (userRepository.existsByUsername(username)) {
            throw new UserAlreadyExistsException(String.format("User with username %s already exists", username));
        }
        if (userRepository.existsByEmail(email)) {
            throw new UserAlreadyExistsException(String.format("User with email %s already exists", email));
        }
        User user = User.create(username, email, dateOfBirth);
        user.setKratosIdentityId(identityId);
        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            throw new UserAlreadyExistsException("Account identifiers are already in use.");
        }
    }

    private void validateRegistrationFields(String username, String email) {
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
    }

    @Deprecated
    public User registerCustomer(String username, String email, String password, LocalDate dateOfBirth) {
        throw new UnsupportedOperationException("Use Kratos browser registration.");
    }

    private void lockRegistrationClaims(String username, String email) {
        Stream.of("email:" + email, "username:" + username).sorted().forEach(registrationRepository::lockClaim);
    }

    private void requireUnreserved(String username, String email, UUID allowedReservation) {
        var usernameClaim = registrationRepository.findByUsername(username);
        var emailClaim = registrationRepository.findByEmail(email);
        if (Stream.of(usernameClaim, emailClaim)
                .flatMap(Optional::stream)
                .anyMatch(claim -> !Objects.equals(claim.getId(), allowedReservation))) {
            throw new UserAlreadyExistsException("Account identifiers are reserved by a registration.");
        }
    }

    @Transactional
    public UserRegistrationReservation reserveRegistration(
            UUID flowId, String username, String email, LocalDate dateOfBirth, Instant expiresAt) {
        validateRegistrationFields(username, email);
        if (flowId == null
                || dateOfBirth == null
                || expiresAt == null
                || !expiresAt.isAfter(Instant.now())
                || expiresAt.isAfter(Instant.now().plus(Duration.ofHours(24)))) {
            throw new IllegalArgumentException("A valid registration flow and date of birth are required.");
        }
        registrationRepository.lockClaim("flow:" + flowId);
        lockRegistrationClaims(username, email);
        var existing = registrationRepository.findByFlowId(flowId);
        if (existing.isPresent()) {
            var claim = existing.get();
            if (!claim.getUsername().equals(username)
                    || !claim.getEmail().equals(email)
                    || !claim.getDateOfBirth().equals(dateOfBirth)) {
                throw new UserAlreadyExistsException("Restart registration to change reserved account details.");
            }
            return claim;
        }
        requireUnreserved(username, email, null);
        if (userRepository.existsByUsername(username) || userRepository.existsByEmail(email)) {
            throw new UserAlreadyExistsException("Account identifiers cannot be registered.");
        }
        return registrationRepository.saveAndFlush(
                UserRegistrationReservation.create(flowId, username, email, dateOfBirth, expiresAt));
    }

    @Transactional
    public UserRegistrationReservation bindRegistration(UUID identityId) {
        var identity = kratosClient.getNativeRegistrationIdentity(identityId, false);
        var reservation = registrationRepository
                .findLockedById(identity.reservationId())
                .orElseThrow(() -> new IdentitySyncException("Registration reservation was not found."));
        requireMatchingRegistration(reservation, identity);
        if (reservation.getKratosIdentityId() != null
                && !reservation.getKratosIdentityId().equals(identityId)) {
            throw new IdentitySyncException("Registration is already bound to a different identity.");
        }
        reservation.setKratosIdentityId(identityId);
        return reservation;
    }

    @Transactional
    public User finalizeRegistration(UUID identityId) {
        var identity = kratosClient.getNativeRegistrationIdentity(identityId, true);
        lockRegistrationClaims(identity.username(), identity.email());
        var existing = userRepository.findByKratosIdentityId(identityId);
        if (existing.isPresent()) {
            requireActive(existing.get());
            if (identity.springUserId() != null
                    && !Objects.equals(identity.springUserId(), existing.get().getId())) {
                throw new IdentitySyncException("Canonical identity link does not match.");
            }
            return existing.get();
        }
        if (identity.springUserId() != null) {
            throw new IdentitySyncException("Native identity has an inconsistent canonical link.");
        }
        var reservation = registrationRepository
                .findLockedById(identity.reservationId())
                .orElseThrow(() -> new IdentitySyncException("Registration reservation was not found."));
        requireMatchingRegistration(reservation, identity);
        if (reservation.getKratosIdentityId() != null
                && !reservation.getKratosIdentityId().equals(identityId)) {
            throw new IdentitySyncException("Registration identity does not match its reservation.");
        }
        var user = create(
                reservation.getUsername(),
                reservation.getEmail(),
                reservation.getDateOfBirth(),
                reservation.getId(),
                identityId);
        userRepository.flush();
        registrationRepository.delete(reservation);
        return user;
    }

    @Transactional
    public int reconcileAbandonedRegistrations() {
        int released = 0;
        for (var candidate : registrationRepository.findTop50ByKratosIdentityIdIsNullAndExpiresAtBeforeOrderByExpiresAt(
                Instant.now().minus(Duration.ofHours(1)))) {
            lockRegistrationClaims(candidate.getUsername(), candidate.getEmail());
            var current = registrationRepository.findLockedById(candidate.getId());
            if (current.isEmpty() || current.get().getKratosIdentityId() != null) {
                continue;
            }
            var reservation = current.get();
            var identityId = kratosClient.findRegistrationIdentity(reservation.getId());
            if (identityId == null) {
                registrationRepository.delete(reservation);
                released++;
            } else {
                requireMatchingRegistration(reservation, kratosClient.getNativeRegistrationIdentity(identityId, false));
                reservation.setKratosIdentityId(identityId);
            }
        }
        return released;
    }

    private void requireMatchingRegistration(
            UserRegistrationReservation reservation, KratosClient.NativeRegistrationIdentity identity) {
        if (!reservation.getUsername().equals(identity.username())
                || !reservation.getEmail().equals(identity.email())
                || !reservation.getDateOfBirth().equals(identity.dateOfBirth())) {
            throw new IdentitySyncException("Identity traits do not match their registration reservation.");
        }
    }

    @Transactional
    public User provision(String username, String email, LocalDate dateOfBirth, UserRole role) {
        Optional<User> existing = userRepository.findByUsername(username);
        if (existing.isPresent()) {
            User user = userRepository.findLockedById(existing.get().getId()).orElseThrow();
            requireActive(user);
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
    public User provisionBootstrapAdmin(String username, String email, LocalDate dateOfBirth) {
        if (dateOfBirth == null) {
            throw new IllegalArgumentException("Date of birth is required");
        }
        User created = create(username, email, dateOfBirth);
        created.setRole(UserRole.ADMIN);
        try {
            return userRepository.saveAndFlush(created);
        } catch (DataIntegrityViolationException e) {
            throw new UserAlreadyExistsException(
                    String.format("User with username %s or email %s already exists", username, email));
        }
    }

    @Transactional
    public User updateProfile(Long id, String username, String email) {
        User user = userRepository
                .findLockedById(id)
                .orElseThrow(() -> new UserNotFoundException(String.format("User with id %s not found", id)));
        requireActive(user);

        lockRegistrationClaims(username, email);
        requireUnreserved(username, email, null);
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
        UUID identityId = deletionTransaction.execute(status -> {
            User user = userRepository
                    .findLockedById(id)
                    .orElseThrow(() -> new UserNotFoundException(String.format("User with id %s not found", id)));
            if (user.isDeleted()) {
                return null;
            }
            if (user.getDeletionIdentityId() == null) {
                UUID verifiedIdentity = kratosClient.findLinkedIdentity(id);
                if (verifiedIdentity == null) {
                    throw new IdentitySyncException("No verified canonical identity link found. Contact support.");
                }
                user.setDeletionIdentityId(verifiedIdentity);
                userRepository.saveAndFlush(user);
            }
            return user.getDeletionIdentityId();
        });
        if (identityId == null) {
            return;
        }

        // Commit the verified retry coordinate before irreversible upstream work.
        // Remove authentication first, then grants, so a retry after a DB/upstream
        // failure cannot leave a live password identity hidden behind success.
        kratosClient.deleteLinkedIdentity(identityId, id);
        hydraClient.revokeUser(id);

        deletionTransaction.executeWithoutResult(status -> {
            User user = userRepository.findLockedById(id).orElseThrow();
            if (user.isDeleted()) {
                return;
            }
            userRepository.deleteAddresses(id);
            userRepository.deletePaymentMethods(id);
            userRepository.deleteGuestCartMergeReceipts(id);
            userRepository.deleteCart(id);
            userRepository.deleteWishlist(id);
            userRepository.deleteRatings(id);
            String anonymous = "deleted-" + UUID.randomUUID();
            user.setUsername(anonymous);
            user.setEmail(anonymous + "@deleted.invalid");
            user.setDateOfBirth(LocalDate.of(1970, 1, 1));
            user.setRole(UserRole.USER);
            user.setDeleted(true);
            userRepository.saveAndFlush(user);
        });
    }

    @Transactional
    public User updateRole(Long id, UserRole role) {
        User user = userRepository
                .findLockedById(id)
                .orElseThrow(() -> new UserNotFoundException(String.format("User with id %s not found", id)));
        requireActive(user);
        user.setRole(role);
        return user;
    }

    private void requireActive(User user) {
        if (user.isDeleted() || user.getDeletionIdentityId() != null) {
            throw new IllegalArgumentException("Account deletion is in progress or completed. Contact support.");
        }
    }
}

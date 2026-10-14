package ee.bytecore.backend.services;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import ee.bytecore.backend.security.crypto.FieldCipher;
import ee.bytecore.backend.security.crypto.FieldEncryption;

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
        Stream.of(emailClaim(email), "username:" + username).sorted().forEach(registrationRepository::lockClaim);
    }

    /** Emails are unique case-insensitively, so their advisory lock key uses the normalized form. */
    private static String emailClaim(String email) {
        return "email:" + FieldCipher.normalizeEmail(email);
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

    public record RegistrationAvailability(boolean emailAvailable, boolean usernameAvailable) {}

    /** Mirrors reservation conflict rules without claiming identifiers. */
    @Transactional(readOnly = true)
    public RegistrationAvailability registrationAvailability(String username, String email) {
        validateRegistrationFields(username == null ? "unchecked" : username, email);
        boolean emailAvailable =
                registrationRepository.findByEmail(email).isEmpty() && !userRepository.existsByEmail(email);
        boolean usernameAvailable = username == null
                || (registrationRepository.findByUsername(username).isEmpty()
                        && !userRepository.existsByUsername(username));
        return new RegistrationAvailability(emailAvailable, usernameAvailable);
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
                revokeGrantsAfterCommit(user.getId());
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

    /**
     * Changes the canonical email of a native identity's user. The caller (the
     * auth bridge) has already proven possession of the address and updates
     * Kratos itself; this only owns Spring's canonical record.
     */
    @Transactional
    public User changeEmail(UUID kratosIdentityId, String email) {
        if (kratosIdentityId == null) {
            throw new IllegalArgumentException("Identity is required");
        }
        validateRegistrationFields("unchecked", email);
        User linked = userRepository
                .findByKratosIdentityId(kratosIdentityId)
                .orElseThrow(() -> new UserNotFoundException("User for identity not found"));
        User user = userRepository
                .findLockedById(linked.getId())
                .orElseThrow(() -> new UserNotFoundException("User for identity not found"));
        requireActive(user);
        if (user.getEmail().equals(email)) {
            return user;
        }
        if (Objects.equals(FieldCipher.normalizeEmail(user.getEmail()), FieldCipher.normalizeEmail(email))) {
            user.setEmail(email);
            return userRepository.saveAndFlush(user);
        }
        registrationRepository.lockClaim(emailClaim(email));
        if (registrationRepository.findByEmail(email).isPresent()) {
            throw new UserAlreadyExistsException("Account identifiers are reserved by a registration.");
        }
        if (userRepository.existsByEmail(email)) {
            throw new UserAlreadyExistsException("Account identifiers are already in use.");
        }
        user.setEmail(email);
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new UserAlreadyExistsException("Account identifiers are already in use.");
        }
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
            userRepository.deleteReviewVotes(id);
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
        return updateRole(null, id, role).user();
    }

    public record RoleChange(User user, UserRole previousRole) {}

    /**
     * Changes a user's role on behalf of {@code actorId}. An administrator cannot demote
     * themselves and the last active ADMIN can never be demoted. After commit the user's
     * existing grants are revoked, so the new role reaches their tokens on next sign-in.
     */
    @Transactional
    public RoleChange updateRole(Long actorId, Long id, UserRole role) {
        if (role == null) throw new IllegalArgumentException("Role is required");
        // Lock the admin set first (in id order) so concurrent demotions serialize without deadlocks.
        List<User> activeAdmins = role == UserRole.ADMIN ? List.of() : userRepository.findActiveAdminsForUpdate();
        User user = userRepository
                .findLockedById(id)
                .orElseThrow(() -> new UserNotFoundException(String.format("User with id %s not found", id)));
        requireActive(user);
        UserRole previous = user.getRole();
        if (previous == UserRole.ADMIN && role != UserRole.ADMIN) {
            if (Objects.equals(actorId, id))
                throw new IllegalArgumentException("You cannot remove your own ADMIN role");
            if (activeAdmins.size() <= 1)
                throw new IllegalArgumentException("Cannot remove the ADMIN role from the last active administrator");
        }
        user.setRole(role);
        revokeGrantsAfterCommit(id);
        return new RoleChange(user, previous);
    }

    /**
     * Role-bearing access/refresh tokens must not outlive a role assignment: once the
     * change commits, every Hydra grant and login session of the user is revoked
     * (same as account deletion), so the resource server's introspection rejects old
     * tokens and the next sign-in mints the current role. A failure surfaces to the
     * caller after the commit; repeating the assignment retries the revocation.
     */
    private void revokeGrantsAfterCommit(Long userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            hydraClient.revokeUser(userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    hydraClient.revokeUser(userId);
                } catch (RuntimeException e) {
                    throw new IdentitySyncException(
                            "Role updated, but existing sessions could not be revoked. Repeat the change to retry.", e);
                }
            }
        });
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<User> searchActive(String query, UserRole role, int page, int size) {
        if (page < 0) throw new IllegalArgumentException("Page must not be negative");
        if (size < 1 || size > 100) throw new IllegalArgumentException("Size must be between 1 and 100");
        String trimmed = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        if (trimmed.length() > 100) throw new IllegalArgumentException("Search query must be at most 100 characters");
        String escaped = trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        // Emails are encrypted at rest: they match exactly through the blind index, never by substring.
        String emailIndex = trimmed.contains("@") ? FieldEncryption.userEmailIndex(trimmed) : null;
        Long id = trimmed.matches("\\d{1,18}") ? Long.valueOf(trimmed) : null;
        return userRepository.searchActive(
                "%" + escaped + "%",
                emailIndex,
                id,
                role,
                org.springframework.data.domain.PageRequest.of(
                        page, size, org.springframework.data.domain.Sort.by("id")));
    }

    private void requireActive(User user) {
        if (user.isDeleted() || user.getDeletionIdentityId() != null) {
            throw new IllegalArgumentException("Account deletion is in progress or completed. Contact support.");
        }
    }
}

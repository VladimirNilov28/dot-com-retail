package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    UserRepository userRepository;

    @Mock
    UserRegistrationReservationRepository registrationRepository;

    @Mock
    KratosClient kratosClient;

    @Mock
    HydraClient hydraClient;

    @Mock
    PlatformTransactionManager transactionManager;

    @InjectMocks
    UserService userService;

    private User user;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient()
                .when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        user = User.create("test-user", "test@example.com", LocalDate.of(1995, 6, 15));
        user.setId(1L);
    }

    @Test
    void shouldFindUserByIdTest() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        Optional<User> found = userService.findById(1L);

        assertThat(found).contains(user);
    }

    @Test
    void shouldReturnEmptyWhenUserNotFoundByIdTest() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        Optional<User> found = userService.findById(999L);

        assertThat(found).isEmpty();
    }

    @Test
    void shouldCreateUserTest() {
        when(userRepository.existsByUsername("test-user")).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenReturn(user);

        User created = userService.create("test-user", "test@example.com", LocalDate.of(1995, 6, 15));

        assertThat(created).isEqualTo(user);
        verify(userRepository).save(any(User.class));
    }

    @Test
    void shouldThrowWhenCreatingUserWithDuplicateUsernameTest() {
        when(userRepository.existsByUsername("test-user")).thenReturn(true);

        assertThatThrownBy(() -> userService.create("test-user", "test@example.com", LocalDate.of(1995, 6, 15)))
                .isInstanceOf(UserAlreadyExistsException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldThrowWhenCreatingUserWithDuplicateEmailTest() {
        when(userRepository.existsByUsername("test-user")).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.create("test-user", "test@example.com", LocalDate.of(1995, 6, 15)))
                .isInstanceOf(UserAlreadyExistsException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldThrowWhenCreatingUserWithBlankUsernameTest() {
        assertThatThrownBy(() -> userService.create("", "blank-username@example.com", LocalDate.of(1995, 6, 15)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldThrowWhenCreatingUserWithWhitespaceOnlyUsernameTest() {
        assertThatThrownBy(
                        () -> userService.create("   ", "whitespace-username@example.com", LocalDate.of(1995, 6, 15)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldThrowWhenCreatingUserWithBlankEmailTest() {
        assertThatThrownBy(() -> userService.create("blank-email-user", "", LocalDate.of(1995, 6, 15)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldThrowWhenCreatingUserWithMalformedEmailTest() {
        assertThatThrownBy(() -> userService.create("malformed-email-user", "not-an-email", LocalDate.of(1995, 6, 15)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldProvisionNewUserWithGivenRoleTest() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());
        when(userRepository.existsByUsername("admin")).thenReturn(false);
        when(userRepository.existsByEmail("admin@bytecore.ee")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User provisioned =
                userService.provision("admin", "admin@bytecore.ee", LocalDate.of(2000, 1, 1), UserRole.ADMIN);

        assertThat(provisioned.getRole()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void shouldUpdateRoleWhenProvisioningExistingUserWithDifferentRoleTest() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(userRepository.findLockedById(1L)).thenReturn(Optional.of(user));

        User provisioned =
                userService.provision("admin", "admin@bytecore.ee", LocalDate.of(2000, 1, 1), UserRole.ADMIN);

        assertThat(provisioned.getRole()).isEqualTo(UserRole.ADMIN);
        verify(userRepository).save(user);
    }

    @Test
    void shouldNotResaveWhenProvisioningExistingUserWithSameRoleTest() {
        user.setRole(UserRole.ADMIN);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(userRepository.findLockedById(1L)).thenReturn(Optional.of(user));

        userService.provision("admin", "admin@bytecore.ee", LocalDate.of(2000, 1, 1), UserRole.ADMIN);

        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldRemovePersonalDataWithoutDeletingHistoricalOwnerTest() {
        stubDeletion();

        userService.deleteById(1L);

        assertThat(user.getEmail()).isNotEqualTo("test@example.com");
        assertThat(user.getUsername()).isNotEqualTo("test-user");
        assertThat(user.getDateOfBirth()).isNotEqualTo(LocalDate.of(1995, 6, 15));
        verify(userRepository, never()).deleteById(1L);
    }

    @Test
    void shouldDeleteUserByIdTest() {
        UUID identityId = stubDeletion();

        userService.deleteById(1L);

        org.mockito.InOrder ordered = org.mockito.Mockito.inOrder(userRepository, kratosClient, hydraClient);
        ordered.verify(userRepository).findLockedById(1L);
        ordered.verify(kratosClient).findLinkedIdentity(1L);
        ordered.verify(userRepository).saveAndFlush(user);
        ordered.verify(kratosClient).deleteLinkedIdentity(identityId, 1L);
        ordered.verify(hydraClient).revokeUser(1L);
        ordered.verify(userRepository).findLockedById(1L);
        verify(userRepository).deleteAddresses(1L);
        verify(userRepository).deletePaymentMethods(1L);
        verify(userRepository).deleteCart(1L);
        verify(userRepository).deleteWishlist(1L);
        assertThat(user.isDeleted()).isTrue();
        assertThat(user.getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    void shouldThrowWhenDeletingMissingUserTest() {
        when(userRepository.findLockedById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deleteById(999L)).isInstanceOf(UserNotFoundException.class);

        verify(userRepository, never()).deleteById(any());
    }

    @Test
    void shouldFailClosedWhenKratosDeletionFailsTest() {
        UUID identityId = stubDeletion();
        org.mockito.Mockito.doThrow(new IdentitySyncException("Retry deletion"))
                .when(kratosClient)
                .deleteLinkedIdentity(identityId, 1L);
        assertThatThrownBy(() -> userService.deleteById(1L)).isInstanceOf(IdentitySyncException.class);
        verify(hydraClient, never()).revokeUser(any());
        verify(userRepository, never()).deleteAddresses(any());
        assertThat(user.getEmail()).isEqualTo("test@example.com");
        assertThat(user.isDeleted()).isFalse();
    }

    @Test
    void shouldRetryAfterHydraFailureUsingDurableIdentityCoordinateTest() {
        UUID identityId = stubDeletion();
        org.mockito.Mockito.doThrow(new IdentitySyncException("Retry deletion"))
                .doNothing()
                .when(hydraClient)
                .revokeUser(1L);
        assertThatThrownBy(() -> userService.deleteById(1L)).isInstanceOf(IdentitySyncException.class);
        assertThat(user.isDeleted()).isFalse();
        assertThat(user.getEmail()).isEqualTo("test@example.com");
        userService.deleteById(1L);
        verify(kratosClient).findLinkedIdentity(1L);
        verify(kratosClient, org.mockito.Mockito.times(2)).deleteLinkedIdentity(identityId, 1L);
        assertThat(user.isDeleted()).isTrue();
    }

    @Test
    void shouldRejectUnverifiedIdentityBeforeAnyRevocationTest() {
        when(userRepository.findLockedById(1L)).thenReturn(Optional.of(user));
        when(kratosClient.findLinkedIdentity(1L)).thenThrow(new IdentitySyncException("No canonical link"));
        assertThatThrownBy(() -> userService.deleteById(1L)).isInstanceOf(IdentitySyncException.class);
        verify(kratosClient, never()).deleteLinkedIdentity(any(), any());
        verify(hydraClient, never()).revokeUser(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldTreatCompletedDeletionAsIdempotentTest() {
        user.setDeleted(true);
        when(userRepository.findLockedById(1L)).thenReturn(Optional.of(user));
        userService.deleteById(1L);
        org.mockito.Mockito.verifyNoInteractions(kratosClient, hydraClient);
        verify(userRepository, never()).deleteById(any());
    }

    private UUID stubDeletion() {
        UUID identityId = UUID.fromString("00000000-0000-4000-8000-000000000001");
        when(userRepository.findLockedById(1L)).thenReturn(Optional.of(user));
        when(kratosClient.findLinkedIdentity(1L)).thenReturn(identityId);
        return identityId;
    }

    @Test
    void shouldUpdateUserRoleTest() {
        when(userRepository.findLockedById(1L)).thenReturn(Optional.of(user));

        User updated = userService.updateRole(1L, UserRole.SUPPORT);

        assertThat(updated.getRole()).isEqualTo(UserRole.SUPPORT);
    }

    @Test
    void shouldThrowWhenUpdatingRoleOfMissingUserTest() {
        when(userRepository.findLockedById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateRole(999L, UserRole.SUPPORT))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void shouldSyncEmailToKratosWhenUpdatingProfileTest() {
        when(userRepository.findLockedById(1L)).thenReturn(Optional.of(user));

        User updated = userService.updateProfile(1L, "test-user", "new@example.com");

        verify(kratosClient).updateIdentityEmail("test@example.com", "new@example.com");
        assertThat(updated.getEmail()).isEqualTo("new@example.com");
    }

    @Test
    void shouldNotSyncToKratosWhenEmailUnchangedTest() {
        when(userRepository.findLockedById(1L)).thenReturn(Optional.of(user));

        userService.updateProfile(1L, "renamed-user", "test@example.com");

        verify(kratosClient, never()).updateIdentityEmail(any(), any());
    }

    @Test
    void shouldFailProfileUpdateWhenKratosSyncFailsTest() {
        when(userRepository.findLockedById(1L)).thenReturn(Optional.of(user));
        org.mockito.Mockito.doThrow(new IdentitySyncException("Kratos unreachable"))
                .when(kratosClient)
                .updateIdentityEmail("test@example.com", "new@example.com");

        assertThatThrownBy(() -> userService.updateProfile(1L, "test-user", "new@example.com"))
                .isInstanceOf(IdentitySyncException.class);

        assertThat(user.getEmail()).isEqualTo("test@example.com");
    }

    @Test
    void shouldFinalizeVerifiedNativeCustomerWithUserRoleTest() {
        var claim = UserRegistrationReservation.create(
                UUID.randomUUID(),
                "new-customer",
                "new-customer@example.com",
                LocalDate.of(1998, 3, 20),
                Instant.now().plusSeconds(600));
        var identityId = UUID.randomUUID();
        when(kratosClient.getNativeRegistrationIdentity(identityId, true))
                .thenReturn(new KratosClient.NativeRegistrationIdentity(
                        identityId,
                        claim.getId(),
                        claim.getUsername(),
                        claim.getEmail(),
                        claim.getDateOfBirth(),
                        null));
        when(registrationRepository.findLockedById(claim.getId())).thenReturn(Optional.of(claim));
        when(registrationRepository.findByUsername(claim.getUsername())).thenReturn(Optional.of(claim));
        when(registrationRepository.findByEmail(claim.getEmail())).thenReturn(Optional.of(claim));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(7L);
            return saved;
        });
        User registered = userService.finalizeRegistration(identityId);
        assertThat(registered.getRole()).isEqualTo(UserRole.USER);
        assertThat(registered.getUsername()).isEqualTo("new-customer");
        assertThat(registered.getKratosIdentityId()).isEqualTo(identityId);
        verify(registrationRepository).delete(claim);
        verify(kratosClient, never()).createIdentity(any(), any(), any());
    }

    @Test
    void shouldRejectReservationForExistingCanonicalUsernameTest() {
        when(userRepository.existsByUsername("new-customer")).thenReturn(true);
        assertThatThrownBy(() -> userService.reserveRegistration(
                        UUID.randomUUID(),
                        "new-customer",
                        "new-customer@example.com",
                        LocalDate.of(1998, 3, 20),
                        Instant.now().plusSeconds(600)))
                .isInstanceOf(UserAlreadyExistsException.class);
        verify(userRepository, never()).save(any());
        verify(registrationRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldRejectReservationForExistingCanonicalEmailTest() {
        when(userRepository.existsByEmail("new-customer@example.com")).thenReturn(true);
        assertThatThrownBy(() -> userService.reserveRegistration(
                        UUID.randomUUID(),
                        "new-customer",
                        "new-customer@example.com",
                        LocalDate.of(1998, 3, 20),
                        Instant.now().plusSeconds(600)))
                .isInstanceOf(UserAlreadyExistsException.class);
        verify(userRepository, never()).save(any());
        verify(registrationRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldRejectRetiredDirectRegistrationWithoutProcessingCredentialsTest() {
        assertThatThrownBy(() -> userService.registerCustomer(
                        "new-customer", "new-customer@example.com", "   ", LocalDate.of(1998, 3, 20)))
                .isInstanceOf(UnsupportedOperationException.class);
        org.mockito.Mockito.verifyNoInteractions(userRepository, registrationRepository, kratosClient);
    }

    @Test
    void shouldRejectNativeRegistrationWithMissingDateOfBirthTest() {
        assertThatThrownBy(() -> userService.reserveRegistration(
                        UUID.randomUUID(),
                        "new-customer",
                        "new-customer@example.com",
                        null,
                        Instant.now().plusSeconds(600)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(userRepository, never()).save(any());
        verify(registrationRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldRejectNativeRegistrationWithOverlongUsernameTest() {
        assertThatThrownBy(() -> userService.reserveRegistration(
                        UUID.randomUUID(),
                        "a".repeat(256),
                        "new-customer@example.com",
                        LocalDate.of(1998, 3, 20),
                        Instant.now().plusSeconds(600)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldNotCreateCanonicalAccountWhenIdentityValidationFailsTest() {
        var identityId = UUID.randomUUID();
        when(kratosClient.getNativeRegistrationIdentity(identityId, true))
                .thenThrow(new IdentitySyncException("Identity unavailable"));
        assertThatThrownBy(() -> userService.finalizeRegistration(identityId))
                .isInstanceOf(IdentitySyncException.class);
        org.mockito.Mockito.verifyNoInteractions(userRepository, registrationRepository);
    }

    @Test
    void shouldTranslateCanonicalCreateConstraintConflictTest() {
        when(userRepository.existsByUsername("new-customer")).thenReturn(false);
        when(userRepository.existsByEmail("new-customer@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));
        assertThatThrownBy(
                        () -> userService.create("new-customer", "new-customer@example.com", LocalDate.of(1998, 3, 20)))
                .isInstanceOf(UserAlreadyExistsException.class)
                .satisfies(error -> assertThat(error.getMessage())
                        .as("must not leak the raw SQL/constraint exception to the client")
                        .doesNotContain("DataIntegrityViolationException")
                        .doesNotContain("duplicate key"));

        verify(kratosClient, never()).createIdentity(any(), any(), any());
    }

    @Test
    void shouldPreventInternalWriterFromTakingReservedIdentifiersTest() {
        var claim = UserRegistrationReservation.create(
                UUID.randomUUID(),
                user.getUsername(),
                user.getEmail(),
                user.getDateOfBirth(),
                Instant.now().plusSeconds(600));
        when(registrationRepository.findByUsername(user.getUsername())).thenReturn(Optional.of(claim));
        assertThatThrownBy(() -> userService.create(user.getUsername(), user.getEmail(), user.getDateOfBirth()))
                .isInstanceOf(UserAlreadyExistsException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldRecoverAlreadyCommittedNativeRegistrationIdempotentlyTest() {
        var identityId = UUID.randomUUID();
        user.setKratosIdentityId(identityId);
        when(kratosClient.getNativeRegistrationIdentity(identityId, true))
                .thenReturn(new KratosClient.NativeRegistrationIdentity(
                        identityId,
                        UUID.randomUUID(),
                        user.getUsername(),
                        user.getEmail(),
                        user.getDateOfBirth(),
                        user.getId()));
        when(userRepository.findByKratosIdentityId(identityId)).thenReturn(Optional.of(user));
        assertThat(userService.finalizeRegistration(identityId)).isSameAs(user);
        verify(userRepository, never()).save(any());
        verify(registrationRepository, never()).delete(any());
    }
}

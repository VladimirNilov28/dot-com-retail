package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;

import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.enums.UserRole;
import ee.bytecore.backend.exceptions.IdentitySyncException;
import ee.bytecore.backend.exceptions.UserAlreadyExistsException;
import ee.bytecore.backend.exceptions.UserNotFoundException;
import ee.bytecore.backend.integration.KratosClient;
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
    KratosClient kratosClient;

    @InjectMocks
    UserService userService;

    private User user;

    @BeforeEach
    void setUp() {
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

        User provisioned =
                userService.provision("admin", "admin@bytecore.ee", LocalDate.of(2000, 1, 1), UserRole.ADMIN);

        assertThat(provisioned.getRole()).isEqualTo(UserRole.ADMIN);
        verify(userRepository).save(user);
    }

    @Test
    void shouldNotResaveWhenProvisioningExistingUserWithSameRoleTest() {
        user.setRole(UserRole.ADMIN);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));

        userService.provision("admin", "admin@bytecore.ee", LocalDate.of(2000, 1, 1), UserRole.ADMIN);

        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldDeleteUserByIdTest() {
        when(userRepository.existsById(1L)).thenReturn(true);

        userService.deleteById(1L);

        verify(userRepository).deleteById(1L);
    }

    @Test
    void shouldThrowWhenDeletingMissingUserTest() {
        when(userRepository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> userService.deleteById(999L)).isInstanceOf(UserNotFoundException.class);

        verify(userRepository, never()).deleteById(any());
    }

    @Test
    void shouldTranslateForeignKeyViolationWhenDeletingUserWithOrdersTest() {
        when(userRepository.existsById(1L)).thenReturn(true);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("fk_order_user"))
                .when(userRepository)
                .deleteById(1L);

        assertThatThrownBy(() -> userService.deleteById(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(error -> assertThat(error.getMessage())
                        .as("must not leak the raw SQL/constraint exception to the client")
                        .doesNotContain("DataIntegrityViolationException")
                        .doesNotContain("fk_order_user"));
    }

    @Test
    void shouldUpdateUserRoleTest() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        User updated = userService.updateRole(1L, UserRole.SUPPORT);

        assertThat(updated.getRole()).isEqualTo(UserRole.SUPPORT);
    }

    @Test
    void shouldThrowWhenUpdatingRoleOfMissingUserTest() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateRole(999L, UserRole.SUPPORT))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void shouldSyncEmailToKratosWhenUpdatingProfileTest() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        User updated = userService.updateProfile(1L, "test-user", "new@example.com");

        verify(kratosClient).updateIdentityEmail("test@example.com", "new@example.com");
        assertThat(updated.getEmail()).isEqualTo("new@example.com");
    }

    @Test
    void shouldNotSyncToKratosWhenEmailUnchangedTest() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        userService.updateProfile(1L, "renamed-user", "test@example.com");

        verify(kratosClient, never()).updateIdentityEmail(any(), any());
    }

    @Test
    void shouldFailProfileUpdateWhenKratosSyncFailsTest() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        org.mockito.Mockito.doThrow(new IdentitySyncException("Kratos unreachable"))
                .when(kratosClient)
                .updateIdentityEmail("test@example.com", "new@example.com");

        assertThatThrownBy(() -> userService.updateProfile(1L, "test-user", "new@example.com"))
                .isInstanceOf(IdentitySyncException.class);

        assertThat(user.getEmail()).isEqualTo("test@example.com");
    }

    @Test
    void shouldRegisterCustomerWithUserRoleTest() {
        when(userRepository.existsByUsername("new-customer")).thenReturn(false);
        when(userRepository.existsByEmail("new-customer@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(7L);
            return saved;
        });

        User registered = userService.registerCustomer(
                "new-customer", "new-customer@example.com", "s3cret-test-pw", LocalDate.of(1998, 3, 20));

        assertThat(registered.getRole()).isEqualTo(UserRole.USER);
        assertThat(registered.getUsername()).isEqualTo("new-customer");
        verify(kratosClient).createIdentity("new-customer@example.com", "s3cret-test-pw", 7L);
    }

    @Test
    void shouldThrowConflictWhenRegisteringDuplicateUsernameTest() {
        when(userRepository.existsByUsername("new-customer")).thenReturn(true);

        assertThatThrownBy(() -> userService.registerCustomer(
                        "new-customer", "new-customer@example.com", "s3cret-test-pw", LocalDate.of(1998, 3, 20)))
                .isInstanceOf(UserAlreadyExistsException.class);

        verify(userRepository, never()).save(any());
        verify(kratosClient, never()).createIdentity(any(), any(), any());
    }

    @Test
    void shouldThrowConflictWhenRegisteringDuplicateEmailTest() {
        when(userRepository.existsByUsername("new-customer")).thenReturn(false);
        when(userRepository.existsByEmail("new-customer@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.registerCustomer(
                        "new-customer", "new-customer@example.com", "s3cret-test-pw", LocalDate.of(1998, 3, 20)))
                .isInstanceOf(UserAlreadyExistsException.class);

        verify(userRepository, never()).save(any());
        verify(kratosClient, never()).createIdentity(any(), any(), any());
    }

    @Test
    void shouldThrowWhenRegisteringWithBlankPasswordTest() {
        assertThatThrownBy(() -> userService.registerCustomer(
                        "new-customer", "new-customer@example.com", "   ", LocalDate.of(1998, 3, 20)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(userRepository, never()).save(any());
        verify(kratosClient, never()).createIdentity(any(), any(), any());
    }

    @Test
    void shouldThrowWhenRegisteringWithNullDateOfBirthTest() {
        assertThatThrownBy(() -> userService.registerCustomer(
                        "new-customer", "new-customer@example.com", "s3cret-test-pw", null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldThrowWhenRegisteringWithOverlongUsernameTest() {
        String tooLong = "a".repeat(256);

        assertThatThrownBy(() -> userService.registerCustomer(
                        tooLong, "new-customer@example.com", "s3cret-test-pw", LocalDate.of(1998, 3, 20)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldCompensateByDeletingSpringUserWhenKratosIdentityCreationFailsTest() {
        when(userRepository.existsByUsername("new-customer")).thenReturn(false);
        when(userRepository.existsByEmail("new-customer@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(7L);
            return saved;
        });
        org.mockito.Mockito.doThrow(new IdentitySyncException("Kratos unreachable"))
                .when(kratosClient)
                .createIdentity("new-customer@example.com", "s3cret-test-pw", 7L);

        assertThatThrownBy(() -> userService.registerCustomer(
                        "new-customer", "new-customer@example.com", "s3cret-test-pw", LocalDate.of(1998, 3, 20)))
                .isInstanceOf(IdentitySyncException.class);

        verify(userRepository).deleteById(7L);
    }

    @Test
    void shouldTranslateRaceConditionDuringRegistrationToConflictTest() {
        when(userRepository.existsByUsername("new-customer")).thenReturn(false);
        when(userRepository.existsByEmail("new-customer@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> userService.registerCustomer(
                        "new-customer", "new-customer@example.com", "s3cret-test-pw", LocalDate.of(1998, 3, 20)))
                .isInstanceOf(UserAlreadyExistsException.class)
                .satisfies(error -> assertThat(error.getMessage())
                        .as("must not leak the raw SQL/constraint exception to the client")
                        .doesNotContain("DataIntegrityViolationException")
                        .doesNotContain("duplicate key"));

        verify(kratosClient, never()).createIdentity(any(), any(), any());
    }
}

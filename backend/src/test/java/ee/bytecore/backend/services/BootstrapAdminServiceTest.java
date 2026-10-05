package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import org.springframework.dao.DataIntegrityViolationException;

import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.enums.UserRole;
import ee.bytecore.backend.exceptions.UserAlreadyExistsException;
import ee.bytecore.backend.integration.KratosClient;
import ee.bytecore.backend.repositories.user.UserRepository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class BootstrapAdminServiceTest {

    private static final LocalDate DOB = LocalDate.of(2000, 1, 1);

    @Mock
    UserRepository userRepository;

    @Mock
    KratosClient kratosClient;

    @InjectMocks
    UserService userService;

    @Test
    void shouldCreateOnlyNewAdminAndFlushBeforeReturningTest() {
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User user = userService.provisionBootstrapAdmin("bootstrap-admin", "bootstrap-admin@example.com", DOB);

        assertThat(user.getUsername()).isEqualTo("bootstrap-admin");
        assertThat(user.getEmail()).isEqualTo("bootstrap-admin@example.com");
        assertThat(user.getDateOfBirth()).isEqualTo(DOB);
        assertThat(user.getRole()).isEqualTo(UserRole.ADMIN);
        verify(userRepository).saveAndFlush(user);
        verify(userRepository, never()).findByUsername(any());
        verifyNoInteractions(kratosClient);
    }

    @Test
    void shouldRejectAnyExistingUsernameRatherThanUpsertTest() {
        when(userRepository.existsByUsername("bootstrap-admin")).thenReturn(true);

        assertThatThrownBy(() ->
                        userService.provisionBootstrapAdmin("bootstrap-admin", "bootstrap-admin@example.com", DOB))
                .isInstanceOf(UserAlreadyExistsException.class);

        verify(userRepository, never()).findByUsername(any());
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).saveAndFlush(any());
        verifyNoInteractions(kratosClient);
    }

    @Test
    void shouldRejectAnyExistingEmailRatherThanUpsertTest() {
        when(userRepository.existsByEmail("bootstrap-admin@example.com")).thenReturn(true);

        assertThatThrownBy(() ->
                        userService.provisionBootstrapAdmin("bootstrap-admin", "bootstrap-admin@example.com", DOB))
                .isInstanceOf(UserAlreadyExistsException.class);

        verify(userRepository, never()).save(any());
        verify(userRepository, never()).saveAndFlush(any());
        verifyNoInteractions(kratosClient);
    }

    @Test
    void shouldTranslateConcurrentUniqueInsertFailureToConflictTest() {
        when(userRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("private-sql"));

        assertThatThrownBy(() ->
                        userService.provisionBootstrapAdmin("bootstrap-admin", "bootstrap-admin@example.com", DOB))
                .isInstanceOf(UserAlreadyExistsException.class)
                .hasMessage("User with username bootstrap-admin or email bootstrap-admin@example.com already exists");

        verify(userRepository, never()).saveAndFlush(any());
        verifyNoInteractions(kratosClient);
    }

    @Test
    void shouldTranslateFlushFailureToConflictTest() {
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("private-sql"));

        assertThatThrownBy(() ->
                        userService.provisionBootstrapAdmin("bootstrap-admin", "bootstrap-admin@example.com", DOB))
                .isInstanceOf(UserAlreadyExistsException.class)
                .hasMessage("User with username bootstrap-admin or email bootstrap-admin@example.com already exists");

        verifyNoInteractions(kratosClient);
    }

    @Test
    void shouldRequireDateOfBirthBeforePersistenceTest() {
        assertThatThrownBy(() ->
                        userService.provisionBootstrapAdmin("bootstrap-admin", "bootstrap-admin@example.com", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Date of birth is required");

        verifyNoInteractions(userRepository, kratosClient);
    }

    @Test
    void shouldRetainExistingCreateValidationTest() {
        assertThatThrownBy(() -> userService.provisionBootstrapAdmin("", "bootstrap-admin@example.com", DOB))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> userService.provisionBootstrapAdmin("bootstrap-admin", "invalid", DOB))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(userRepository, kratosClient);
    }
}

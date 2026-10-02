package ee.bytecore.backend.controller;

import java.time.LocalDate;

/**
 * Public self-registration request. Deliberately has no {@code role} (or any
 * other privileged/internal) field — Spring Boot's default Jackson
 * configuration silently ignores unknown JSON properties, so a client
 * sending e.g. {@code "role":"ADMIN"} has no effect; role is always forced to
 * USER by {@link ee.bytecore.backend.services.UserService#registerCustomer}.
 * The password is forwarded to Kratos only and is never persisted here.
 */
public record RegisterUserRequest(String username, String email, String password, LocalDate dateOfBirth) {}

package ee.bytecore.backend.controller;

import java.time.LocalDate;

public record BootstrapAdminRequest(String username, String email, LocalDate dateOfBirth) {}

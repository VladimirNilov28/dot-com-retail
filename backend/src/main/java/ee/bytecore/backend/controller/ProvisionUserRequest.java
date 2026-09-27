package ee.bytecore.backend.controller;

import java.time.LocalDate;

import ee.bytecore.backend.enums.UserRole;

public record ProvisionUserRequest(String username, String email, LocalDate dateOfBirth, UserRole role) {}

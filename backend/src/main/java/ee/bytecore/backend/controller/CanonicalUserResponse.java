package ee.bytecore.backend.controller;

import ee.bytecore.backend.entities.user.User;

public record CanonicalUserResponse(Long id, String username, String email, String role) {
    public static CanonicalUserResponse from(User user) {
        return new CanonicalUserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole().name());
    }
}

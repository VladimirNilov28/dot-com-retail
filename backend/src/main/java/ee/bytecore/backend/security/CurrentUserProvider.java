package ee.bytecore.backend.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the canonical Spring User id from the authenticated principal's
 * name (JWT {@code sub}). Centralized here so DataFetchers don't each
 * re-implement SecurityContextHolder access and non-numeric-subject handling.
 */
@Component
public class CurrentUserProvider {

    public Long getCurrentUserId() {
        String sub = SecurityContextHolder.getContext().getAuthentication().getName();
        try {
            return Long.valueOf(sub);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

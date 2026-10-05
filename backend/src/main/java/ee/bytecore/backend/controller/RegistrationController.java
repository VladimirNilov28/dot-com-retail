package ee.bytecore.backend.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ee.bytecore.backend.services.UserService;

/**
 * The one public, unauthenticated entry point for normal end-user
 * registration (see SecurityConfig, which permits only this path
 * unauthenticated). Every other REST/GraphQL operation remains protected.
 */
@RestController
@RequestMapping("/auth")
public class RegistrationController {

    private final UserService userService;

    public RegistrationController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public CanonicalUserResponse register(@RequestBody RegisterUserRequest request) {
        var user = userService.registerCustomer(
                request.username(), request.email(), request.password(), request.dateOfBirth());
        return CanonicalUserResponse.from(user);
    }
}

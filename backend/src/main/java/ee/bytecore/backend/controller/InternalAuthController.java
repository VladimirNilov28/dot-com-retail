package ee.bytecore.backend.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ee.bytecore.backend.exceptions.UserNotFoundException;
import ee.bytecore.backend.services.UserService;

@RestController
@RequestMapping("/internal/users")
public class InternalAuthController {

    private final UserService userService;

    public InternalAuthController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_internal:provision-user')")
    public CanonicalUserResponse resolve(@PathVariable Long id) {
        return userService
                .findById(id)
                .map(CanonicalUserResponse::from)
                .orElseThrow(() -> new UserNotFoundException(String.format("User with id %s not found", id)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_internal:provision-user')")
    public CanonicalUserResponse provision(@RequestBody ProvisionUserRequest request) {
        var user = userService.provision(request.username(), request.email(), request.dateOfBirth(), request.role());
        return CanonicalUserResponse.from(user);
    }
}

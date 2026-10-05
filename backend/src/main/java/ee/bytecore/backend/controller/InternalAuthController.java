package ee.bytecore.backend.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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
    private final String machineClientId;

    public InternalAuthController(
            UserService userService,
            @Value("${oauth.service.client-id:oauth-service-internal}") String machineClientId) {
        this.userService = userService;
        this.machineClientId = machineClientId;
    }

    @GetMapping("/{id}")
    @PreAuthorize(
            "hasAuthority('SCOPE_internal:provision-user') and @internalAuthController.isMachineCaller(authentication)")
    public CanonicalUserResponse resolve(@PathVariable Long id) {
        return userService
                .findById(id)
                .map(CanonicalUserResponse::from)
                .orElseThrow(() -> new UserNotFoundException(String.format("User with id %s not found", id)));
    }

    @PostMapping
    @PreAuthorize(
            "hasAuthority('SCOPE_internal:provision-user') and @internalAuthController.isMachineCaller(authentication)")
    public CanonicalUserResponse provision(@RequestBody ProvisionUserRequest request) {
        var user = userService.provision(request.username(), request.email(), request.dateOfBirth(), request.role());
        return CanonicalUserResponse.from(user);
    }

    @PostMapping("/bootstrap-admin")
    @PreAuthorize(
            "hasAuthority('SCOPE_internal:provision-user') and @internalAuthController.isMachineCaller(authentication)")
    public CanonicalUserResponse bootstrapAdmin(@RequestBody BootstrapAdminRequest request) {
        return CanonicalUserResponse.from(
                userService.provisionBootstrapAdmin(request.username(), request.email(), request.dateOfBirth()));
    }

    public boolean isMachineCaller(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken jwt
                && machineClientId.equals(jwt.getToken().getSubject())
                && machineClientId.equals(jwt.getToken().getClaimAsString("client_id"));
    }
}

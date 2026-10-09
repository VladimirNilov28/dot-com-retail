package ee.bytecore.backend.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class RegistrationController {

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register() {
        return ResponseEntity.status(HttpStatus.GONE)
                .header("Cache-Control", "no-store")
                .body(Map.of("error", "registration_retired", "message", "Use storefront registration."));
    }
}

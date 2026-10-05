package ee.bytecore.backend.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import org.junit.jupiter.api.Test;

@WebMvcTest(HealthEndpointSecurityTest.ProbeController.class)
@Import({SecurityConfig.class, HealthEndpointSecurityTest.ProbeController.class})
class HealthEndpointSecurityTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void shouldAllowOnlyPublicGetHealthProbesTest() throws Exception {
        for (String path :
                new String[] {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            mockMvc.perform(get(path)).andExpect(status().isOk());
            mockMvc.perform(post(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void shouldKeepOtherActuatorAndInternalEndpointsProtectedTest() throws Exception {
        for (String path : new String[] {"/actuator/env", "/actuator/health/db", "/internal/users"}) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping({
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness",
            "/actuator/health/db",
            "/actuator/env",
            "/internal/users"
        })
        String probe() {
            return "UP";
        }
    }
}

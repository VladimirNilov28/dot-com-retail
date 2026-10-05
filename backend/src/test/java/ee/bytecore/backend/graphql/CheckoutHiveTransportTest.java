package ee.bytecore.backend.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/** Runs the inherited HTTP contracts through actual Hive, not an HTTP proxy fixture. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckoutHiveTransportTest extends CheckoutGraphQlIntegrationTest {
    private GenericContainer<?> router;

    @BeforeAll
    void composeAndStartRouter() throws Exception {
        configureJwt();
        ProcessBuilder composition = new ProcessBuilder("../infrastructure/hive/scripts/compose-supergraph.sh")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT);
        composition.environment().put("SPRING_GRAPHQL_URL", "http://localhost:" + port + "/graphql");
        composition.environment().put("HIVE_BEARER_TOKEN", "1/order-read");
        Process process = composition.start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Checkout schema composition timed out");
        }
        assertThat(process.exitValue()).as("Runtime SDL composition").isZero();
        Testcontainers.exposeHostPorts(port);
        String graph = Files.readString(Path.of("../infrastructure/hive/supergraph.graphql"));
        String routed = graph.replace(
                "http://host.docker.internal:8080/graphql", "http://host.testcontainers.internal:" + port + "/graphql");
        assertThat(routed).isNotEqualTo(graph);
        router = new GenericContainer<>("ghcr.io/graphql-hive/router:0.2.19")
                .withExposedPorts(4000)
                .withCopyToContainer(Transferable.of(routed), "/app/supergraph.graphql")
                .withCopyToContainer(
                        Transferable.of(Files.readString(Path.of("../infrastructure/hive/router.config.yaml"))),
                        "/app/router.config.yaml")
                .waitingFor(Wait.forHttp("/readiness").forPort(4000));
        router.start();
    }

    @Override
    URI endpoint() {
        return URI.create("http://" + router.getHost() + ":" + router.getMappedPort(4000) + "/graphql");
    }

    @Override
    int subgraphRejectionStatus(int springStatus) {
        // Hive wraps subgraph HTTP failures in GraphQL errors at HTTP 200.
        return 200;
    }

    @AfterAll
    void stopRouter() {
        if (router != null) router.close();
    }
}

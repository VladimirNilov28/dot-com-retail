package ee.bytecore.backend.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class RateLimitFilterTest {

    private final RateLimitFilter filter = new RateLimitFilter(
            new RateLimitProperties(),
            mock(PostgresRateLimiter.class),
            new ClientAddressResolver(List.of(), "X-Forwarded-For"),
            JsonMapper.builder().build());

    private static byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void shouldCollectRootFieldsOfSelectedOperationIncludingFragmentsTest() {
        String body =
                """
                {"operationName":"Place","query":"mutation Place { ...F a: createOrder { id } } fragment F on Mutation { ... on Mutation { submitContactMessage { accepted } } } query Other { products { id } }"}
                """;

        assertThat(filter.rootFields(json(body))).containsExactlyInAnyOrder("createOrder", "submitContactMessage");
    }

    @Test
    void shouldCollectAllOperationsWhenNoneIsSelectedTest() {
        String body = "{\"query\":\"query A { products { id } } mutation B { rateProduct { id } }\"}";

        assertThat(filter.rootFields(json(body))).containsExactlyInAnyOrder("products", "rateProduct");
    }

    @Test
    void shouldReturnNoRootsForUnparsableBodiesTest() {
        assertThat(filter.rootFields(json("not json"))).isEmpty();
        assertThat(filter.rootFields(json("{\"query\":\"mutation {\"}"))).isEmpty();
        assertThat(filter.rootFields(json("{\"query\":42}"))).isEmpty();
    }

    @Test
    void shouldBoundBucketKeyLengthTest() {
        assertThat(RateLimitFilter.bucketKey("general", "ip:198.51.100.1")).isEqualTo("general|ip:198.51.100.1");
        String key = RateLimitFilter.bucketKey("general", "sub:" + "x".repeat(400));
        assertThat(key).startsWith("general|h:").hasSize("general|h:".length() + 64);
    }
}

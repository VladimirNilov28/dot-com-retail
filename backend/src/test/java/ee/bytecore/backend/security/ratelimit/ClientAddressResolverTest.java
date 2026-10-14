package ee.bytecore.backend.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.springframework.mock.web.MockHttpServletRequest;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ClientAddressResolverTest {

    private final ClientAddressResolver resolver =
            new ClientAddressResolver(List.of("127.0.0.1/32", "172.28.88.0/24", "::1/128"), "X-Forwarded-For");

    private static MockHttpServletRequest request(String peer, String... forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/graphql");
        request.setRemoteAddr(peer);
        for (String value : forwardedFor) request.addHeader("X-Forwarded-For", value);
        return request;
    }

    @Test
    void shouldIgnoreForwardedForFromUntrustedPeerTest() {
        var client = resolver.resolve(request("203.0.113.9", "198.51.100.1"));

        assertThat(client.address()).isEqualTo("203.0.113.9");
        assertThat(client.proxyWithoutClient()).isFalse();
    }

    @Test
    void shouldUseForwardedClientFromTrustedProxyTest() {
        var client = resolver.resolve(request("172.28.88.5", "198.51.100.1"));

        assertThat(client.address()).isEqualTo("198.51.100.1");
        assertThat(client.proxyWithoutClient()).isFalse();
    }

    @Test
    void shouldUseRightmostUntrustedHopSoPrependedEntriesCannotChooseTheBucketTest() {
        var client = resolver.resolve(request("172.28.88.5", "10.0.0.1, 198.51.100.1, 127.0.0.1"));

        assertThat(client.address()).isEqualTo("198.51.100.1");
    }

    @Test
    void shouldJoinRepeatedHeadersTest() {
        var client = resolver.resolve(request("127.0.0.1", "192.0.2.44", "198.51.100.7"));

        assertThat(client.address()).isEqualTo("198.51.100.7");
    }

    @Test
    void shouldFallBackToLeftmostWhenEveryHopIsTrustedTest() {
        var client = resolver.resolve(request("127.0.0.1", "172.28.88.20, 127.0.0.1"));

        assertThat(client.address()).isEqualTo("172.28.88.20");
        assertThat(client.proxyWithoutClient()).isFalse();
    }

    @Test
    void shouldTreatTrustedProxyWithoutClientAsProxyTrafficTest() {
        var client = resolver.resolve(request("172.28.88.5"));

        assertThat(client.address()).isEqualTo("172.28.88.5");
        assertThat(client.proxyWithoutClient()).isTrue();
    }

    @Test
    void shouldNotResolveHostnamesOrAcceptMalformedHopsTest() {
        var client = resolver.resolve(request("172.28.88.5", "example.com"));

        assertThat(client.address()).isEqualTo("172.28.88.5");
        assertThat(client.proxyWithoutClient()).isTrue();
    }

    @Test
    void shouldNormalizeIpv6AddressesTest() {
        var client = resolver.resolve(request("::1", "2001:DB8::1"));

        assertThat(client.address()).isEqualTo("2001:db8:0:0:0:0:0:1");
    }
}

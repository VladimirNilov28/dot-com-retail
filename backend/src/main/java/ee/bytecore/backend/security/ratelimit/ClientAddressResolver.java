package ee.bytecore.backend.security.ratelimit;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the client address for rate limiting. The forwarded-for header is
 * honoured only when the TCP peer is a configured trusted proxy (Next BFF,
 * Hive Router); the right-most untrusted hop wins, so entries a client
 * prepends cannot be used to pick a different bucket.
 */
public class ClientAddressResolver {

    /** {@code proxyWithoutClient} marks traffic a trusted proxy made on its own behalf (e.g. SSR cache fills). */
    public record ClientAddress(String address, boolean proxyWithoutClient) {}

    private final List<Cidr> trusted;
    private final String header;

    public ClientAddressResolver(List<String> trustedProxies, String header) {
        this.trusted = trustedProxies.stream().map(Cidr::parse).toList();
        this.header = header;
    }

    public ClientAddress resolve(HttpServletRequest request) {
        InetAddress peer = Cidr.literal(request.getRemoteAddr());
        String peerText = peer == null ? "unknown" : peer.getHostAddress();
        if (peer == null || !isTrusted(peer)) return new ClientAddress(peerText, false);

        List<String> hops = new ArrayList<>();
        for (String value : Collections.list(request.getHeaders(header))) {
            for (String part : value.split(",")) if (!part.isBlank()) hops.add(part.trim());
        }
        if (hops.isEmpty() || hops.size() > 20) return new ClientAddress(peerText, true);
        InetAddress leftmost = null;
        for (int i = hops.size() - 1; i >= 0; i--) {
            InetAddress hop = Cidr.literal(hops.get(i));
            // A malformed hop ends the trustworthy part of the chain.
            if (hop == null) return new ClientAddress(peerText, true);
            if (!isTrusted(hop)) return new ClientAddress(hop.getHostAddress(), false);
            leftmost = hop;
        }
        return new ClientAddress(leftmost.getHostAddress(), false);
    }

    private boolean isTrusted(InetAddress address) {
        return trusted.stream().anyMatch(cidr -> cidr.contains(address));
    }
}

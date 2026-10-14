package ee.bytecore.backend.security.ratelimit;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** An IPv4/IPv6 network in CIDR notation; literal addresses only (never resolves host names). */
public record Cidr(byte[] network, int prefix) {

    public static Cidr parse(String value) {
        String[] parts = value.trim().split("/", 2);
        InetAddress address = literal(parts[0]);
        if (address == null) throw new IllegalArgumentException("Invalid trusted proxy address: " + value);
        byte[] bytes = address.getAddress();
        int prefix;
        try {
            prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : bytes.length * 8;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid trusted proxy prefix: " + value);
        }
        if (prefix < 0 || prefix > bytes.length * 8)
            throw new IllegalArgumentException("Invalid trusted proxy prefix: " + value);
        return new Cidr(bytes, prefix);
    }

    public boolean contains(InetAddress address) {
        byte[] candidate = address.getAddress();
        if (candidate.length != network.length) return false;
        int full = prefix / 8;
        for (int i = 0; i < full; i++) if (candidate[i] != network[i]) return false;
        int rest = prefix % 8;
        if (rest == 0) return true;
        int mask = 0xFF << (8 - rest) & 0xFF;
        return (candidate[full] & mask) == (network[full] & mask);
    }

    /** Parses an IP literal without DNS; returns null for anything else. IPv4-mapped IPv6 becomes IPv4. */
    public static InetAddress literal(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (text.startsWith("[") && text.endsWith("]")) text = text.substring(1, text.length() - 1);
        if (text.isEmpty() || text.length() > 45) return null;
        boolean ipv4 = text.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean ipv6 = text.contains(":") && text.matches("[0-9A-Fa-f:.]+");
        if (!ipv4 && !ipv6) return null;
        if (ipv4) {
            for (String octet : text.split("\\.")) if (Integer.parseInt(octet) > 255) return null;
        }
        try {
            return InetAddress.getByName(text);
        } catch (UnknownHostException exception) {
            return null;
        }
    }
}

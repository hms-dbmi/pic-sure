package edu.harvard.hms.dbmi.avillach.commons.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * An IPv4 or IPv6 CIDR range such as {@code 10.0.0.0/8} or {@code 2001:db8::/32}. A bare address is a single-host range ({@code /32} or
 * {@code /128}). Host bits below the prefix are ignored, so {@code 10.1.2.3/8} is {@code 10.0.0.0/8}. An IPv4 range never contains an IPv6
 * address or the reverse. An IPv4-mapped client address ({@code ::ffff:a.b.c.d}) is the IPv4 address it maps, but an IPv4-mapped range is
 * rejected: write it as the IPv4 range.
 *
 * <p> Matching is Spring Security's {@link IpAddressMatcher}, so this class needs {@code spring-security-web} on the classpath.
 * Spring-commons declares it optional; the gateway has it. Parsing first checks the text against the same strict literal rules as client
 * addresses, since {@link IpAddressMatcher} alone takes loose forms such as {@code 10.1/16} or {@code 010.0.0.1} that would trust the wrong
 * range.
 */
public final class CidrRange {

    private final IpAddressMatcher matcher;
    private final InetAddress address;
    private final int prefixLength;
    private final String text;

    private CidrRange(IpAddressMatcher matcher, InetAddress address, int prefixLength, String text) {
        this.matcher = matcher;
        this.address = address;
        this.prefixLength = prefixLength;
        this.text = text;
    }

    /**
     * Parses one CIDR or bare address. Never consults DNS.
     *
     * @throws IllegalArgumentException if {@code cidr} is not a valid IPv4 or IPv6 CIDR or address
     */
    public static CidrRange parse(String cidr) {
        String trimmed = cidr == null ? "" : cidr.trim();
        try {
            int slash = trimmed.indexOf('/');
            String addressPart = slash < 0 ? trimmed : trimmed.substring(0, slash);
            InetAddress address = IpLiterals.parse(addressPart);
            if (address instanceof Inet4Address && addressPart.indexOf(':') >= 0) {
                throw new IllegalArgumentException("IPv4-mapped IPv6 range; write it as an IPv4 range");
            }
            int maxBits = address.getAddress().length * 8;
            int prefix = slash < 0 ? maxBits : parsePrefix(trimmed.substring(slash + 1), maxBits);
            return new CidrRange(new IpAddressMatcher(trimmed), address, prefix, trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid CIDR '" + trimmed + "': " + e.getMessage(), e);
        }
    }

    /**
     * Parses a comma-separated CIDR list as it arrives from configuration. Entries are trimmed and empty entries skipped, so {@code null},
     * blank and {@code " 10.0.0.0/8, ,::1 "} are all fine.
     *
     * @throws IllegalArgumentException naming the first malformed entry
     */
    public static List<CidrRange> parseList(String commaSeparated) {
        if (commaSeparated == null) {
            return List.of();
        }
        List<CidrRange> ranges = new ArrayList<>();
        for (String entry : commaSeparated.split(",")) {
            if (!entry.isBlank()) {
                ranges.add(parse(entry));
            }
        }
        return List.copyOf(ranges);
    }

    /**
     * Whether any of {@code ranges} contains {@code address}. False for a null address. Package-private so allowlist checks go through
     * {@link ClientIp#isIn}, which refuses failed resolutions.
     */
    static boolean anyContains(Collection<CidrRange> ranges, InetAddress address) {
        if (address == null) {
            return false;
        }
        for (CidrRange range : ranges) {
            if (range.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code address} falls inside this range. False for a null address or one of the other IP family. */
    public boolean contains(InetAddress address) {
        return address != null && matcher.matches(address.getHostAddress());
    }

    /** The range as configured, trimmed. */
    @Override
    public String toString() {
        return text;
    }

    /**
     * Two ranges are equal when they cover the same addresses, so host bits and a {@code /32} or {@code /128} on a bare address don't
     * count.
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof CidrRange other && prefixLength == other.prefixLength && contains(other.address);
    }

    @Override
    public int hashCode() {
        return 31 * address.getAddress().length + prefixLength;
    }

    private static int parsePrefix(String text, int maxBits) {
        boolean leadingZero = text.length() > 1 && text.charAt(0) == '0';
        if (text.isEmpty() || text.length() > 3 || leadingZero || !text.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("bad prefix length");
        }
        int prefix = Integer.parseInt(text);
        if (prefix > maxBits) {
            throw new IllegalArgumentException("prefix length over " + maxBits);
        }
        return prefix;
    }
}

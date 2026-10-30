package edu.harvard.hms.dbmi.avillach.commons.net;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * An IPv4 or IPv6 CIDR range such as {@code 10.0.0.0/8} or {@code 2001:db8::/32}. A bare address is a single-host range ({@code /32} or
 * {@code /128}). Host bits below the prefix are ignored, so {@code 10.1.2.3/8} is {@code 10.0.0.0/8}. An IPv4 range never contains an IPv6
 * address or the reverse, except that IPv4-mapped IPv6 literals ({@code ::ffff:a.b.c.d}) are treated as the IPv4 address they map.
 */
public final class CidrRange {

    private final byte[] network;
    private final int prefixLength;
    private final String text;

    private CidrRange(byte[] network, int prefixLength, String text) {
        this.network = network;
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
            byte[] bytes = IpLiterals.parse(addressPart).getAddress();
            int maxBits = bytes.length * 8;
            int prefix = slash < 0 ? maxBits : parsePrefix(trimmed.substring(slash + 1), maxBits);
            mask(bytes, prefix);
            return new CidrRange(bytes, prefix, trimmed);
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

    /** Whether any of {@code ranges} contains {@code address}. False for a null address. */
    public static boolean anyContains(Collection<CidrRange> ranges, InetAddress address) {
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
        if (address == null) {
            return false;
        }
        byte[] candidate = address.getAddress();
        if (candidate.length != network.length) {
            return false;
        }
        mask(candidate, prefixLength);
        return Arrays.equals(candidate, network);
    }

    public int prefixLength() {
        return prefixLength;
    }

    /** The range as configured, trimmed. */
    @Override
    public String toString() {
        return text;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CidrRange other && prefixLength == other.prefixLength && Arrays.equals(network, other.network);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(network) + prefixLength;
    }

    private static int parsePrefix(String text, int maxBits) {
        if (text.isEmpty() || text.length() > 3 || !text.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("bad prefix length");
        }
        int prefix = Integer.parseInt(text);
        if (prefix > maxBits) {
            throw new IllegalArgumentException("prefix length over " + maxBits);
        }
        return prefix;
    }

    private static void mask(byte[] bytes, int prefix) {
        for (int i = 0; i < bytes.length; i++) {
            int bitsInByte = Math.max(0, Math.min(8, prefix - i * 8));
            bytes[i] &= (byte) (0xFF << (8 - bitsInByte));
        }
    }
}

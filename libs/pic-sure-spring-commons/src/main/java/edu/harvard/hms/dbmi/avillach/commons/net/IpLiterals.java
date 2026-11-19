package edu.harvard.hms.dbmi.avillach.commons.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.regex.Pattern;

/**
 * Strict IP-literal parsing that never touches DNS. {@link InetAddress#getByName} resolves anything that isn't a literal, so a hostile
 * {@code X-Forwarded-For} entry could trigger lookups; these methods only accept literals.
 */
final class IpLiterals {

    private static final String OCTET = "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])";

    /** Four plain decimal octets. {@link Inet4Address#ofLiteral} alone also takes {@code 10.1} and {@code 010.0.0.1}. */
    private static final Pattern STRICT_IPV4 = Pattern.compile("(" + OCTET + "\\.){3}" + OCTET);

    private IpLiterals() {}

    /**
     * Parses a bare IPv4 dotted quad or an unbracketed IPv6 literal. IPv4 octets must be plain decimal without leading zeros, so
     * {@code 010.0.0.1} (octal to some parsers) is rejected rather than guessed at. IPv6 zone ids are rejected. An IPv4-mapped IPv6 literal
     * comes back as its {@link Inet4Address}.
     *
     * @throws IllegalArgumentException if {@code text} is not such a literal
     */
    static InetAddress parse(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("empty address");
        }
        if (text.indexOf(':') >= 0) {
            return parseIpv6(text);
        }
        return parseIpv4(text);
    }

    /**
     * Parses one {@code X-Forwarded-For} hop: a bare IPv4 or IPv6 literal, {@code ipv4:port}, {@code [ipv6]} or {@code [ipv6]:port}.
     *
     * @throws IllegalArgumentException if {@code hop} is none of those
     */
    static InetAddress parseHop(String hop) {
        if (hop.startsWith("[")) {
            int close = hop.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("unterminated bracket");
            }
            String rest = hop.substring(close + 1);
            if (!rest.isEmpty()) {
                requirePort(rest);
            }
            String inner = hop.substring(1, close);
            if (inner.indexOf(':') < 0) {
                throw new IllegalArgumentException("brackets around a non-IPv6 address");
            }
            return parseIpv6(inner);
        }
        int colon = hop.indexOf(':');
        if (colon >= 0 && colon == hop.lastIndexOf(':')) {
            requirePort(hop.substring(colon));
            return parseIpv4(hop.substring(0, colon));
        }
        return parse(hop);
    }

    private static void requirePort(String suffix) {
        if (suffix.length() < 2 || suffix.length() > 6 || suffix.charAt(0) != ':') {
            throw new IllegalArgumentException("bad port suffix");
        }
        int port = 0;
        for (int i = 1; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("bad port suffix");
            }
            port = port * 10 + (c - '0');
        }
        if (port > 65535) {
            throw new IllegalArgumentException("port out of range");
        }
    }

    private static InetAddress parseIpv4(String text) {
        if (!STRICT_IPV4.matcher(text).matches()) {
            throw new IllegalArgumentException("not an IPv4 address");
        }
        return Inet4Address.ofLiteral(text);
    }

    private static InetAddress parseIpv6(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean allowed = c == ':' || c == '.' || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!allowed) {
                throw new IllegalArgumentException("not an IPv6 address");
            }
        }
        try {
            return Inet6Address.ofLiteral(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("not an IPv6 address", e);
        }
    }
}

package edu.harvard.hms.dbmi.avillach.commons.net;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the real client address from the connection peer and {@code X-Forwarded-For}, trusting the header only as far as the configured
 * proxy ranges allow.
 *
 * <p>The peer is the starting point. When the peer is inside a trusted-proxy range the header is read: hops are walked right to left,
 * skipping any inside a trusted range, and the first untrusted hop is the client. If every hop is trusted the leftmost wins. A header from
 * an untrusted peer is ignored entirely, since the caller wrote all of it. Hops to the left of the client are never consulted for the
 * answer, which is why a caller-supplied prefix (AIO's httpd keeps one) cannot pick the result.
 *
 * <p>A trusted peer forwarding a header that is over {@link #MAX_HEADER_LENGTH} characters, over {@link #MAX_HOPS} hops, or contains any
 * entry that isn't an IP literal falls back to the peer and is marked {@link ClientIp#resolutionFailed()}.
 *
 * <p>Distinct from any allowlist: trusted-proxy membership only permits reading the header. Check allowlists with
 * {@link ClientIp#isIn(Collection)} on the result.
 */
public final class ClientIpResolver {

    public static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";

    /** Longest {@code X-Forwarded-For} accepted, summed across repeated header lines. */
    public static final int MAX_HEADER_LENGTH = 1024;

    /** Most {@code X-Forwarded-For} hops accepted. Real chains here are two or three. */
    public static final int MAX_HOPS = 16;

    private final List<CidrRange> trustedProxies;

    public ClientIpResolver(Collection<CidrRange> trustedProxies) {
        this.trustedProxies = List.copyOf(trustedProxies);
    }

    /**
     * Resolves the client of {@code request}, joining repeated {@code X-Forwarded-For} lines in order.
     *
     * @throws IllegalArgumentException if {@link HttpServletRequest#getRemoteAddr()} is not an IP literal
     */
    public ClientIp resolve(HttpServletRequest request) {
        return resolve(request.getRemoteAddr(), joinHeaders(request.getHeaders(HEADER_X_FORWARDED_FOR)));
    }

    /**
     * Resolves from a connection peer address and a raw {@code X-Forwarded-For} value (null or blank when absent).
     *
     * @throws IllegalArgumentException if {@code remoteAddr} is not an IP literal. Servlet containers always report one, so this means a
     *         misconfigured caller rather than hostile input.
     */
    public ClientIp resolve(String remoteAddr, String forwardedFor) {
        InetAddress peer = parsePeer(remoteAddr);
        if (forwardedFor == null || forwardedFor.isBlank() || !CidrRange.anyContains(trustedProxies, peer)) {
            return new ClientIp(peer, false);
        }
        List<InetAddress> hops = parseChain(forwardedFor);
        if (hops == null) {
            return new ClientIp(peer, true);
        }
        for (int i = hops.size() - 1; i >= 0; i--) {
            if (!CidrRange.anyContains(trustedProxies, hops.get(i))) {
                return new ClientIp(hops.get(i), false);
            }
        }
        return new ClientIp(hops.get(0), false);
    }

    /** The hops in header order, or null if the header is over a cap or any hop is malformed. */
    private static List<InetAddress> parseChain(String forwardedFor) {
        if (forwardedFor.length() > MAX_HEADER_LENGTH) {
            return null;
        }
        String[] entries = forwardedFor.split(",", -1);
        if (entries.length > MAX_HOPS) {
            return null;
        }
        List<InetAddress> hops = new ArrayList<>(entries.length);
        for (String entry : entries) {
            try {
                hops.add(IpLiterals.parseHop(entry.trim()));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return hops;
    }

    private static InetAddress parsePeer(String remoteAddr) {
        if (remoteAddr == null) {
            throw new IllegalArgumentException("Connection peer address is missing");
        }
        // Containers may report a link-local IPv6 peer with its zone id (fe80::1%eth0); the zone doesn't identify the client.
        int zone = remoteAddr.indexOf('%');
        String literal = zone < 0 ? remoteAddr : remoteAddr.substring(0, zone);
        try {
            return IpLiterals.parse(literal);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Connection peer is not an IP literal: '" + remoteAddr + "'", e);
        }
    }

    private static String joinHeaders(Enumeration<String> values) {
        if (values == null || !values.hasMoreElements()) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        while (values.hasMoreElements()) {
            if (!joined.isEmpty()) {
                joined.append(',');
            }
            joined.append(values.nextElement());
            if (joined.length() > MAX_HEADER_LENGTH) {
                break; // already over the cap; parseChain rejects it without reading the rest
            }
        }
        return joined.toString();
    }
}

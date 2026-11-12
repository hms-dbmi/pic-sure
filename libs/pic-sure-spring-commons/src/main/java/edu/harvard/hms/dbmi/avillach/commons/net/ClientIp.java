package edu.harvard.hms.dbmi.avillach.commons.net;

import java.net.InetAddress;
import java.util.Collection;
import java.util.Objects;

/**
 * The client address {@link ClientIpResolver} settled on.
 *
 * @param address the resolved client, or the connection peer when resolution failed
 * @param resolutionFailed true when a trusted proxy forwarded a malformed or over-long {@code X-Forwarded-For} and {@code address} is only
 *        the connection peer
 */
public record ClientIp(InetAddress address, boolean resolutionFailed) {

    public ClientIp {
        Objects.requireNonNull(address, "address");
    }

    /**
     * Whether the resolved client is inside any of {@code ranges}. Always false after a failed resolution, so a fallback to the peer can
     * never earn an allowlist exemption, even when the peer itself is listed.
     */
    public boolean isIn(Collection<CidrRange> ranges) {
        return !resolutionFailed && CidrRange.anyContains(ranges, address);
    }
}

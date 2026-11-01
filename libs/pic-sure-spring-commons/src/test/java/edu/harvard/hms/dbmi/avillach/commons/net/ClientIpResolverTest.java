package edu.harvard.hms.dbmi.avillach.commons.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

    // AIO: httpd on the compose network. BDC: httpd and the ALB in their VPC subnets.
    private static final String COMPOSE_NET = "172.18.0.0/16";
    private static final String HTTPD = "172.18.0.5";
    private static final String BDC_ALB_SUBNET = "10.0.1.0/24";
    private static final String BDC_HTTPD_SUBNET = "10.0.2.0/24";

    private static final String CLIENT = "203.0.113.7";
    private static final String FORGED = "198.51.100.66";

    private final ClientIpResolver aio = resolver(COMPOSE_NET);

    @Test
    void directTrafficWithoutHeaderResolvesToPeer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(CLIENT);

        assertResolved(aio.resolve(request), CLIENT);
    }

    @Test
    void headerFromUntrustedPeerIsIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(CLIENT);
        request.addHeader("X-Forwarded-For", FORGED);

        assertResolved(aio.resolve(request), CLIENT);
    }

    @Test
    void malformedHeaderFromUntrustedPeerIsIgnoredNotAFailure() {
        assertResolved(aio.resolve(CLIENT, "not-an-ip"), CLIENT);
    }

    @Test
    void aioShapeTakesTheAddressHttpdAppended() {
        assertResolved(aio.resolve(HTTPD, FORGED + ", " + CLIENT), CLIENT);
    }

    @Test
    void bdcShapeSkipsTheAlbAndTakesTheRealClient() {
        ClientIpResolver bdc = resolver(BDC_ALB_SUBNET + "," + BDC_HTTPD_SUBNET);

        assertResolved(bdc.resolve("10.0.2.9", FORGED + ", " + CLIENT + ", 10.0.1.17"), CLIENT);
    }

    @Test
    void peerJustOutsideTrustedRangeIsNotTrusted() {
        ClientIpResolver resolver = resolver("10.0.0.0/24");

        assertResolved(resolver.resolve("10.0.0.255", CLIENT), CLIENT);
        assertResolved(resolver.resolve("10.0.1.0", CLIENT), "10.0.1.0");
    }

    @Test
    void bareTrustedAddressMeansThatHostOnly() {
        ClientIpResolver resolver = resolver(HTTPD);

        assertResolved(resolver.resolve(HTTPD, CLIENT), CLIENT);
        assertResolved(resolver.resolve("172.18.0.6", CLIENT), "172.18.0.6");
    }

    @Test
    void everyHopTrustedResolvesToLeftmost() {
        assertResolved(aio.resolve(HTTPD, "172.18.0.9, 172.18.0.8"), "172.18.0.9");
    }

    @Test
    void noTrustedProxiesMeansHeaderNeverRead() {
        assertResolved(new ClientIpResolver(List.of()).resolve(HTTPD, CLIENT), HTTPD);
    }

    @Test
    void repeatedHeaderLinesAreJoinedInOrder() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(HTTPD);
        request.addHeader("X-Forwarded-For", FORGED);
        request.addHeader("X-Forwarded-For", CLIENT);

        assertResolved(aio.resolve(request), CLIENT);
    }

    @Test
    void emptyFirstHeaderLineIsStillAHop() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(HTTPD);
        request.addHeader("X-Forwarded-For", "");
        request.addHeader("X-Forwarded-For", "172.18.0.9");

        assertFailedToPeer(aio.resolve(request));
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"not-an-ip", CLIENT + ",", CLIENT + ", 172.18.0.9 , garbage", "garbage, 172.18.0.9", "unknown", "example.com",
            "203.0.113.7:99999", "203.0.113.7:", "[2001:db8::1", "[203.0.113.7]", "2001:db8::1:443x", "010.0.0.1", "fe80::1%eth0",
            "203.0.113.7 198.51.100.66"}
    )
    void malformedChainFallsBackToPeerAndIsFlagged(String header) {
        assertFailedToPeer(aio.resolve(HTTPD, header));
    }

    @ParameterizedTest
    @ValueSource(
        strings = {FORGED + ", garbage, " + CLIENT, "unknown, " + CLIENT, "x.x.x.x, " + CLIENT, "," + CLIENT, FORGED + ",," + CLIENT}
    )
    void malformedEntriesLeftOfTheClientAreNeverParsed(String header) {
        assertResolved(aio.resolve(HTTPD, header), CLIENT);
    }

    @Test
    void blankHeaderFromTrustedPeerIsTreatedAsAbsent() {
        assertResolved(aio.resolve(HTTPD, "  "), HTTPD);
    }

    @Test
    void overLongBlankHeaderFallsBack() {
        assertFailedToPeer(aio.resolve(HTTPD, " ".repeat(ClientIpResolver.MAX_HEADER_LENGTH + 1)));
    }

    @Test
    void failedResolutionIsNotAMemberOfAnAllowlistContainingThePeer() {
        ClientIp result = aio.resolve(HTTPD, "garbage");

        assertThat(result.address()).isEqualTo(ip(HTTPD));
        assertThat(result.isIn(CidrRange.parseList(HTTPD))).isFalse();
    }

    @Test
    void successfulResolutionIsCheckedAgainstAllowlist() {
        ClientIp result = aio.resolve(HTTPD, CLIENT);

        assertThat(result.isIn(CidrRange.parseList("203.0.113.0/24"))).isTrue();
        assertThat(result.isIn(CidrRange.parseList(COMPOSE_NET))).isFalse();
        assertThat(result.isIn(List.of())).isFalse();
    }

    @Test
    void overLongHeaderFallsBack() {
        String header = (FORGED + ",").repeat(5) + " ".repeat(ClientIpResolver.MAX_HEADER_LENGTH) + CLIENT;

        assertFailedToPeer(aio.resolve(HTTPD, header));
    }

    @Test
    void overLongHeaderAcrossRepeatedLinesFallsBack() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(HTTPD);
        request.addHeader("X-Forwarded-For", " ".repeat(ClientIpResolver.MAX_HEADER_LENGTH - 10) + FORGED);
        request.addHeader("X-Forwarded-For", CLIENT);

        assertFailedToPeer(aio.resolve(request));
    }

    @Test
    void hopCountAtCapIsAcceptedAndOverCapFallsBack() {
        String atCap = (FORGED + ",").repeat(ClientIpResolver.MAX_HOPS - 1) + CLIENT;
        String overCap = FORGED + "," + atCap;

        assertResolved(aio.resolve(HTTPD, atCap), CLIENT);
        assertFailedToPeer(aio.resolve(HTTPD, overCap));
    }

    @Test
    void ipv6PlainBracketedAndPortSuffixedHops() {
        assertResolved(aio.resolve(HTTPD, "2001:db8::1"), "2001:db8::1");
        assertResolved(aio.resolve(HTTPD, "[2001:db8::1]"), "2001:db8::1");
        assertResolved(aio.resolve(HTTPD, "[2001:db8::1]:443"), "2001:db8::1");
        assertResolved(aio.resolve(HTTPD, "2001:DB8:0:0:0:0:0:1"), "2001:db8::1");
    }

    @Test
    void ipv4PortSuffixedHop() {
        assertResolved(aio.resolve(HTTPD, FORGED + ", 203.0.113.7:51000"), CLIENT);
    }

    @Test
    void ipv6TrustedRangeAndIpv6Peer() {
        ClientIpResolver resolver = resolver("2001:db8::/32");

        assertResolved(resolver.resolve("2001:db8:1::5", FORGED + ", 2001:db8:2::9, [2001:db8:3::1]:8443"), FORGED);
        assertResolved(resolver.resolve("2001:db9::5", CLIENT), "2001:db9::5");
    }

    @Test
    void ipv6PeerWithZoneIdIsAccepted() {
        assertResolved(resolver("fe80::/10").resolve("fe80::1%eth0", CLIENT), CLIENT);
    }

    @Test
    void ipv4MappedIpv6HopIsTheIpv4Address() {
        assertResolved(aio.resolve(HTTPD, "::ffff:203.0.113.7"), CLIENT);
        assertResolved(aio.resolve(HTTPD, CLIENT + ", ::ffff:172.18.0.9"), CLIENT);
    }

    @Test
    void nonLiteralPeerIsRejected() {
        assertThatThrownBy(() -> aio.resolve("example.com", null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'example.com'");
        assertThatThrownBy(() -> aio.resolve(null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bestEffortClientFindsTheCallerPastTheAlbWhenPaddingFailsResolution() {
        ClientIpResolver bdc = resolver(BDC_ALB_SUBNET + "," + BDC_HTTPD_SUBNET);
        MockHttpServletRequest request =
            request("10.0.2.9", (FORGED + ", ").repeat(ClientIpResolver.MAX_HOPS + 1) + CLIENT + ", 10.0.1.17");

        assertThat(bdc.resolve(request).resolutionFailed()).isTrue();
        assertThat(bdc.bestEffortClient(request)).contains(ip(CLIENT));
    }

    @Test
    void bestEffortClientIgnoresTheLengthCapAndReadsRepeatedLines() {
        MockHttpServletRequest request = request(HTTPD, " ".repeat(ClientIpResolver.MAX_HEADER_LENGTH) + FORGED);
        request.addHeader("X-Forwarded-For", CLIENT);

        assertThat(aio.bestEffortClient(request)).contains(ip(CLIENT));
    }

    @Test
    void bestEffortClientWalksOnlyTheRightmostMaxHops() {
        ClientIpResolver resolver = resolver("10.0.0.0/8");
        String trustedHops = "10.0.0.1, ".repeat(ClientIpResolver.MAX_HOPS - 1) + "10.0.0.1";

        assertThat(resolver.bestEffortClient(request("10.0.0.2", CLIENT + ", " + trustedHops))).isEmpty();
        assertThat(resolver.bestEffortClient(request("10.0.0.2", CLIENT + ", " + trustedHops.substring(10)))).contains(ip(CLIENT));
    }

    @Test
    void bestEffortClientNeverParsesNonIpPaddingLeftOfTheCaller() {
        MockHttpServletRequest request = request(HTTPD, "junk, ".repeat(ClientIpResolver.MAX_HOPS + 1) + "junk, " + CLIENT);

        assertThat(aio.resolve(request).resolutionFailed()).isTrue();
        assertThat(aio.bestEffortClient(request)).contains(ip(CLIENT));
    }

    @Test
    void bestEffortClientStopsAtAMalformedHopInsteadOfReadingPastIt() {
        assertThat(aio.bestEffortClient(request(HTTPD, CLIENT + ", not-an-ip"))).isEmpty();
        assertThat(aio.bestEffortClient(request(HTTPD, CLIENT + ","))).isEmpty();
    }

    @Test
    void bestEffortClientIsEmptyForAnUntrustedPeerOrNoHeader() {
        assertThat(aio.bestEffortClient(request(CLIENT, FORGED))).isEmpty();
        MockHttpServletRequest noHeader = new MockHttpServletRequest();
        noHeader.setRemoteAddr(HTTPD);
        assertThat(aio.bestEffortClient(noHeader)).isEmpty();
    }

    private static MockHttpServletRequest request(String peer, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }

    private static ClientIpResolver resolver(String trusted) {
        return new ClientIpResolver(CidrRange.parseList(trusted));
    }

    private static void assertResolved(ClientIp result, String expected) {
        assertThat(result.resolutionFailed()).isFalse();
        assertThat(result.address()).isEqualTo(ip(expected));
    }

    private static void assertFailedToPeer(ClientIp result) {
        assertThat(result.resolutionFailed()).isTrue();
        assertThat(result.address()).isEqualTo(ip(HTTPD));
    }

    private static InetAddress ip(String literal) {
        return IpLiterals.parse(literal);
    }
}

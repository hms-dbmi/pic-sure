package edu.harvard.hms.dbmi.avillach.commons.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CidrRangeTest {

    @Test
    void ipv4RangeContainsItsEdgesAndNothingJustOutside() {
        CidrRange range = CidrRange.parse("172.18.0.0/16");

        assertThat(range.contains(ip("172.18.0.0"))).isTrue();
        assertThat(range.contains(ip("172.18.255.255"))).isTrue();
        assertThat(range.contains(ip("172.17.255.255"))).isFalse();
        assertThat(range.contains(ip("172.19.0.0"))).isFalse();
    }

    @Test
    void nonOctetAlignedPrefix() {
        CidrRange range = CidrRange.parse("10.0.0.16/28");

        assertThat(range.contains(ip("10.0.0.16"))).isTrue();
        assertThat(range.contains(ip("10.0.0.31"))).isTrue();
        assertThat(range.contains(ip("10.0.0.15"))).isFalse();
        assertThat(range.contains(ip("10.0.0.32"))).isFalse();
    }

    @Test
    void bareAddressesAreSingleHostRanges() {
        assertThat(CidrRange.parse("192.0.2.10").prefixLength()).isEqualTo(32);
        assertThat(CidrRange.parse("192.0.2.10").contains(ip("192.0.2.11"))).isFalse();
        assertThat(CidrRange.parse("2001:db8::1").prefixLength()).isEqualTo(128);
        assertThat(CidrRange.parse("2001:db8::1").contains(ip("2001:db8::1"))).isTrue();
        assertThat(CidrRange.parse("2001:db8::1").contains(ip("2001:db8::2"))).isFalse();
    }

    @Test
    void ipv6Range() {
        CidrRange range = CidrRange.parse("2001:db8::/32");

        assertThat(range.contains(ip("2001:db8:ffff:ffff:ffff:ffff:ffff:ffff"))).isTrue();
        assertThat(range.contains(ip("2001:db9::"))).isFalse();
        assertThat(range.contains(ip("192.0.2.1"))).isFalse();
    }

    @Test
    void hostBitsAreIgnoredAndZeroPrefixMatchesTheWholeFamily() {
        assertThat(CidrRange.parse("10.1.2.3/8")).isEqualTo(CidrRange.parse("10.0.0.0/8"));
        assertThat(CidrRange.parse("0.0.0.0/0").contains(ip("203.0.113.7"))).isTrue();
        assertThat(CidrRange.parse("0.0.0.0/0").contains(ip("2001:db8::1"))).isFalse();
    }

    @Test
    void parsesListTrimmingSpacesAndSkippingBlanks() {
        assertThat(CidrRange.parseList(" 10.0.0.0/8 , ,2001:db8::/32,, 192.0.2.1 "))
            .containsExactly(CidrRange.parse("10.0.0.0/8"), CidrRange.parse("2001:db8::/32"), CidrRange.parse("192.0.2.1"));
        assertThat(CidrRange.parseList("")).isEmpty();
        assertThat(CidrRange.parseList("  ")).isEmpty();
        assertThat(CidrRange.parseList(null)).isEmpty();
    }

    @Test
    void malformedListEntryThrowsNamingIt() {
        assertThatThrownBy(() -> CidrRange.parseList("10.0.0.0/8, 10.0.0.300/24, ::1")).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'10.0.0.300/24'");
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"", "10.0.0.0/", "10.0.0.0/33", "2001:db8::/129", "10.0.0.0/-1", "10.0.0.0/8/8", "10.0.0", "10.0.0.0.0", "010.0.0.0/8",
            "example.com", "localhost", "[2001:db8::]/32", "fe80::1%eth0", "10.0.0.0 /8", "10.0.0.0/ 8"}
    )
    void rejectsMalformedEntries(String cidr) {
        assertThatThrownBy(() -> CidrRange.parse(cidr)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'" + cidr.trim() + "'");
    }

    private static InetAddress ip(String literal) {
        return IpLiterals.parse(literal);
    }
}

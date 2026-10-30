package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RateLimitSubjectTest {

    @Test
    void ipv6AddressesInOneSlash64ShareASubject() throws Exception {
        RateLimitSubject a = RateLimitSubject.ip(InetAddress.getByName("2001:db8:1:2::1"));
        RateLimitSubject b = RateLimitSubject.ip(InetAddress.getByName("2001:db8:1:2:ffff:ffff:ffff:ffff"));

        assertThat(a).isEqualTo(b);
        assertThat(a.value()).isEqualTo("2001:db8:1:2:0:0:0:0/64");
    }

    @Test
    void ipv6AddressesInDifferentSlash64sDoNotShareASubject() throws Exception {
        assertThat(RateLimitSubject.ip(InetAddress.getByName("2001:db8:1:2::1")))
            .isNotEqualTo(RateLimitSubject.ip(InetAddress.getByName("2001:db8:1:3::1")));
    }

    @Test
    void ipv6ScopeIdDoesNotSplitAPrefix() throws Exception {
        assertThat(RateLimitSubject.ip(InetAddress.getByName("fe80::1%1")))
            .isEqualTo(RateLimitSubject.ip(InetAddress.getByName("fe80::2")));
    }

    @Test
    void ipv4IsPerAddress() throws Exception {
        RateLimitSubject a = RateLimitSubject.ip(InetAddress.getByName("203.0.113.7"));

        assertThat(a.value()).isEqualTo("203.0.113.7");
        assertThat(a).isNotEqualTo(RateLimitSubject.ip(InetAddress.getByName("203.0.113.8")));
    }

    @Test
    void ipv4MappedIpv6IsTreatedAsIpv4() throws Exception {
        assertThat(RateLimitSubject.ip(InetAddress.getByName("::ffff:203.0.113.7")))
            .isEqualTo(RateLimitSubject.ip(InetAddress.getByName("203.0.113.7")));
    }

    @Test
    void sameRawValueDifferentKindsAreDifferentSubjects() throws Exception {
        InetAddress address = InetAddress.getByName("203.0.113.7");
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertThat(RateLimitSubject.prevalidationIp(address)).isNotEqualTo(RateLimitSubject.ip(address));
        assertThat(RateLimitSubject.user(id)).isNotEqualTo(RateLimitSubject.session(id.toString()));
        assertThat(RateLimitSubject.prevalidationIp(address)).hasToString("prevalidation_ip:203.0.113.7");
    }

    @Test
    void blankSessionIdIsRejected() {
        assertThatThrownBy(() -> RateLimitSubject.session(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}

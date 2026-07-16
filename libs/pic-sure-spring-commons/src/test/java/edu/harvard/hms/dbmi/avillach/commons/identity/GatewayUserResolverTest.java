package edu.harvard.hms.dbmi.avillach.commons.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class GatewayUserResolverTest {

    @Test
    void resolvesGatewayUserFromHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(GatewayUserResolver.HEADER_USER_ID, "user-1");
        request.addHeader(GatewayUserResolver.HEADER_USER_SUBJECT, "subject-1");
        request.addHeader(GatewayUserResolver.HEADER_USER_EMAIL, "user@example.com");
        request.addHeader(GatewayUserResolver.HEADER_USER_ROLES, "ADMIN,USER");
        request.addHeader(GatewayUserResolver.HEADER_USER_PRIVILEGES, "query.read, query.write ,,admin.all");

        Optional<GatewayUser> resolved = GatewayUserResolver.resolve(request);

        assertThat(resolved).isPresent();
        GatewayUser user = resolved.get();
        assertThat(user.getUserId()).isEqualTo("user-1");
        assertThat(user.getSubject()).isEqualTo("subject-1");
        assertThat(user.getEmail()).isEqualTo("user@example.com");
        assertThat(user.getRoles()).isEqualTo("ADMIN,USER");
        assertThat(user.getPrivileges()).containsExactlyInAnyOrder("query.read", "query.write", "admin.all");
    }

    @Test
    void returnsEmptyWhenUserIdHeaderIsMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(GatewayUserResolver.resolve(request)).isEmpty();
    }

    @Test
    void privilegesAreEmptySetWhenHeaderIsAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(GatewayUserResolver.HEADER_USER_ID, "user-1");

        GatewayUser user = GatewayUserResolver.resolve(request).orElseThrow();

        assertThat(user.getPrivileges()).isEmpty();
    }

    @Test
    void headerConstantsMatchTheAgreedContract() {
        assertThat(GatewayUserResolver.HEADER_USER_ID).isEqualTo("X-User-Id");
        assertThat(GatewayUserResolver.HEADER_USER_SUBJECT).isEqualTo("X-User-Subject");
        assertThat(GatewayUserResolver.HEADER_USER_EMAIL).isEqualTo("X-User-Email");
        assertThat(GatewayUserResolver.HEADER_USER_ROLES).isEqualTo("X-User-Roles");
        assertThat(GatewayUserResolver.HEADER_USER_PRIVILEGES).isEqualTo("X-User-Privileges");
    }
}

package edu.harvard.hms.dbmi.avillach.gateway.docs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import edu.harvard.hms.dbmi.avillach.gateway.auth.ShippedPublicRoutes;
import edu.harvard.hms.dbmi.avillach.gateway.config.GatewaySecurityProperties;

/**
 * Both console prefixes stay on the unauthenticated public-route list the introspection filter consults. A prefix match is what makes
 * {@code /openapi/{name}} and the Swagger UI assets reachable, not just the bare prefixes.
 */
@SpringBootTest
class DocsAllowListTest {

    @Autowired
    private GatewaySecurityProperties security;

    @Test
    void consolePrefixesAreServedWithoutAToken() {
        assertThat(security.publicRoutes()).contains(ShippedPublicRoutes.prefix("/openapi"), ShippedPublicRoutes.prefix("/swagger-ui"));
    }
}

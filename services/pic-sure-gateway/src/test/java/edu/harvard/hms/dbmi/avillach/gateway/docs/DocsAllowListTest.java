package edu.harvard.hms.dbmi.avillach.gateway.docs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import edu.harvard.hms.dbmi.avillach.gateway.config.GatewaySecurityProperties;

/** Both console prefixes stay on the unauthenticated allow-list the introspection filter consults. */
@SpringBootTest
class DocsAllowListTest {

    @Autowired
    private GatewaySecurityProperties security;

    @Test
    void consolePrefixesAreAllowListed() {
        assertThat(security.allowListPrefixes()).contains("/openapi", "/swagger-ui");
    }
}

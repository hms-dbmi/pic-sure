package edu.harvard.dbmi.avillach.logging;

import edu.harvard.dbmi.avillach.logging.config.LoggingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the full binder path for an explicitly empty JWT_CLAIM_MAPPING: the raw "{}" string flows through the placeholder in
 * application.yml, through {@link edu.harvard.dbmi.avillach.logging.config.JwtClaimMappingConverter}, and into {@link LoggingProperties}
 * without being replaced by the default mapping. This is the test that would have caught the regression where LoggingProperties treated an
 * empty map the same as a missing one.
 */
@SpringBootTest(properties = {"picsure.logging.api-key=test-key", "picsure.logging.jwt-claim-mapping={}"})
class EmptyJwtClaimMappingIntegrationTest {

    @Autowired
    private LoggingProperties properties;

    @Test
    void explicitlyEmptyJwtClaimMappingBindsToAnEmptyMap() {
        assertThat(properties.jwtClaimMapping()).isEmpty();
    }
}

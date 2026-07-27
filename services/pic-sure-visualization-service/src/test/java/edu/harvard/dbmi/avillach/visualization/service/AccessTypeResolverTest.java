package edu.harvard.dbmi.avillach.visualization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import edu.harvard.dbmi.avillach.visualization.error.BadVisualizationRequestException;
import edu.harvard.dbmi.avillach.visualization.model.AccessType;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import org.junit.jupiter.api.Test;

class AccessTypeResolverTest {

    private final AccessTypeResolver resolver = new AccessTypeResolver();

    @Test
    void resolvesAuthorized() {
        assertEquals(AccessType.AUTHORIZED, resolver.resolve(GatewayUserResolver.ACCESS_TYPE_AUTHORIZED));
    }

    @Test
    void resolvesOpen() {
        assertEquals(AccessType.OPEN, resolver.resolve(GatewayUserResolver.ACCESS_TYPE_OPEN));
    }

    @Test
    void resolveIsCaseInsensitiveAndTrims() {
        assertEquals(AccessType.OPEN, resolver.resolve("  OPEN "));
        assertEquals(AccessType.AUTHORIZED, resolver.resolve("Authorized"));
    }

    @Test
    void missingHeaderFailsClosed() {
        // Absence means the request never traversed the gateway auth chain, so there is no trustworthy access type.
        // Defaulting to AUTHORIZED would expose non-obfuscated counts; defaulting to OPEN would silently downgrade
        // real users with nothing in the response to say so. Fail loudly instead.
        BadVisualizationRequestException e = assertThrows(BadVisualizationRequestException.class, () -> resolver.resolve(null));
        assertEquals("Missing or unrecognized X-Picsure-Access-Type header", e.getMessage());
    }

    @Test
    void blankHeaderFailsClosed() {
        assertThrows(BadVisualizationRequestException.class, () -> resolver.resolve("   "));
    }

    @Test
    void unrecognizedHeaderFailsClosed() {
        assertThrows(BadVisualizationRequestException.class, () -> resolver.resolve("superuser"));
    }
}

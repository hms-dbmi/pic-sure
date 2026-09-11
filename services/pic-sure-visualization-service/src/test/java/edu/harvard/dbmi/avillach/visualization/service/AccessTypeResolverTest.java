package edu.harvard.dbmi.avillach.visualization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import edu.harvard.dbmi.avillach.visualization.error.BadVisualizationRequestException;
import edu.harvard.dbmi.avillach.visualization.model.AccessType;
import org.junit.jupiter.api.Test;

class AccessTypeResolverTest {

    private final AccessTypeResolver resolver = new AccessTypeResolver();

    @Test
    void resolvesAuthBackend() {
        assertEquals(AccessType.AUTHORIZED, resolver.resolve("auth"));
    }

    @Test
    void resolvesOpen() {
        assertEquals(AccessType.OPEN, resolver.resolve("open"));
    }

    @Test
    void backendMustBeAnExactPathSegment() {
        assertThrows(BadVisualizationRequestException.class, () -> resolver.resolve(" OPEN "));
        assertThrows(BadVisualizationRequestException.class, () -> resolver.resolve("authorized"));
        assertThrows(BadVisualizationRequestException.class, () -> resolver.resolve("AUTH"));
    }

    @Test
    void missingBackendFailsClosed() {
        BadVisualizationRequestException e = assertThrows(BadVisualizationRequestException.class, () -> resolver.resolve(null));
        assertEquals("Missing or unrecognized visualization backend", e.getMessage());
    }

    @Test
    void blankBackendFailsClosed() {
        assertThrows(BadVisualizationRequestException.class, () -> resolver.resolve("   "));
    }

    @Test
    void unrecognizedBackendFailsClosed() {
        assertThrows(BadVisualizationRequestException.class, () -> resolver.resolve("superuser"));
    }
}

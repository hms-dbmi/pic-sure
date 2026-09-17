package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.service.IdpProvisioningService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IdpProvisioningRegistryTest {

    @Test
    public void findFor_returnsMatchingService() {
        Connection connection = new Connection().setId("OKTA_CONN");
        IdpProvisioningService nonMatching = mock(IdpProvisioningService.class);
        when(nonMatching.supports(connection)).thenReturn(false);
        IdpProvisioningService matching = mock(IdpProvisioningService.class);
        when(matching.supports(connection)).thenReturn(true);

        IdpProvisioningRegistry registry = new IdpProvisioningRegistry(List.of(nonMatching, matching));

        Optional<IdpProvisioningService> result = registry.findFor(connection);

        assertTrue(result.isPresent());
        assertEquals(matching, result.get());
    }

    @Test
    public void findFor_emptyWhenNoServiceSupportsConnection() {
        Connection connection = new Connection().setId("UNMATCHED_CONN");
        IdpProvisioningService service = mock(IdpProvisioningService.class);
        when(service.supports(connection)).thenReturn(false);

        IdpProvisioningRegistry registry = new IdpProvisioningRegistry(List.of(service));

        assertTrue(registry.findFor(connection).isEmpty());
    }

    @Test
    public void findFor_emptyWhenNoServicesRegistered() {
        IdpProvisioningRegistry registry = new IdpProvisioningRegistry(List.of());

        assertTrue(registry.findFor(new Connection().setId("ANY")).isEmpty());
    }

    @Test
    public void findFor_emptyForNullConnection() {
        IdpProvisioningService service = mock(IdpProvisioningService.class);
        IdpProvisioningRegistry registry = new IdpProvisioningRegistry(List.of(service));

        assertTrue(registry.findFor(null).isEmpty());
    }
}

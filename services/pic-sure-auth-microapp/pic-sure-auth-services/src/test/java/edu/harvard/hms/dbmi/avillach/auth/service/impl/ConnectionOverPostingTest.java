package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ConnectionCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ConnectionUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserMetadataMappingRepository;

/** A connection create generates its own identifier, and an update keeps the identifier and any field the request omits. */
class ConnectionOverPostingTest {

    private final ConnectionRepository repo = mock(ConnectionRepository.class);
    private final ConnectionWebService service = new ConnectionWebService(repo, mock(UserMetadataMappingRepository.class));

    @Test
    void creatingAConnectionGeneratesItsOwnIdentifier() {
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createFrom(List.of(new ConnectionCreateRequest("fence", "Fence", "fence|", "[]")));

        Connection saved = savedConnection();
        assertNull(saved.getUuid());
        assertEquals("fence", saved.getId());
    }

    @Test
    void updatingAConnectionKeepsItsIdentityAndUnlistedFields() {
        UUID connectionId = UUID.randomUUID();
        Connection stored = new Connection().setId("fence").setLabel("Fence").setSubPrefix("fence|").setRequiredFields("[]");
        stored.setUuid(connectionId);
        when(repo.findById(connectionId)).thenReturn(Optional.of(stored));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateFrom(List.of(new ConnectionUpdateRequest(connectionId, null, "Renamed", null, null)));

        Connection saved = savedConnection();
        assertEquals(connectionId, saved.getUuid());
        assertEquals("Renamed", saved.getLabel());
        assertEquals("fence", saved.getId());
        assertEquals("fence|", saved.getSubPrefix());
    }

    @Test
    void updatingAnUnknownConnectionIsRejected() {
        UUID unknown = UUID.randomUUID();
        when(repo.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(
            IllegalArgumentException.class, () -> service.updateFrom(List.of(new ConnectionUpdateRequest(unknown, null, "x", null, null)))
        );
        verify(repo, never()).saveAll(anyList());
    }

    @SuppressWarnings("unchecked")
    private Connection savedConnection() {
        ArgumentCaptor<List<Connection>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        return captor.getValue().getFirst();
    }
}

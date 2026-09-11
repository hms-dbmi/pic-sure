package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ConnectionCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ConnectionUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserMetadataMappingRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Over-posting cover for the connection endpoints. A create must generate its own identifier and an update must keep the identifier and any field the request omits.
 */
class ConnectionOverPostingTest {

    @Test
    void creatingAConnectionGeneratesItsOwnIdentifier() {
        ConnectionRepository repo = mock(ConnectionRepository.class);
        ConnectionWebService service = new ConnectionWebService(repo, mock(UserMetadataMappingRepository.class));
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createFrom(List.of(new ConnectionCreateRequest("fence", "Fence", "fence|", "[]")));

        Connection saved = captureSaved(repo);
        assertNull(saved.getUuid());
        assertEquals("fence", saved.getId());
    }

    @Test
    void updatingAConnectionKeepsItsIdentityAndUnlistedFields() {
        ConnectionRepository repo = mock(ConnectionRepository.class);
        ConnectionWebService service = new ConnectionWebService(repo, mock(UserMetadataMappingRepository.class));
        UUID connectionId = UUID.randomUUID();
        Connection stored = new Connection().setId("fence").setLabel("Fence").setSubPrefix("fence|").setRequiredFields("[]");
        stored.setUuid(connectionId);
        when(repo.findById(connectionId)).thenReturn(Optional.of(stored));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateFrom(List.of(new ConnectionUpdateRequest(connectionId, null, "Renamed", null, null)));

        Connection saved = captureSaved(repo);
        assertEquals(connectionId, saved.getUuid());
        assertEquals("Renamed", saved.getLabel());
        assertEquals("fence", saved.getId(), "an absent member leaves the stored value alone");
        assertEquals("fence|", saved.getSubPrefix());
    }

    private static <T> T captureSaved(org.springframework.data.jpa.repository.JpaRepository<T, ?> repo) {
        ArgumentCaptor<List<T>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        return captor.getValue().getFirst();
    }

}

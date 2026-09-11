package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.UserMetadataMapping;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ConnectionRef;
import edu.harvard.hms.dbmi.avillach.auth.model.request.UserMetadataMappingCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.UserMetadataMappingUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserMetadataMappingRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Over-posting cover for the user metadata mapping endpoints. A create resolves its connection from storage, and an update keeps the identifier and the stored connection when the request omits it.
 */
class UserMetadataMappingOverPostingTest {

    @Test
    void creatingAMetadataMappingResolvesItsConnectionFromStorage() {
        UserMetadataMappingRepository repo = mock(UserMetadataMappingRepository.class);
        ConnectionRepository connectionRepo = mock(ConnectionRepository.class);
        UserMetadataMappingService service = new UserMetadataMappingService(repo, connectionRepo);
        Connection persisted = new Connection().setId("fence").setLabel("Fence");
        persisted.setUuid(UUID.randomUUID());
        when(connectionRepo.findById("fence")).thenReturn(Optional.of(persisted));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createFrom(List.of(new UserMetadataMappingCreateRequest(new ConnectionRef("fence"), "$.email", "$.email")));

        UserMetadataMapping saved = captureSaved(repo);
        assertNull(saved.getUuid());
        assertSame(persisted, saved.getConnection());
    }

    @Test
    void updatingAMetadataMappingKeepsItsIdentityAndConnectionWhenOmitted() {
        UserMetadataMappingRepository repo = mock(UserMetadataMappingRepository.class);
        ConnectionRepository connectionRepo = mock(ConnectionRepository.class);
        UserMetadataMappingService service = new UserMetadataMappingService(repo, connectionRepo);
        UUID mappingId = UUID.randomUUID();
        Connection storedConnection = new Connection().setId("fence");
        UserMetadataMapping stored = new UserMetadataMapping().setConnection(storedConnection).setGeneralMetadataJsonPath("$.email")
            .setAuth0MetadataJsonPath("$.email");
        stored.setUuid(mappingId);
        when(repo.findById(mappingId)).thenReturn(Optional.of(stored));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateFrom(List.of(new UserMetadataMappingUpdateRequest(mappingId, null, "$.mail", null)));

        UserMetadataMapping saved = captureSaved(repo);
        assertEquals(mappingId, saved.getUuid());
        assertSame(storedConnection, saved.getConnection());
        assertEquals("$.mail", saved.getGeneralMetadataJsonPath());
        assertEquals("$.email", saved.getAuth0MetadataJsonPath());
    }

    private static <T> T captureSaved(org.springframework.data.jpa.repository.JpaRepository<T, ?> repo) {
        ArgumentCaptor<List<T>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        return captor.getValue().getFirst();
    }

}

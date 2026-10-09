package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
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
import edu.harvard.hms.dbmi.avillach.auth.entity.UserMetadataMapping;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ConnectionRef;
import edu.harvard.hms.dbmi.avillach.auth.model.request.UserMetadataMappingCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.UserMetadataMappingUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserMetadataMappingRepository;

/**
 * A metadata mapping create resolves its connection from storage and generates its own identifier, and an update keeps the identifier and
 * the stored connection when the request omits it.
 */
class UserMetadataMappingOverPostingTest {

    private final UserMetadataMappingRepository repo = mock(UserMetadataMappingRepository.class);
    private final ConnectionRepository connectionRepo = mock(ConnectionRepository.class);
    private final UserMetadataMappingService service = new UserMetadataMappingService(repo, connectionRepo);

    @Test
    void creatingAMetadataMappingResolvesItsConnectionFromStorage() {
        Connection persisted = new Connection().setId("fence").setLabel("Fence");
        persisted.setUuid(UUID.randomUUID());
        when(connectionRepo.findById("fence")).thenReturn(Optional.of(persisted));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createFrom(List.of(new UserMetadataMappingCreateRequest(new ConnectionRef("fence"), "$.email", "$.email")));

        UserMetadataMapping saved = savedMapping();
        assertNull(saved.getUuid());
        assertSame(persisted, saved.getConnection());
    }

    @Test
    void updatingAMetadataMappingKeepsItsIdentityAndConnectionWhenOmitted() {
        UUID mappingId = UUID.randomUUID();
        Connection storedConnection = new Connection().setId("fence");
        UserMetadataMapping stored = new UserMetadataMapping().setConnection(storedConnection).setGeneralMetadataJsonPath("$.email")
            .setAuth0MetadataJsonPath("$.email");
        stored.setUuid(mappingId);
        when(repo.findById(mappingId)).thenReturn(Optional.of(stored));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateFrom(List.of(new UserMetadataMappingUpdateRequest(mappingId, null, "$.mail", null)));

        UserMetadataMapping saved = savedMapping();
        assertEquals(mappingId, saved.getUuid());
        assertSame(storedConnection, saved.getConnection());
        assertEquals("$.mail", saved.getGeneralMetadataJsonPath());
        assertEquals("$.email", saved.getAuth0MetadataJsonPath());
    }

    @Test
    void creatingAMappingForAnUnknownConnectionIsRejected() {
        when(connectionRepo.findById("nope")).thenReturn(Optional.empty());

        assertThrows(
            IllegalArgumentException.class,
            () -> service.createFrom(List.of(new UserMetadataMappingCreateRequest(new ConnectionRef("nope"), "$.email", "$.email")))
        );
        verify(repo, never()).saveAll(anyList());
    }

    @SuppressWarnings("unchecked")
    private UserMetadataMapping savedMapping() {
        ArgumentCaptor<List<UserMetadataMapping>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        return captor.getValue().getFirst();
    }
}

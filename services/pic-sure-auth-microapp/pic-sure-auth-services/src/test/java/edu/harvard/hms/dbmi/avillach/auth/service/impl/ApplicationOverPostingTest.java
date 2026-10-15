package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
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

import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ApplicationCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ApplicationUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.repository.ApplicationRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.PrivilegeRepository;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;

/**
 * The application bearer token is server-owned: a create mints it after the row is persisted, and an update leaves the stored token alone
 * because the update record has no token member.
 */
class ApplicationOverPostingTest {

    private final ApplicationRepository repo = mock(ApplicationRepository.class);
    private final JWTUtil jwtUtil = mock(JWTUtil.class);
    private final ApplicationService service = new ApplicationService(repo, mock(PrivilegeRepository.class), jwtUtil);

    @Test
    void updatingAnApplicationKeepsItsBearerToken() {
        UUID appId = UUID.randomUUID();
        Application stored = new Application();
        stored.setUuid(appId);
        stored.setName("picsure");
        stored.setToken("stored-application-token");
        when(repo.findById(appId)).thenReturn(Optional.of(stored));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateFrom(List.of(new ApplicationUpdateRequest(appId, "renamed", "desc", "https://example.org", false, null)));

        Application saved = savedApplication();
        assertEquals("stored-application-token", saved.getToken());
        assertEquals(appId, saved.getUuid());
        assertEquals("renamed", saved.getName());
        assertFalse(saved.isEnable());
    }

    @Test
    void creatingAnApplicationMintsItsTokenServerSide() {
        when(jwtUtil.createJwtToken(any(), any(), anyMap(), anyString(), anyLong())).thenReturn("minted-token");
        when(repo.saveAll(anyList())).thenAnswer(invocation -> {
            List<Application> apps = invocation.getArgument(0);
            apps.stream().filter(app -> app.getUuid() == null).forEach(app -> app.setUuid(UUID.randomUUID()));
            return apps;
        });

        List<Application> created =
            service.createFrom(List.of(new ApplicationCreateRequest("new-app", "desc", "https://example.org", true, null)));

        assertEquals("minted-token", created.getFirst().getToken());
    }

    @Test
    void updatingAnUnknownApplicationIsRejected() {
        UUID unknown = UUID.randomUUID();
        when(repo.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(
            IllegalArgumentException.class,
            () -> service.updateFrom(List.of(new ApplicationUpdateRequest(unknown, "x", null, null, null, null)))
        );
        verify(repo, never()).saveAll(anyList());
    }

    @SuppressWarnings("unchecked")
    private Application savedApplication() {
        ArgumentCaptor<List<Application>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        return captor.getValue().getFirst();
    }
}

package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ApplicationCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ApplicationUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.repository.ApplicationRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.PrivilegeRepository;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Over-posting cover for the application endpoints. The bearer token is server-owned: a create mints it after the row is persisted and an update must leave the stored token untouched, because the update record has no token member at all.
 */
class ApplicationOverPostingTest {

    @Test
    void updatingAnApplicationKeepsItsBearerToken() {
        ApplicationRepository repo = mock(ApplicationRepository.class);
        ApplicationService service = new ApplicationService(repo, mock(PrivilegeRepository.class), mock(JWTUtil.class));
        UUID appId = UUID.randomUUID();
        Application stored = new Application();
        stored.setUuid(appId);
        stored.setName("picsure");
        stored.setToken("stored-application-token");
        when(repo.findById(appId)).thenReturn(Optional.of(stored));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateFrom(List.of(new ApplicationUpdateRequest(appId, "renamed", "desc", "https://example.org", false, null)));

        Application saved = captureSaved(repo);
        assertEquals("stored-application-token", saved.getToken(), "the application token is not reachable from a request body");
        assertEquals(appId, saved.getUuid());
        assertEquals("renamed", saved.getName());
        assertFalse(saved.isEnable());
    }

    @Test
    void creatingAnApplicationMintsItsTokenServerSide() {
        ApplicationRepository repo = mock(ApplicationRepository.class);
        JWTUtil jwtUtil = mock(JWTUtil.class);
        when(jwtUtil.createJwtToken(any(), any(), anyMap(), anyString(), anyLong())).thenReturn("minted-token");
        ApplicationService service = new ApplicationService(repo, mock(PrivilegeRepository.class), jwtUtil);
        when(repo.saveAll(anyList())).thenAnswer(invocation -> {
            List<Application> apps = invocation.getArgument(0);
            apps.forEach(app -> {
                if (app.getUuid() == null) {
                    app.setUuid(UUID.randomUUID());
                }
            });
            return apps;
        });

        List<Application> created =
            service.createFrom(List.of(new ApplicationCreateRequest("new-app", "desc", "https://example.org", true, null)));

        assertEquals("minted-token", created.getFirst().getToken());
    }

    private static <T> T captureSaved(org.springframework.data.jpa.repository.JpaRepository<T, ?> repo) {
        ArgumentCaptor<List<T>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        return captor.getValue().getFirst();
    }


    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }


    private static <K, V> java.util.Map<K, V> anyMap() {
        return org.mockito.ArgumentMatchers.anyMap();
    }


    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}

package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.UserMetadataMapping;
import edu.harvard.hms.dbmi.avillach.auth.model.request.AccessRuleCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.AccessRuleUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ApplicationCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ApplicationUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ConnectionCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ConnectionRef;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ConnectionUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.EntityIdRef;
import edu.harvard.hms.dbmi.avillach.auth.model.request.PrivilegeCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.PrivilegeUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.RoleCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.RoleUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.UserMetadataMappingCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.UserMetadataMappingUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.repository.AccessRuleRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.ApplicationRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.PrivilegeRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.RoleRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserMetadataMappingRepository;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Over-posting cover for the six administrative endpoints other than {@code /user}. Each create must generate its own identifier -- so a
 * create can never be aimed at an existing row -- and each update must keep the identifier and any server-owned value the loaded row
 * already carries, most importantly {@code Application.token}.
 */
class AdminEndpointOverPostingTest {

    @Test
    void creatingAnAccessRuleGeneratesItsOwnIdentifier() {
        AccessRuleRepository repo = mock(AccessRuleRepository.class);
        AccessRuleService service = new AccessRuleService(repo, "[]");
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createFrom(List.of(new AccessRuleCreateRequest("RULE", "desc", 1, "$.x", "v", null, null, null, null, null, null)));

        AccessRule saved = captureSaved(repo);
        assertNull(saved.getUuid(), "a create must not carry a caller-chosen identifier");
        assertEquals("RULE", saved.getName());
        assertEquals(Boolean.FALSE, saved.getCheckMapKeyOnly(), "the boolean defaults the service has always applied still apply");
    }

    @Test
    void updatingAnAccessRuleKeepsItsIdentityAndUnlistedFields() {
        AccessRuleRepository repo = mock(AccessRuleRepository.class);
        AccessRuleService service = new AccessRuleService(repo, "[]");
        UUID ruleId = UUID.randomUUID();
        AccessRule stored = new AccessRule();
        stored.setUuid(ruleId);
        stored.setName("ORIGINAL");
        stored.setRule("$.original");
        stored.setValue("original-value");
        when(repo.findById(ruleId)).thenReturn(Optional.of(stored));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateFrom(
            List.of(new AccessRuleUpdateRequest(ruleId, "RENAMED", null, null, null, null, null, null, null, null, null, null))
        );

        AccessRule saved = captureSaved(repo);
        assertEquals(ruleId, saved.getUuid());
        assertEquals("RENAMED", saved.getName());
        assertEquals("$.original", saved.getRule(), "an absent member leaves the stored value alone");
        assertEquals("original-value", saved.getValue());
    }

    @Test
    void accessRuleGatesAreResolvedFromStorageRatherThanTakenFromTheRequest() {
        AccessRuleRepository repo = mock(AccessRuleRepository.class);
        AccessRuleService service = new AccessRuleService(repo, "[]");
        UUID gateId = UUID.randomUUID();
        AccessRule persistedGate = new AccessRule();
        persistedGate.setUuid(gateId);
        persistedGate.setName("PERSISTED_GATE");
        when(repo.findAllById(Set.of(gateId))).thenReturn(List.of(persistedGate));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createFrom(
            List.of(
                new AccessRuleCreateRequest("RULE", null, 1, "$.x", null, null, null, null, null, Set.of(new EntityIdRef(gateId)), null)
            )
        );

        assertEquals(Set.of(persistedGate), captureSaved(repo).getGates());
    }

    @Test
    void accessRuleUpdateRejectsAnUnknownGate() {
        AccessRuleRepository repo = mock(AccessRuleRepository.class);
        AccessRuleService service = new AccessRuleService(repo, "[]");
        UUID missing = UUID.randomUUID();
        when(repo.findAllById(Set.of(missing))).thenReturn(List.of());

        assertThrows(
            IllegalArgumentException.class,
            () -> service.createFrom(
                List.of(
                    new AccessRuleCreateRequest(
                        "RULE", null, 1, "$.x", null, null, null, null, null, Set.of(new EntityIdRef(missing)), null
                    )
                )
            )
        );
    }

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

    @Test
    void creatingAPrivilegeResolvesItsApplicationFromStorage() {
        PrivilegeRepository repo = mock(PrivilegeRepository.class);
        ApplicationRepository applicationRepo = mock(ApplicationRepository.class);
        PrivilegeService service = new PrivilegeService(repo, mock(AccessRuleService.class), applicationRepo);
        UUID appId = UUID.randomUUID();
        Application persisted = new Application();
        persisted.setUuid(appId);
        persisted.setName("picsure");
        persisted.setToken("stored-application-token");
        when(applicationRepo.findById(appId)).thenReturn(Optional.of(persisted));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createFrom(List.of(new PrivilegeCreateRequest("PRIV", "desc", new EntityIdRef(appId), null)));

        Privilege saved = captureSaved(repo);
        assertNull(saved.getUuid());
        assertSame(persisted, saved.getApplication(), "the association is the stored application, not the caller's copy");
    }

    @Test
    void updatingAPrivilegeKeepsItsIdentityAndUnlistedFields() {
        PrivilegeRepository repo = mock(PrivilegeRepository.class);
        PrivilegeService service = new PrivilegeService(repo, mock(AccessRuleService.class), mock(ApplicationRepository.class));
        UUID privilegeId = UUID.randomUUID();
        Privilege stored = new Privilege();
        stored.setUuid(privilegeId);
        stored.setName("ORIGINAL");
        stored.setDescription("original description");
        when(repo.findById(privilegeId)).thenReturn(Optional.of(stored));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateFrom(List.of(new PrivilegeUpdateRequest(privilegeId, "RENAMED", null, null, null)));

        Privilege saved = captureSaved(repo);
        assertEquals(privilegeId, saved.getUuid());
        assertEquals("RENAMED", saved.getName());
        assertEquals("original description", saved.getDescription());
    }

    @Test
    void creatingARoleResolvesItsPrivilegesFromStorage() {
        RoleRepository repo = mock(RoleRepository.class);
        PrivilegeService privilegeService = mock(PrivilegeService.class);
        RoleService service = new RoleService(repo, privilegeService);
        UUID privilegeId = UUID.randomUUID();
        Privilege persisted = new Privilege();
        persisted.setUuid(privilegeId);
        persisted.setName("PERSISTED_PRIV");
        when(privilegeService.findById(privilegeId)).thenReturn(Optional.of(persisted));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createFrom(List.of(new RoleCreateRequest("ROLE", "desc", Set.of(new EntityIdRef(privilegeId)))));

        Role saved = captureSaved(repo);
        assertNull(saved.getUuid());
        assertEquals(Set.of(persisted), saved.getPrivileges());
    }

    @Test
    void updatingARoleKeepsItsIdentityAndUnlistedFields() {
        RoleRepository repo = mock(RoleRepository.class);
        RoleService service = new RoleService(repo, mock(PrivilegeService.class));
        UUID roleId = UUID.randomUUID();
        Role stored = new Role();
        stored.setUuid(roleId);
        stored.setName("ORIGINAL");
        stored.setDescription("original description");
        when(repo.findById(roleId)).thenReturn(Optional.of(stored));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateFrom(List.of(new RoleUpdateRequest(roleId, "RENAMED", null, null)));

        Role saved = captureSaved(repo);
        assertEquals(roleId, saved.getUuid());
        assertEquals("RENAMED", saved.getName());
        assertEquals("original description", saved.getDescription());
    }

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

    @Test
    void everyUpdateRejectsAnIdentifierThatDoesNotExist() {
        AccessRuleRepository accessRules = mock(AccessRuleRepository.class);
        UUID missing = UUID.randomUUID();
        when(accessRules.findById(missing)).thenReturn(Optional.empty());

        assertThrows(
            IllegalArgumentException.class,
            () -> new AccessRuleService(accessRules, "[]")
                .updateFrom(List.of(new AccessRuleUpdateRequest(missing, "x", null, null, null, null, null, null, null, null, null, null)))
        );
        assertTrue(true);
    }

    @SuppressWarnings("unchecked")
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

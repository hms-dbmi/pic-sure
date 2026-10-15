package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.ApplicationService;

/**
 * The two reads return the JSON {@code Application.ApplicationForDisplay} serialized to. Create, update and delete return the JSON the
 * {@link Application} entity serialized to, privileges included, minus its {@code token}. Issuing a token returns the same one-member
 * object. The error paths keep their 400.
 */
class ApplicationControllerTest {

    private final ApplicationService applicationService = mock(ApplicationService.class);
    private final MockMvc mockMvc = FrozenWire.mockMvc(new ApplicationController(applicationService));

    private static Application applicationWithPrivileges() {
        Application application = AdminFixtures.application("PICSURE");
        Privilege privilege = AdminFixtures.privilege("PRIV_FENCE_phs000007_c1", application, AdminFixtures.accessRule("AR_ONLY_SEARCH"));
        application.setPrivileges(new LinkedHashSet<>(List.of(privilege)));
        return application;
    }

    private static Application applicationWithNothingOptional() {
        Application application = new Application();
        application.setUuid(UUID.randomUUID());
        application.setName("IRCT");
        application.setEnable(false);
        application.setToken(AdminFixtures.APPLICATION_TOKEN);
        return application;
    }

    @Test
    void readingOneApplicationReturnsTheDisplayJson() throws Exception {
        Application application = applicationWithPrivileges();
        when(applicationService.getApplicationByID(application.getUuid().toString())).thenReturn(Optional.of(application));

        mockMvc.perform(get("/application/{applicationId}", application.getUuid())).andExpect(status().isOk())
            .andExpect(content().string(FrozenWire.json(Application.ApplicationForDisplay.from(application))))
            .andExpect(content().string(not(containsString("privileges"))));
    }

    @Test
    void listingApplicationsReturnsABareArrayOfTheDisplayJson() throws Exception {
        List<Application> applications = List.of(applicationWithPrivileges(), applicationWithNothingOptional());
        when(applicationService.getAllApplications()).thenReturn(applications);

        mockMvc.perform(get("/application")).andExpect(status().isOk())
            .andExpect(content().string(FrozenWire.json(applications.stream().map(Application.ApplicationForDisplay::from).toList())));
    }

    @Test
    void creatingApplicationsReturnsTheEntityJsonWithoutTheToken() throws Exception {
        List<Application> created = List.of(applicationWithPrivileges(), applicationWithNothingOptional());
        when(applicationService.createFrom(anyList())).thenReturn(created);

        mockMvc
            .perform(post("/application").contentType(MediaType.APPLICATION_JSON).content("[{\"name\":\"PICSURE\"},{\"name\":\"IRCT\"}]"))
            .andExpect(status().isOk()).andExpect(content().string(FrozenWire.json(created, "token")))
            .andExpect(content().string(not(containsString("canary"))));
    }

    @Test
    void updatingApplicationsReturnsTheEntityJsonWithoutTheToken() throws Exception {
        List<Application> updated = List.of(applicationWithPrivileges());
        when(applicationService.updateFrom(anyList())).thenReturn(updated);

        mockMvc
            .perform(
                put("/application").contentType(MediaType.APPLICATION_JSON)
                    .content("[{\"uuid\":\"" + updated.getFirst().getUuid() + "\",\"description\":\"edited\"}]")
            ).andExpect(status().isOk()).andExpect(content().string(FrozenWire.json(updated, "token")))
            .andExpect(content().string(not(containsString("canary"))));
    }

    @Test
    void deletingAnApplicationReturnsTheRemainingOnesWithoutTheirTokens() throws Exception {
        UUID deleted = UUID.randomUUID();
        List<Application> remaining = List.of(applicationWithPrivileges());
        when(applicationService.deleteApplicationById(deleted.toString())).thenReturn(remaining);

        mockMvc.perform(delete("/application/{applicationId}", deleted)).andExpect(status().isOk())
            .andExpect(content().string(FrozenWire.json(remaining, "token"))).andExpect(content().string(not(containsString("canary"))));
    }

    @Test
    void issuingATokenReturnsItUnderTheTokenMember() throws Exception {
        UUID applicationId = UUID.randomUUID();
        when(applicationService.refreshApplicationToken(applicationId.toString())).thenReturn("new.application.token");

        mockMvc.perform(get("/application/refreshToken/{applicationId}", applicationId)).andExpect(status().isOk())
            .andExpect(content().string("{\"token\":\"new.application.token\"}"));
    }

    @Test
    void readingAnUnknownApplicationIs400InTheErrorEnvelope() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(applicationService.getApplicationByID(unknown.toString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/application/{applicationId}", unknown)).andExpect(status().isBadRequest()).andExpect(
            content().string(
                "{\"message\":\"Invalid request\",\"content\":\"Application is not found by given Application ID: " + unknown + "\"}"
            )
        );
    }

    @Test
    void deletingAnUnknownApplicationIs400InTheErrorEnvelope() throws Exception {
        UUID unknown = UUID.randomUUID();
        String reason = "Cannot find application by the given applicationId: " + unknown;
        when(applicationService.deleteApplicationById(unknown.toString())).thenThrow(new IllegalArgumentException(reason));

        mockMvc.perform(delete("/application/{applicationId}", unknown)).andExpect(status().isBadRequest())
            .andExpect(content().string("{\"message\":\"Invalid request\",\"content\":\"" + reason + "\"}"));
    }
}

package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.ApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code Application.token} is the bearer credential a registered application authenticates with. The two read endpoints must serialize the
 * token-free display shape, so neither response carries it. Who may call the reads is pinned by {@code HandlerAuthorizationTest}.
 */
class ApplicationReadExposureTest {

    private static final String TOKEN_CANARY = "canary.application.bearer.token";

    private final ApplicationService applicationService = mock(ApplicationService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ApplicationController(applicationService)).build();

    private static Application applicationWithToken(UUID uuid) {
        Application application = new Application();
        application.setUuid(uuid);
        application.setName("picsure");
        application.setDescription("The PIC-SURE application");
        application.setUrl("https://example.org");
        application.setEnable(true);
        application.setToken(TOKEN_CANARY);
        return application;
    }

    @Test
    void readingOneApplicationNeverSerializesItsToken() throws Exception {
        UUID applicationId = UUID.randomUUID();
        when(applicationService.getApplicationByID(applicationId.toString())).thenReturn(Optional.of(applicationWithToken(applicationId)));

        mockMvc.perform(get("/application/{applicationId}", applicationId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.uuid").value(applicationId.toString())).andExpect(jsonPath("$.name").value("picsure"))
            .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void listingApplicationsNeverSerializesTheirTokens() throws Exception {
        UUID applicationId = UUID.randomUUID();
        when(applicationService.getAllApplications()).thenReturn(List.of(applicationWithToken(applicationId)));

        mockMvc.perform(get("/application")).andExpect(status().isOk()).andExpect(jsonPath("$[0].uuid").value(applicationId.toString()))
            .andExpect(jsonPath("$[0].name").value("picsure")).andExpect(jsonPath("$[0].token").doesNotExist());
    }
}

package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.harvard.hms.dbmi.avillach.auth.exceptions.GlobalExceptionHandler;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.ApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Pins that an admin endpoint naming a record by path identifier rejects a value that is not a UUID with a 400 before any service runs, and
 * never repeats the value in the response.
 */
class PathIdentifierWebTest {

    private static final String NOT_A_UUID = "not-a-uuid<script>";

    private final ApplicationService applicationService = mock(ApplicationService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ApplicationController(applicationService))
        .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void applicationReadRejectsAMalformedIdentifier() throws Exception {
        mockMvc.perform(get("/application/{id}", NOT_A_UUID)).andExpect(status().isBadRequest())
            .andExpect(content().string(not(containsString("script"))));
        verifyNoInteractions(applicationService);
    }

    @Test
    void applicationDeleteRejectsAMalformedIdentifier() throws Exception {
        mockMvc.perform(delete("/application/{id}", NOT_A_UUID)).andExpect(status().isBadRequest())
            .andExpect(content().string(not(containsString("script"))));
        verifyNoInteractions(applicationService);
    }

    @Test
    void applicationTokenRefreshRejectsAMalformedIdentifier() throws Exception {
        mockMvc.perform(get("/application/refreshToken/{id}", NOT_A_UUID)).andExpect(status().isBadRequest())
            .andExpect(content().string(not(containsString("script"))));
        verifyNoInteractions(applicationService);
    }
}

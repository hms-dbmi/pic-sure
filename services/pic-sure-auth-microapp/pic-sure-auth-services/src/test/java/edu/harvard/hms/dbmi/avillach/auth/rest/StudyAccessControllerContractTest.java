package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.auth.exceptions.GlobalExceptionHandler;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.StudyAccessService;

/** The wire contract of {@code POST /studyAccess}, which used to bind a raw String under a JSON content type. */
class StudyAccessControllerContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StudyAccessService studyAccessService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        studyAccessService = mock(StudyAccessService.class);
        mockMvc =
            MockMvcBuilders.standaloneSetup(new StudyAccessController(studyAccessService)).setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new StringHttpMessageConverter(), new MappingJackson2HttpMessageConverter(MAPPER)).build();
    }

    @Test
    void bindsTheStudyIdentifierFromAJsonObject() throws Exception {
        when(studyAccessService.addStudyAccess("phs000001.c1")).thenReturn("Role 'MANUAL_phs000001_c1' successfully created");

        MvcResult result = mockMvc
            .perform(post("/studyAccess").contentType("application/json").content("{\"studyIdentifier\":\"phs000001.c1\"}"))
            .andExpect(status().isOk()).andReturn();

        assertEquals("Role 'MANUAL_phs000001_c1' successfully created", result.getResponse().getContentAsString());
    }

    /** The service signals failure with an "Error:"-prefixed string; that becomes the uniform error contract, message preserved. */
    @Test
    void serviceLevelFailureBecomesTheUniformErrorContract() throws Exception {
        when(studyAccessService.addStudyAccess("nope")).thenReturn("Error: Could not find study with the provided identifier");

        MvcResult result = mockMvc.perform(post("/studyAccess").contentType("application/json").content("{\"studyIdentifier\":\"nope\"}"))
            .andExpect(status().isInternalServerError()).andReturn();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertEquals("Error: Could not find study with the provided identifier", body.get("message").asText());
    }
}

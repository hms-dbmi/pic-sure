package edu.harvard.dbmi.avillach.dictionary.concept;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import edu.harvard.hms.dbmi.avillach.commons.security.InternalTokenFilter;

/**
 * With no inbound token configured, every {@code POST /internal/concepts/export-metadata} call is rejected with 403, including one whose
 * header is blank or matches the blank configuration, and none reaches the repository.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "picsure.internal.token=")
class InternalConceptExportBlankTokenTest {

    private static final String URL = "/internal/concepts/export-metadata";
    private static final String BODY = "{\"conceptPaths\":[\"a\"],\"metaKeys\":[\"drs_uri\"]}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConceptRepository conceptRepository;

    @Test
    void blankConfiguredTokenRejectsARequestWithNoHeader() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorType").value("FORBIDDEN"));

        verifyNoInteractions(conceptRepository);
    }

    @Test
    void blankConfiguredTokenRejectsABlankHeader() throws Exception {
        mockMvc.perform(post(URL).header(InternalTokenFilter.HEADER, "").contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isForbidden());

        verifyNoInteractions(conceptRepository);
    }

    @Test
    void blankConfiguredTokenRejectsAnyHeaderValue() throws Exception {
        mockMvc.perform(post(URL).header(InternalTokenFilter.HEADER, "anything").contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isForbidden());

        verifyNoInteractions(conceptRepository);
    }
}

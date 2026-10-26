package edu.harvard.hms.dbmi.avillach.query.search;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;

/**
 * With no inbound token configured, every {@code /internal/search/{backend}} call is rejected, including one whose header is blank or
 * matches the blank configuration.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = "picsure.internal.token=")
class InternalSearchControllerBlankTokenTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OperationsClient operationsClient;

    @Test
    void blankConfiguredTokenRejectsARequestWithNoHeader() throws Exception {
        mockMvc.perform(post("/internal/search/auth").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"q\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void blankConfiguredTokenRejectsABlankHeader() throws Exception {
        mockMvc.perform(
            post("/internal/search/auth").header(InternalTokenFilter.HEADER, "").contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\"}")
        ).andExpect(status().isForbidden());
    }

    @Test
    void blankConfiguredTokenRejectsAnyHeaderValue() throws Exception {
        mockMvc.perform(
            post("/internal/search/auth").header(InternalTokenFilter.HEADER, "anything").contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\"}")
        ).andExpect(status().isForbidden());
    }
}

package edu.harvard.hms.dbmi.avillach.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A deployment with no open backend publishes no open access document, while the authorized document is unaffected.
 */
@SpringBootTest(properties = "hpds.open-url=")
@AutoConfigureMockMvc
class OpenAccessDocumentDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void noOpenBackendMeansNoOpenAccessDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs/open")).andExpect(status().isNotFound());
        String auth = mockMvc.perform(get("/v3/api-docs/auth")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(auth).contains("/hpds/auth/query/sync").doesNotContain("/hpds/open/");
    }
}

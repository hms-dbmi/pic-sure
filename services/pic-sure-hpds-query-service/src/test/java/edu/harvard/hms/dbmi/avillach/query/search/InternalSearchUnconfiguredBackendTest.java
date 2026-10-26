package edu.harvard.hms.dbmi.avillach.query.search;

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
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;

/**
 * With both HPDS backend URLs blank, which is how a deployment without a backend presents, an authorized {@code POST
 * /internal/search/{backend}} is refused by backend selection with 503 {@code backend_not_configured}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {"picsure.internal.token=inbound-secret", "hpds.auth-url=", "hpds.open-url="})
class InternalSearchUnconfiguredBackendTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OperationsClient operationsClient;

    @Test
    void unconfiguredAuthBackendIs503() throws Exception {
        perform("auth");
    }

    @Test
    void unconfiguredOpenBackendIs503() throws Exception {
        perform("open");
    }

    private void perform(String backend) throws Exception {
        mockMvc.perform(
            post("/internal/search/" + backend).header(InternalTokenFilter.HEADER, "inbound-secret").contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"age\"}")
        ).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.errorType").value("backend_not_configured"));
    }
}

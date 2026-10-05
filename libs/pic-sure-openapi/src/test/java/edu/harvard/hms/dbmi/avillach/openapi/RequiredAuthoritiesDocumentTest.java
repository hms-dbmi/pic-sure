package edu.harvard.hms.dbmi.avillach.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.annotations.Operation;

/** The auto-configuration registers the customizer, so a served document carries the authorities without the service wiring anything. */
@SpringBootTest(classes = {OpenApiTestApplication.class, RequiredAuthoritiesDocumentTest.GuardedController.class})
@AutoConfigureMockMvc
class RequiredAuthoritiesDocumentTest {

    @RestController
    static class GuardedController {
        @Operation(summary = "Create a thing", description = "POST a thing")
        @PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")
        @PostMapping("/things")
        public String create() {
            return "";
        }

        @Operation(summary = "Read the caller", description = "GET the caller")
        @GetMapping("/me")
        public String me() {
            return "";
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void servedDocumentCarriesTheAuthorities() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode paths = new ObjectMapper().readTree(body).path("paths");

        assertThat(paths.path("/things").path("post").path("description").asText())
            .isEqualTo("POST a thing\n\nRequired authorities: ADMIN, SUPER_ADMIN.");
        assertThat(paths.path("/me").path("get").path("description").asText()).isEqualTo("GET the caller");
    }
}

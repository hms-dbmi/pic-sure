package edu.harvard.hms.dbmi.avillach.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import edu.harvard.hms.dbmi.avillach.commons.openapi.OpenApiExport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regenerates {@code docs/api/pic-sure-auth-services.openapi.json} from the running context and asserts the contract invariants that must
 * hold in it. The document is a build artifact of record, committed alongside the code: a diff in it is a diff in the public wire, and this
 * test is what makes that visible in review.
 *
 * <p>This is the only service whose context needs coaxing to boot in a test: PSAMA owns a MySQL schema, several of its services do
 * DB-touching startup work in {@code @PostConstruct}/{@code @EventListener} methods, and {@code APPLICATION_CLIENT_SECRET} has no default.
 * The properties below stand an empty H2 schema up in memory and supply a placeholder secret. Nothing here changes production configuration
 * -- it exists so the HTTP surface can be described without a MySQL server.
 *
 * <p>springdoc is a TEST-scope dependency, so the endpoint this reads exists only while this test runs -- PSAMA ships no live
 * {@code /v3/api-docs}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        // NON_KEYWORDS=USER because the User entity maps to a table named "user", which H2 reserves and MySQL does not.
        "spring.datasource.url=jdbc:h2:mem:psama-openapi-export;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,VALUE,KEY",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=create-drop",
        "APPLICATION_CLIENT_SECRET=openapi-export-placeholder-secret", "management.endpoints.web.exposure.include=none"}
)
@AutoConfigureMockMvc(addFilters = false)
class OpenApiExportTest {

    private static final String ARTIFACT = "pic-sure-auth-services";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exportsTheDocumentAndHoldsTheSharedContractInvariants() throws Exception {
        String raw = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        JsonNode document = OpenApiExport.write(ARTIFACT, raw);

        OpenApiExport.assertSharedContractInvariants(document);

        // Representative shape: /token/inspect is the gateway's hop into PSAMA. This is the wire the gateway and
        // PSAMA must agree on, so both halves are pinned.
        JsonNode inspect = OpenApiExport.operation(document, "/token/inspect", "post");
        OpenApiExport.assertAcceptsSchema(inspect, "application/json", "IntrospectionRequest");
        OpenApiExport.assertRespondsWith(inspect, "application/json", "TokenIntrospectionResponse");
        // TokenIntrospectionResponse @JsonUnwrapped's the contract, so the document must show the contract's fields
        // FLATTENED at the top level plus the unmodelled `message` -- exactly what the gateway reads back as an
        // IntrospectionResponse. A nested `introspection` object here would mean the wire had silently changed.
        OpenApiExport.assertSchemaProperties(
            document, "TokenIntrospectionResponse",
            List.of("active", "email", "message", "privileges", "query", "roles", "sub", "token", "tokenRefreshed", "userId")
        );
    }
}

package edu.harvard.hms.dbmi.avillach.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code GatewayUser} reaches a handler from the gateway's {@code X-User-*} headers through {@code GatewayUserArgumentResolver}, never from
 * anything the client sends. Left unhidden, springdoc reads the bare handler parameter as client input and publishes it as a required
 * {@code user} query parameter on every named-dataset operation, advertising a request contract the service does not read. These assertions
 * pin what a client actually supplies: the request body and the path variable, and nothing else.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NamedDatasetRequestSchemaTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode document;

    @BeforeEach
    void fetchDocument() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        document = objectMapper.readTree(body);
    }

    @ParameterizedTest
    @CsvSource({"post, /dataset/named", "put, /dataset/named/{id}"})
    void writeOperationsDeclareTheirOwnRequestDtoAsTheBody(String method, String path) {
        JsonNode schema =
            document.path("paths").path(path).path(method).path("requestBody").path("content").path("application/json").path("schema");

        assertThat(schema.path("$ref").asText()).isEqualTo("#/components/schemas/NamedDatasetRequestDto");
    }

    @ParameterizedTest
    @CsvSource(
        {"get, /dataset/named, ", "post, /dataset/named, ", "get, /dataset/named/{id}, id", "put, /dataset/named/{id}, id",
            "delete, /dataset/named/{id}, id"}
    )
    void operationsPublishOnlyTheParametersAClientSupplies(String method, String path, String expected) {
        List<String> names = new ArrayList<>();
        document.path("paths").path(path).path(method).path("parameters").forEach(p -> names.add(p.path("name").asText()));

        assertThat(names).containsExactlyElementsOf(expected == null ? List.of() : List.of(expected));
    }

    @Test
    void noOperationAnywhereReferencesTheGatewayResolvedIdentity() {
        List<String> offenders = new ArrayList<>();
        document.path("paths").fields().forEachRemaining(
            pathEntry -> pathEntry.getValue().fields()
                .forEachRemaining(operationEntry -> operationEntry.getValue().path("parameters").forEach(parameter -> {
                    if (parameter.path("schema").path("$ref").asText().endsWith("/GatewayUser")) {
                        offenders.add(operationEntry.getKey() + " " + pathEntry.getKey() + " -> " + parameter.path("name").asText());
                    }
                }))
        );

        assertThat(offenders).isEmpty();
    }

    @Test
    void theIdentityTypeIsNotPublishedAsASchema() {
        assertThat(document.path("components").path("schemas").has("GatewayUser")).isFalse();
    }
}

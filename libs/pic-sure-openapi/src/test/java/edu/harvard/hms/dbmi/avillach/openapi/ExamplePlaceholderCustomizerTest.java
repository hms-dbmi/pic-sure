package edu.harvard.hms.dbmi.avillach.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.tags.Tag;

/**
 * Which parts of a document the customizer resolves, what a placeholder becomes with and without a configured value, and that a value
 * substituted into a JSON example comes out escaped.
 */
class ExamplePlaceholderCustomizerTest {

    private static final String PATH_PLACEHOLDER = "${picsure.openapi.examples.concept-path:\\demo\\SEX\\}";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void placeholdersFallBackToTheirDefaultsWhenNothingIsConfigured() throws Exception {
        OpenAPI document = document();

        new ExamplePlaceholderCustomizer(new MockEnvironment()).customise(document);

        Operation operation = document.getPaths().get("/concepts").getPost();
        assertThat(operation.getDescription()).isEqualTo("Look up \\demo\\SEX\\ with curl.");
        assertThat(operation.getParameters().get(0).getExample()).isEqualTo("demo");
        JsonNode body = (JsonNode) operation.getRequestBody().getContent().get("application/json").getExamples().get("one").getValue();
        assertThat(body.path("select").get(0).asText()).isEqualTo("\\demo\\SEX\\");
    }

    @Test
    void configuredValuesReplaceTheDefaultsEverywhere() throws Exception {
        OpenAPI document = document();
        MockEnvironment environment = new MockEnvironment().withProperty("picsure.openapi.examples.concept-path", "\\phs1\\\"AGE\"\\")
            .withProperty("picsure.openapi.examples.dataset", "phs1");

        new ExamplePlaceholderCustomizer(environment).customise(document);

        Operation operation = document.getPaths().get("/concepts").getPost();
        assertThat(document.getTags().get(0).getDescription()).isEqualTo("Concepts in phs1.");
        assertThat(operation.getSummary()).isEqualTo("Search phs1");
        assertThat(operation.getDescription()).isEqualTo("Look up \\phs1\\\"AGE\"\\ with curl.");
        assertThat(operation.getParameters().get(0).getExample()).isEqualTo("phs1");
        MediaType json = operation.getRequestBody().getContent().get("application/json");
        Example example = json.getExamples().get("one");
        assertThat(example.getSummary()).isEqualTo("Select from phs1");
        assertThat(mapper.writeValueAsString(example.getValue()))
            .isEqualTo("{\"select\":[\"\\\\phs1\\\\\\\"AGE\\\"\\\\\"],\"limit\":1,\"literal\":\"$TOKEN\"}");
        assertThat(operation.getRequestBody().getContent().get("text/plain").getSchema().getExample()).isEqualTo("\\phs1\\\"AGE\"\\");
    }

    @Test
    void anUnresolvablePlaceholderIsLeftAsItIs() {
        OpenAPI document = new OpenAPI().paths(
            new Paths().addPathItem("/x", new PathItem().get(new Operation().description("Uses ${picsure.openapi.examples.missing}.")))
        );

        new ExamplePlaceholderCustomizer(new MockEnvironment()).customise(document);

        assertThat(document.getPaths().get("/x").getGet().getDescription()).isEqualTo("Uses ${picsure.openapi.examples.missing}.");
    }

    private OpenAPI document() throws Exception {
        String dataset = "${picsure.openapi.examples.dataset:demo}";
        JsonNode body =
            mapper.readTree("{\"select\":[\"" + PATH_PLACEHOLDER.replace("\\", "\\\\") + "\"],\"limit\":1,\"literal\":\"$TOKEN\"}");
        Operation operation = new Operation().summary("Search " + dataset).description("Look up " + PATH_PLACEHOLDER + " with curl.")
            .addParametersItem(new Parameter().name("dataset").example(dataset)).requestBody(
                new RequestBody().content(
                    new Content().addMediaType(
                        "application/json",
                        new MediaType().examples(Map.of("one", new Example().summary("Select from " + dataset).value(body)))
                    ).addMediaType("text/plain", new MediaType().schema(new StringSchema().example(PATH_PLACEHOLDER)))
                )
            );
        return new OpenAPI().addTagsItem(new Tag().name("Concepts").description("Concepts in " + dataset + "."))
            .paths(new Paths().addPathItem("/concepts", new PathItem().post(operation)));
    }
}

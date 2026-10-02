package edu.harvard.dbmi.avillach.dictionary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.openapi.OpenApiConfiguration;
import edu.harvard.hms.dbmi.avillach.openapi.OpenApiDocumentAssertions;

/**
 * The live document is served unauthenticated, names this service, carries the bearer scheme, and covers every visible handler with a
 * summarised operation. An endpoint cannot vanish from the document, and the annotation pass cannot skip one, without failing here. Each
 * handler's request and response schema is pinned too, with the fields the frontend and the Python adapter read and the bare-array shapes
 * they depend on.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocumentTest {

    private static final String SCHEMA_PREFIX = "#/components/schemas/";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void documentCoversEveryVisibleHandler() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andReturn().getResponse().getContentAsString();
        JsonNode document = objectMapper.readTree(body);

        assertThat(document.path("info").path("title").asText()).isEqualTo("dictionary");
        assertThat(document.path("info").path("version").asText()).isNotBlank();
        assertThat(document.path("components").path("securitySchemes").has(OpenApiConfiguration.BEARER_SCHEME)).isTrue();
        assertThat(document.path("security").get(0).has(OpenApiConfiguration.BEARER_SCHEME)).isTrue();
        OpenApiDocumentAssertions.assertCovers(document, handlerMapping);
    }

    @Test
    void conceptSearchAndDumpReturnTheConceptPage() throws Exception {
        JsonNode document = document();

        OpenApiDocumentAssertions.assertRequestSchema(document, "post", "/concepts", "Filter");
        OpenApiDocumentAssertions.assertResponseSchema(document, "post", "/concepts", "200", "ConceptPage");
        OpenApiDocumentAssertions.assertResponseSchema(document, "get", "/concepts/dump", "200", "ConceptPage");
        OpenApiDocumentAssertions.assertSchemaHasFields(
            document, "ConceptPage", "content", "pageable", "totalElements", "totalPages", "last", "numberOfElements", "sort", "first",
            "size", "number", "empty"
        );
        OpenApiDocumentAssertions
            .assertSchemaHasFields(document, "ConceptPageable", "pageNumber", "pageSize", "sort", "offset", "paged", "unpaged");
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "ConceptSort", "unsorted", "sorted", "empty");
        OpenApiDocumentAssertions.assertSchemaDocumented(document, "ConceptPage", "ConceptPageable", "ConceptSort");
        assertThat(document.path("components").path("schemas").has("PageConcept")).isFalse();
    }

    @Test
    void conceptIsOneOfTwoShapesDiscriminatedByType() throws Exception {
        JsonNode document = document();
        JsonNode concept = schema(document, "Concept");

        assertThat(concept.path("description").asText()).isNotBlank();
        assertThat(concept.has("oneOf")).isFalse();
        assertThat(concept.path("discriminator").path("propertyName").asText()).isEqualTo("type");
        assertThat(concept.path("discriminator").path("mapping").path("Categorical").asText())
            .isEqualTo(SCHEMA_PREFIX + "CategoricalConcept");
        assertThat(concept.path("discriminator").path("mapping").path("Continuous").asText())
            .isEqualTo(SCHEMA_PREFIX + "ContinuousConcept");
        for (String subtype : List.of("CategoricalConcept", "ContinuousConcept")) {
            assertThat(schema(document, subtype).path("allOf").path(0).path("$ref").asText()).isEqualTo(SCHEMA_PREFIX + "Concept");
            JsonNode type = properties(document, subtype).path("type");
            assertThat(enumValues(type)).containsExactly("Categorical", "Continuous");
            assertThat(type.path("x-enum-descriptions")).hasSize(2);
            OpenApiDocumentAssertions.assertSchemaHasFields(
                document, subtype, "conceptPath", "name", "display", "dataset", "description", "allowFiltering", "studyAcronym", "meta",
                "children", "table", "study", "type"
            );
        }
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "CategoricalConcept", "values");
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "ContinuousConcept", "min", "max");
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "Dataset", "ref", "fullName", "abbreviation", "description", "meta");
        OpenApiDocumentAssertions.assertSchemaDocumented(document, "CategoricalConcept", "ContinuousConcept", "Dataset");

        assertIsEitherConcept(responseSchema(document, "post", "/concepts/detail/{dataset}"));
        assertIsEitherConcept(responseSchema(document, "post", "/concepts/tree/{dataset}"));
        assertIsBareArrayOfEitherConcept(responseSchema(document, "post", "/concepts/detail"));
        assertIsBareArrayOfEitherConcept(responseSchema(document, "post", "/concepts/hierarchy/{dataset}"));
        assertIsBareArrayOfEitherConcept(responseSchema(document, "get", "/concepts/tree"));
        assertIsBareArrayOfEitherConcept(schema(document, "ConceptPage").path("properties").path("content"));
        assertIsBareArrayOfEitherConcept(properties(document, "CategoricalConcept").path("children"));
        assertIsEitherConcept(properties(document, "ContinuousConcept").path("table"));
    }

    @Test
    void conceptPathBodiesAreRawStrings() throws Exception {
        JsonNode document = document();

        for (String path : List.of("/concepts/detail/{dataset}", "/concepts/tree/{dataset}", "/concepts/hierarchy/{dataset}")) {
            JsonNode body = requestSchema(document, "post", path);
            assertThat(body.path("type").asText()).as(path).isEqualTo("string");
            assertThat(body.path("description").asText()).as(path).contains("raw string body");
            assertThat(body.path("example").asText()).as(path).isEqualTo("\\demographics\\AGE\\");
        }
        JsonNode bulk = requestSchema(document, "post", "/concepts/detail");
        assertThat(bulk.path("type").asText()).isEqualTo("array");
        assertThat(bulk.path("items").path("type").asText()).isEqualTo("string");
        assertThat(bulk.path("description").asText()).isNotBlank();
        assertThat(bulk.path("example")).hasSize(2);
    }

    @Test
    void facetsDashboardAndDrawerAreDocumented() throws Exception {
        JsonNode document = document();

        OpenApiDocumentAssertions.assertRequestSchema(document, "post", "/facets", "Filter");
        OpenApiDocumentAssertions.assertBareArrayOf(document, "post", "/facets", "200", "FacetCategory");
        OpenApiDocumentAssertions.assertResponseSchema(document, "get", "/facets/{facetCategory}/{facet}", "200", "Facet");
        OpenApiDocumentAssertions.assertResponseSchema(document, "get", "/dashboard", "200", "Dashboard");
        OpenApiDocumentAssertions.assertBareArrayOf(document, "get", "/dashboard-drawer", "200", "DashboardDrawer");
        OpenApiDocumentAssertions.assertResponseSchema(document, "get", "/dashboard-drawer/{id}", "200", "DashboardDrawer");
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "Filter", "facets", "search", "consents");
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "FacetCategory", "name", "display", "description", "facets");
        OpenApiDocumentAssertions.assertSchemaHasFields(
            document, "Facet", "name", "display", "description", "fullName", "count", "children", "category", "meta"
        );
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "Dashboard", "columns", "rows");
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "DashboardColumn", "label", "dataElement");
        OpenApiDocumentAssertions.assertSchemaHasFields(
            document, "DashboardDrawer", "datasetId", "studyFullname", "studyAbbreviation", "consentGroups", "studySummary", "studyFocus",
            "studyDesign", "sponsor"
        );
        OpenApiDocumentAssertions
            .assertSchemaDocumented(document, "Filter", "Facet", "FacetCategory", "Dashboard", "DashboardColumn", "DashboardDrawer");
        JsonNode rows = schema(document, "Dashboard").path("properties").path("rows");
        assertThat(rows.path("description").asText()).contains("dataElement");
        assertThat(rows.path("items").path("additionalProperties").path("type").asText()).isEqualTo("string");
    }

    private JsonNode document() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static JsonNode schema(JsonNode document, String schemaName) {
        JsonNode schema = document.path("components").path("schemas").path(schemaName);
        assertThat(schema.isObject()).as("schema %s", schemaName).isTrue();
        return schema;
    }

    private static JsonNode properties(JsonNode document, String schemaName) {
        JsonNode schema = schema(document, schemaName);
        return schema.has("allOf") ? schema.path("allOf").path(1).path("properties") : schema.path("properties");
    }

    private static JsonNode requestSchema(JsonNode document, String method, String path) {
        JsonNode schema =
            document.path("paths").path(path).path(method).path("requestBody").path("content").path("application/json").path("schema");
        assertThat(schema.isObject()).as("request schema of %s %s", method, path).isTrue();
        return schema;
    }

    private static JsonNode responseSchema(JsonNode document, String method, String path) {
        JsonNode content = document.path("paths").path(path).path(method).path("responses").path("200").path("content");
        assertThat(content).as("response media types of %s %s", method, path).hasSize(1);
        return content.elements().next().path("schema");
    }

    private static List<String> enumValues(JsonNode property) {
        List<String> values = new ArrayList<>();
        property.path("enum").forEach(value -> values.add(value.asText()));
        return values;
    }

    private static void assertIsEitherConcept(JsonNode schema) {
        List<String> refs = new ArrayList<>();
        schema.path("oneOf").forEach(option -> refs.add(option.path("$ref").asText()));
        assertThat(refs).containsExactly(SCHEMA_PREFIX + "CategoricalConcept", SCHEMA_PREFIX + "ContinuousConcept");
    }

    private static void assertIsBareArrayOfEitherConcept(JsonNode schema) {
        JsonNode type = schema.path("type");
        assertThat(type.isArray() ? type.path(0).asText() : type.asText()).isEqualTo("array");
        assertIsEitherConcept(schema.path("items"));
    }
}

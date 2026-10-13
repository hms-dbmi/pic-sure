package edu.harvard.hms.dbmi.avillach.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * summarised operation. An endpoint cannot vanish from the document, and the annotation pass cannot skip one, without failing here.
 * {@code InternalQueryController} is {@code @Hidden}, so its {@code /internal/queries} handlers must never appear in {@code paths}, and the
 * records only those handlers use must never appear in {@code components}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocumentTest {

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

        assertThat(document.path("info").path("title").asText()).isEqualTo("pic-sure-operations-service");
        assertThat(document.path("info").path("version").asText()).isNotBlank();
        assertThat(document.path("components").path("securitySchemes").has(OpenApiConfiguration.BEARER_SCHEME)).isTrue();
        assertThat(document.path("security").get(0).has(OpenApiConfiguration.BEARER_SCHEME)).isTrue();
        assertThat(document.path("paths").has("/internal/queries")).isFalse();
        OpenApiDocumentAssertions.assertCovers(document, handlerMapping);
    }

    @Test
    void internalQueryRecordsStayOutOfTheDocument() throws Exception {
        JsonNode schemas = document().path("components").path("schemas");

        assertThat(List.of("SaveQueryRequest", "UpdateQueryRequest", "StoredQuery", "SavedQueryReference", "DispatchResponse"))
            .noneMatch(schemas::has);
    }

    @Test
    void adminWritesPublishTheirRequiredAuthority() throws Exception {
        JsonNode paths =
            objectMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString()).path("paths");

        assertThat(paths.path("/configuration/admin").path("post").path("description").asText())
            .isEqualTo("Required authorities: SUPER_ADMIN.");
        assertThat(paths.path("/configuration/admin/{id}").path("patch").path("description").asText())
            .isEqualTo("Required authorities: SUPER_ADMIN.");
        assertThat(paths.path("/configuration/admin/{id}").path("delete").path("description").asText())
            .isEqualTo("Required authorities: SUPER_ADMIN.");
        assertThat(paths.path("/configuration").path("get").path("description").isMissingNode()).isTrue();
    }

    @Test
    void namedDatasetOperationsPublishTheirSchemas() throws Exception {
        JsonNode document = document();

        OpenApiDocumentAssertions.assertBareArrayOf(document, "get", "/dataset/named", "200", "NamedDatasetDto");
        OpenApiDocumentAssertions.assertRequestSchema(document, "post", "/dataset/named", "NamedDatasetRequestDto");
        OpenApiDocumentAssertions.assertResponseSchema(document, "post", "/dataset/named", "201", "NamedDatasetDto");
        OpenApiDocumentAssertions.assertResponseSchema(document, "get", "/dataset/named/{id}", "200", "NamedDatasetDto");
        OpenApiDocumentAssertions.assertRequestSchema(document, "put", "/dataset/named/{id}", "NamedDatasetRequestDto");
        OpenApiDocumentAssertions.assertResponseSchema(document, "put", "/dataset/named/{id}", "200", "NamedDatasetDto");
        OpenApiDocumentAssertions.assertNoResponseBody(document, "delete", "/dataset/named/{id}", "204");
        OpenApiDocumentAssertions
            .assertSchemaHasFields(document, "NamedDatasetDto", "uuid", "user", "name", "query", "archived", "metadata");
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "NamedDatasetQueryDto", "uuid", "query", "startTime", "status");
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "NamedDatasetRequestDto", "queryId", "name", "archived", "metadata");
        assertThat(document.at("/components/schemas/NamedDatasetDto/properties/query/$ref").asText())
            .isEqualTo("#/components/schemas/NamedDatasetQueryDto");
        assertThat(document.at("/components/schemas/NamedDatasetQueryDto/properties/query/type").asText()).isEqualTo("string");
        assertThat(document.at("/components/schemas/NamedDatasetQueryDto/properties/startTime/type").asText()).isEqualTo("integer");
    }

    @Test
    void configurationOperationsPublishTheirSchemas() throws Exception {
        JsonNode document = document();

        OpenApiDocumentAssertions.assertBareArrayOf(document, "get", "/configuration", "200", "ConfigurationDto");
        assertThat(document.at("/paths/~1configuration/get/parameters/0/name").asText()).isEqualTo("kind");
        assertThat(document.at("/paths/~1configuration/get/parameters/0/in").asText()).isEqualTo("query");
        OpenApiDocumentAssertions.assertResponseSchema(document, "get", "/configuration/{identifier}", "200", "ConfigurationDto");
        OpenApiDocumentAssertions.assertRequestSchema(document, "post", "/configuration/admin", "ConfigurationRequestDto");
        OpenApiDocumentAssertions.assertResponseSchema(document, "post", "/configuration/admin", "200", "ConfigurationDto");
        OpenApiDocumentAssertions.assertRequestSchema(document, "patch", "/configuration/admin/{id}", "ConfigurationRequestDto");
        OpenApiDocumentAssertions.assertResponseSchema(document, "patch", "/configuration/admin/{id}", "200", "ConfigurationDto");
        OpenApiDocumentAssertions.assertResponseSchema(document, "delete", "/configuration/admin/{id}", "200", "ConfigurationDto");
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "ConfigurationDto", "uuid", "name", "kind", "value", "description");
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "ConfigurationRequestDto", "name", "kind", "value", "description");
    }

    @Test
    void namedDatasetAndConfigurationModelsAreDocumented() throws Exception {
        OpenApiDocumentAssertions.assertSchemaDocumented(
            document(), "NamedDatasetDto", "NamedDatasetQueryDto", "NamedDatasetRequestDto", "ConfigurationDto", "ConfigurationRequestDto"
        );
    }

    @Test
    void bannerOperationsPublishTheirSchemas() throws Exception {
        JsonNode document = document();

        OpenApiDocumentAssertions.assertBareArrayOf(document, "get", "/banners/active", "200", "ActiveBannerDto");
        OpenApiDocumentAssertions.assertBareArrayOf(document, "get", "/banners", "200", "ManagementBannerDto");
        OpenApiDocumentAssertions.assertRequestSchema(document, "put", "/banners/order", "ReorderBannersRequest");
        OpenApiDocumentAssertions.assertBareArrayOf(document, "put", "/banners/order", "200", "ManagementBannerDto");
        OpenApiDocumentAssertions.assertRequestSchema(document, "post", "/banners", "PublishBannerRequest");
        OpenApiDocumentAssertions.assertResponseSchema(document, "post", "/banners", "201", "ManagementBannerDto");
        OpenApiDocumentAssertions.assertRequestSchema(document, "post", "/banners/saved", "PublishBannerRequest");
        OpenApiDocumentAssertions.assertResponseSchema(document, "post", "/banners/saved", "201", "ManagementBannerDto");
        OpenApiDocumentAssertions.assertRequestSchema(document, "put", "/banners/{uuid}", "PublishBannerRequest");
        OpenApiDocumentAssertions.assertResponseSchema(document, "put", "/banners/{uuid}", "200", "ManagementBannerDto");
        OpenApiDocumentAssertions.assertRequestSchema(document, "post", "/banners/{uuid}/publish", "PublishBannerRequest");
        OpenApiDocumentAssertions.assertResponseSchema(document, "post", "/banners/{uuid}/publish", "200", "ManagementBannerDto");
        OpenApiDocumentAssertions.assertResponseSchema(document, "post", "/banners/{uuid}/disable", "200", "ManagementBannerDto");
        OpenApiDocumentAssertions.assertResponseSchema(document, "post", "/banners/{uuid}/archive", "200", "ArchivedBannerDto");
        OpenApiDocumentAssertions.assertRequestSchema(document, "post", "/banners/{uuid}/restore", "PublishBannerRequest");
        OpenApiDocumentAssertions.assertResponseSchema(document, "post", "/banners/{uuid}/restore", "201", "ManagementBannerDto");
        OpenApiDocumentAssertions.assertSchemaHasFields(
            document, "ActiveBannerDto", "uuid", "htmlContent", "title", "appearance", "icon", "dismissible", "audience", "placement",
            "pageTargets", "priority", "presentationHash"
        );
        OpenApiDocumentAssertions.assertSchemaHasFields(
            document, "ManagementBannerDto", "uuid", "status", "lifecycle", "htmlContent", "title", "appearance", "icon", "dismissible",
            "audience", "placement", "pageTargets", "startAt", "endAt", "priority", "presentationHash", "createdAt", "createdBy",
            "updatedAt", "updatedBy", "publishedAt", "publishedBy", "disabledAt", "disabledBy", "restoredFromUuid"
        );
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "ArchivedBannerDto", "uuid", "status", "archivedAt", "archivedBy");
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "BannerPageTarget", "kind", "path");
        OpenApiDocumentAssertions.assertSchemaHasFields(
            document, "PublishBannerRequest", "htmlContent", "title", "appearance", "icon", "dismissible", "audience", "placement",
            "pageTargets", "startAt", "endAt"
        );
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "ReorderBannersRequest", "bannerUuids");
    }

    @Test
    void bannerModelsAreDocumented() throws Exception {
        OpenApiDocumentAssertions.assertSchemaDocumented(
            document(), "ActiveBannerDto", "ManagementBannerDto", "ArchivedBannerDto", "BannerPageTarget", "PublishBannerRequest",
            "ReorderBannersRequest"
        );
    }

    private JsonNode document() throws Exception {
        return objectMapper
            .readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}

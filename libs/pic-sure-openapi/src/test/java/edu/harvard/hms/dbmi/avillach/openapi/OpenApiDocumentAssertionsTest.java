package edu.harvard.hms.dbmi.avillach.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.annotations.Hidden;

/** The coverage check against a hand-built document and handler map. */
class OpenApiDocumentAssertionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static class ThingsController {
        public String list() {
            return "";
        }

        public String create() {
            return "";
        }

        public String any() {
            return "";
        }
    }

    @Hidden
    static class InternalController {
        public String secret() {
            return "";
        }
    }

    private static Map<RequestMappingInfo, HandlerMethod> handlers() throws NoSuchMethodException {
        ThingsController things = new ThingsController();
        Map<RequestMappingInfo, HandlerMethod> map = new LinkedHashMap<>();
        map.put(
            RequestMappingInfo.paths("/things").methods(RequestMethod.GET).build(),
            new HandlerMethod(things, ThingsController.class.getMethod("list"))
        );
        map.put(
            RequestMappingInfo.paths("/things").methods(RequestMethod.POST).build(),
            new HandlerMethod(things, ThingsController.class.getMethod("create"))
        );
        map.put(RequestMappingInfo.paths("/anything").build(), new HandlerMethod(things, ThingsController.class.getMethod("any")));
        map.put(
            RequestMappingInfo.paths("/internal/secret").methods(RequestMethod.GET).build(),
            new HandlerMethod(new InternalController(), InternalController.class.getMethod("secret"))
        );
        return map;
    }

    private static JsonNode document(String paths) throws Exception {
        return JSON.readTree("{\"openapi\":\"3.0.1\",\"paths\":" + paths + "}");
    }

    @Test
    void passesWhenEveryVisibleHandlerHasASummarisedOperation() throws Exception {
        JsonNode complete = document("""
            {"/things": {"get": {"summary": "List things"}, "post": {"summary": "Create a thing"}},
             "/anything": {"get": {"summary": "Anything"}, "post": {"summary": "Anything"}}}
            """);
        OpenApiDocumentAssertions.assertCovers(complete, handlers());
    }

    @Test
    void failsNamingTheMissingPath() throws Exception {
        JsonNode missing = document("""
            {"/anything": {"get": {"summary": "Anything"}}}
            """);
        assertThatThrownBy(() -> OpenApiDocumentAssertions.assertCovers(missing, handlers())).isInstanceOf(AssertionError.class)
            .hasMessageContaining("missing path /things");
    }

    @Test
    void failsNamingTheMissingMethod() throws Exception {
        JsonNode getOnly = document("""
            {"/things": {"get": {"summary": "List things"}}, "/anything": {"get": {"summary": "Anything"}}}
            """);
        assertThatThrownBy(() -> OpenApiDocumentAssertions.assertCovers(getOnly, handlers())).isInstanceOf(AssertionError.class)
            .hasMessageContaining("missing operation POST /things");
    }

    @Test
    void failsNamingTheOperationWithABlankSummary() throws Exception {
        JsonNode blank = document("""
            {"/things": {"get": {"summary": "List things"}, "post": {"summary": " "}}, "/anything": {"get": {"summary": "Anything"}}}
            """);
        assertThatThrownBy(() -> OpenApiDocumentAssertions.assertCovers(blank, handlers())).isInstanceOf(AssertionError.class)
            .hasMessageContaining("blank summary on POST /things");
    }

    @Test
    void hiddenHandlersAreNotRequired() throws Exception {
        JsonNode withoutSecret = document(
            """
                {"/things": {"get": {"summary": "List things"}, "post": {"summary": "Create a thing"}}, "/anything": {"get": {"summary": "Anything"}}}
                """
        );
        OpenApiDocumentAssertions.assertCovers(withoutSecret, handlers());
        assertThat(withoutSecret.path("paths").has("/internal/secret")).isFalse();
    }
}

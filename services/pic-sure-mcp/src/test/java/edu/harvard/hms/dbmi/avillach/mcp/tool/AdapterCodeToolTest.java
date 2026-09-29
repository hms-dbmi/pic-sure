package edu.harvard.hms.dbmi.avillach.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.codegen.AdapterCodeGenerator;
import edu.harvard.hms.dbmi.avillach.mcp.codegen.AdapterSetup;
import edu.harvard.hms.dbmi.avillach.mcp.codegen.Language;
import edu.harvard.hms.dbmi.avillach.mcp.codegen.ResultKind;
import edu.harvard.hms.dbmi.avillach.mcp.config.GatewayRequestInterceptor;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryClient;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Covers {@code get_adapter_code} as a tool: binding its three arguments and the optional concept check, the result fields, that no call
 * leaves the service unless the check is asked for, that neither the caller's token nor its {@code Authorization} value reaches the result,
 * and that a failed check still returns the code.
 */
class AdapterCodeToolTest {

    private static final String GATEWAY = "http://gateway.test:8080";
    private static final String CALLER_TOKEN = "secret-caller-token-9d2";
    private static final String AUTHORIZATION = "Bearer " + CALLER_TOKEN;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String QUERY = """
        {"select":["\\\\phs1\\\\bmi\\\\"],"phenotypicClause":{"operator":"AND","phenotypicClauses":[
          {"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs1\\\\sex\\\\","values":["Female"]},
          {"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs1\\\\age\\\\","min":40}]}}""";

    private MockRestServiceServer server;
    private AdapterCodeTool tool;
    private McpTransportContext context;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder =
            RestClient.builder().baseUrl(GATEWAY).requestInterceptor(new GatewayRequestInterceptor(GATEWAY, "mcp-token"));
        server = MockRestServiceServer.bindTo(builder).build();
        AdapterSetup setup = new AdapterSetup("https://picsure.example.org", true, false, "3.0.0", "");
        tool = new AdapterCodeTool(new AdapterCodeGenerator(setup), new DictionaryClient(builder.build()));
        context = McpTransportContext.create(Map.of(CallerHeaders.KEY, new CallerHeaders(AUTHORIZATION, null, "req-1", null)));
    }

    private static Map<String, Object> arguments(String resultType, String language, Boolean checkConcepts) throws IOException {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("query", MAPPER.readValue(QUERY, new TypeReference<Map<String, Object>>() {}));
        arguments.put("resultType", resultType);
        arguments.put("language", language);
        if (checkConcepts != null) {
            arguments.put("checkConcepts", checkConcepts);
        }
        return arguments;
    }

    @Test
    void returnsTheSetupAndCodeAsSeparateFieldsWithoutCallingAnything() throws Exception {
        AdapterCodeResult result = tool.handle(context, arguments("participant", "python", null));

        server.verify();
        assertThat(result.language()).isEqualTo("python");
        assertThat(result.requires()).isEqualTo(new AdapterCodeResult.Requires("picsure", "3.0.0", "Python >= 3.10"));
        assertThat(result.check()).isEqualTo("python -c \"import importlib.metadata as m; print(m.version('picsure'))\"");
        assertThat(result.install()).isEqualTo("pip install 'picsure>=3.0.0'");
        assertThat(result.code()).startsWith("import os\nimport picsure\n\nsession = picsure.connect(\"https://picsure.example.org\"")
            .contains("includeConcepts=[").contains("output_path = \"picsure_results/participant.csv\"");
        assertThat(result.warnings()).isNull();
    }

    @ParameterizedTest
    @EnumSource(ResultKind.class)
    void everyResultTypeBindsAndWritesCodeThatHoldsNoCallerCredential(ResultKind kind) throws Exception {
        AdapterCodeResult result = tool.handle(context, arguments(kind.name(), "python", false));

        String json = MAPPER.writeValueAsString(result) + result.setupText();
        assertThat(json).doesNotContain(CALLER_TOKEN, AUTHORIZATION, "Bearer", "mcp-token");
        assertThat(result.code()).doesNotContain("print(df", ".head(").contains("type=\"" + kind.name() + "\"");
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    void everyLanguageBinds(Language language) throws Exception {
        if (language == Language.python) {
            assertThat(tool.handle(context, arguments("count", language.name(), null)).language()).isEqualTo("python");
        } else {
            assertThatThrownBy(() -> tool.handle(context, arguments("count", language.name(), null))).isInstanceOf(ToolFailure.class)
                .hasMessage("Code in " + language.name() + " is not available yet. Use python.");
        }
    }

    @Test
    void anUnknownResultTypeNamesTheFourValues() throws Exception {
        assertThatThrownBy(() -> tool.handle(context, arguments("COUNT", "python", null))).isInstanceOf(ToolFailure.class)
            .hasMessage("Field 'resultType' must be one of count, cross_count, participant, timestamp.");
    }

    @Test
    void anUnknownLanguageNamesTheThreeValues() throws Exception {
        assertThatThrownBy(() -> tool.handle(context, arguments("count", "julia", null))).isInstanceOf(ToolFailure.class)
            .hasMessage("Field 'language' must be one of python, r, bash.");
    }

    @Test
    void anUnknownFieldIsRejected() throws Exception {
        Map<String, Object> arguments = arguments("count", "python", null);
        arguments.put("platform", "BDC_AUTHORIZED");

        assertThatThrownBy(() -> tool.handle(context, arguments)).isInstanceOf(ToolFailure.class)
            .hasMessage("Field 'platform' is not part of this tool's input.");
    }

    @Test
    void aNotInTheQueryIsRejected() {
        Map<String, Object> arguments = Map.of(
            "resultType", "count", "language", "python", "query",
            Map.of("phenotypicClause", Map.of("phenotypicFilterType", "REQUIRED", "conceptPath", "\\p\\", "not", true))
        );

        assertThatThrownBy(() -> tool.handle(context, arguments)).isInstanceOf(ToolFailure.class)
            .hasMessage("Field 'not' is not part of this tool's input.");
    }

    @Test
    void missingArgumentsAreNamed() throws Exception {
        Map<String, Object> noType = arguments("count", "python", null);
        noType.remove("resultType");
        Map<String, Object> noLanguage = arguments("count", "python", null);
        noLanguage.remove("language");

        assertThatThrownBy(() -> tool.handle(context, Map.of())).hasMessage("Argument 'query' is required.");
        assertThatThrownBy(() -> tool.handle(context, noType)).hasMessage("Argument 'resultType' is required.");
        assertThatThrownBy(() -> tool.handle(context, noLanguage)).hasMessage("Argument 'language' is required.");
    }

    @Test
    void theConceptCheckReportsUnknownPathsAndStillReturnsTheCode() throws Exception {
        server.expect(requestTo(GATEWAY + "/dictionary/concepts/detail")).andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", AUTHORIZATION)).andExpect(header("X-PIC-SURE-MCP-TOKEN", "mcp-token"))
            .andExpect(content().json("[\"\\\\phs1\\\\sex\\\\\",\"\\\\phs1\\\\age\\\\\",\"\\\\phs1\\\\bmi\\\\\"]", true)).andRespond(
                withSuccess(
                    "[{\"conceptPath\":\"\\\\phs1\\\\sex\\\\\",\"dataset\":\"phs1\"},{\"conceptPath\":\"\\\\phs1\\\\bmi\\\\\"}]",
                    MediaType.APPLICATION_JSON
                )
            );

        AdapterCodeResult result = tool.handle(context, arguments("participant", "python", true));

        server.verify();
        assertThat(result.warnings())
            .containsExactly("Concept path not found in the dictionary, check it with search_concepts: \\phs1\\age\\");
        assertThat(result.code()).contains("picsure.buildQuery(");
        assertThat(result.setupText())
            .endsWith("Warning: Concept path not found in the dictionary, check it with search_concepts: \\phs1\\age\\");
    }

    @Test
    void aFailedConceptCheckIsAWarningAndNeverBlocksTheCode() throws Exception {
        server.expect(requestTo(GATEWAY + "/dictionary/concepts/detail"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("SECRET-DOWNSTREAM-BODY"));

        AdapterCodeResult result = tool.handle(context, arguments("count", "python", true));

        server.verify();
        assertThat(result.warnings()).containsExactly(
            "The concept paths could not be checked against the dictionary: The dictionary is unavailable. Try again shortly."
        );
        assertThat(result.code()).contains("print(count.raw)");
        assertThat(MAPPER.writeValueAsString(result)).doesNotContain("SECRET-DOWNSTREAM-BODY");
    }

    @Test
    void theDescriptionCarriesTheHandoffRulesAndABindableExample() throws Exception {
        String description = AdapterCodeTool.DESCRIPTION;

        assertThat(description).contains("PICSURE_TOKEN", "do not run it unless the user asks", "run check", "only after the user confirms")
            .contains("exact and filtered by the user's consents", "python, r, or bash")
            .doesNotContain(String.valueOf((char) 0x2014), "--");
        String example = description.substring(description.indexOf("Example arguments: ") + "Example arguments: ".length());
        Map<String, Object> arguments = MAPPER.readValue(example, new TypeReference<>() {});
        assertThat(tool.handle(context, arguments).code()).contains("categories=[\"Female\"]", "min=40, max=65");
    }
}

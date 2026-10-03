package edu.harvard.hms.dbmi.avillach.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Pins the text the model reads. It reads the server {@code instructions} from a real {@code initialize} response and the tool descriptions
 * from a real {@code tools/list} response over {@code /mcp}, and asserts that every sentence carrying a rule is present verbatim. Rewording
 * a rule then fails here, so the change shows up in review as an edit to this test.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"PICSURE_GATEWAY_URL=http://gateway.test:8080", "MCP_SERVICE_TOKEN=test-mcp-token",
        "MCP_ADAPTER_BASE_URL=https://picsure.test"}
)
class InstructionsTest {

    private static final int MAX_INSTRUCTION_WORDS = 450;

    private static final List<String> RULE_SENTENCES = List.of(
        "The server itself never reaches participant-level or authorized data.",
        "To find variables or browse studies and facets, use search_concepts, list_facets, and get_concept.",
        "The dictionary ANDs every word of a search with prefix matching, so a multi-word search must describe one concept.",
        "Search synonyms and abbreviations as separate terms, several at once with the terms argument of search_concepts.",
        "For a rough cohort size or a feasibility check, use count_participants or cross_count.",
        "For exact counts under the user's own consents, use get_adapter_code with resultType count.",
        "For participant rows, timestamps, or an export, use get_adapter_code with resultType participant or timestamp.",
        "To change adapter code the user already has, edit that code directly and use this server only to look up concept paths.",
        "An open count from count_participants or cross_count is obfuscated and ignores the user's consents.",
        "The same query run through the adapter returns an exact count filtered by the user's consents.",
        "Always say which kind of number you are reporting.", "Never present an open count as the authorized answer.",
        "Show the generated code to the user. Do not run it unless the user asks.", "Before you run the code, run its check command.",
        "If the connector is missing or older than requires.minVersion, show the install command to the user and ask before running it.",
        "Never install anything without asking.", "If you cannot run commands, show check, install, and the code to the user together.",
        "Count and cross_count code prints only the counts.",
        "Participant and timestamp code writes a CSV to picsure_results/ and prints one line with its size and path.",
        "Report only what the code prints. Do not open the result files unless the user asks you to.",
        "Open counts and authorized counts are different numbers.", "Do not route authorized work around this server.",
        "If you have a shell and the picsure adapter is installed, still generate the code with get_adapter_code and hand it to the user "
            + "instead of calling the adapter yourself.",
        "get_adapter_code takes language python, r, or bash. Pick it from what you can see: the user's request, project files such as .R, "
            + ".ipynb, or shell scripts, the notebook kernel, and code the user already has. Ask the user when that does not settle it.",
        "Take concept paths from search_concepts. Build one query, count it with count_participants, then pass the same query to "
            + "get_adapter_code.",
        "The query has no not field and no authorization fields, and the tools reject both."
    );

    private static final List<String> SELECT_SENTENCES = List.of(
        "select has no effect on the open count tools, count_participants and cross_count.",
        "For get_adapter_code, select adds output columns for participant and timestamp results, and names the concept paths cross_count "
            + "counts on the authorized channel."
    );

    private static final String WITHHELD_SENTENCE = "no result was returned (the open channel withholds small results)";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void theInitializeResponseCarriesEveryRuleSentenceVerbatim() throws Exception {
        String instructions = instructions();

        assertThat(instructions).contains(RULE_SENTENCES);
    }

    @Test
    void theInstructionsArePlainTextUnderTheWordBudget() throws Exception {
        String instructions = instructions();

        assertThat(instructions.split("\\s+")).hasSizeLessThanOrEqualTo(MAX_INSTRUCTION_WORDS);
        assertThat(instructions).doesNotContain(String.valueOf((char) 0x2014), "-".repeat(2), "#", "|", "**", "`");
    }

    @Test
    void theDictionaryToolDescriptionsStateTheirCapsAndThatTheDataIsOpen() throws Exception {
        Map<String, String> descriptions = descriptions();

        assertThat(descriptions.get("search_concepts")).contains("open-access dictionary metadata only, never participant data")
            .contains("up to 20 values", "valuesOmitted", "at most 25", "get_adapter_code")
            .contains("The dictionary ANDs every word of a search with prefix matching, so a multi-word search must describe one concept")
            .contains("Search synonyms and abbreviations as separate terms, which the terms argument does in one call.")
            .contains("Give either query or terms (up to 5), not both.", "matchedTerms", "truncated", "warnings");
        assertThat(descriptions.get("list_facets")).contains("open-access dictionary metadata only, never participant data")
            .contains("At most 25 categories and 25 facets per category", "categoriesOmitted", "facetsOmitted")
            .contains(
                "The dictionary ANDs every word of the search with prefix matching, so a multi-word search must describe one concept."
            ).contains(
                "Search synonyms and abbreviations as separate terms; search_concepts takes several in one call with its terms argument."
            );
        assertThat(descriptions.get("get_concept")).contains("open-access dictionary metadata only, never participant data")
            .contains("up to 20 categorical values", "up to 10 metadata entries", "300 characters");
    }

    @Test
    void theCountToolDescriptionsSayTheNumbersAreObfuscatedAndNeverTheAuthorizedAnswer() throws Exception {
        Map<String, String> descriptions = descriptions();

        assertThat(descriptions.get("count_participants"))
            .contains("obfuscated open-access count that ignores the caller's consents", "suppressed true, with threshold")
            .contains("It is never the authorized answer", "get_adapter_code with resultType count", "phs999999")
            .contains("give genomic filters values only, with no min or max");
        assertThat(descriptions.get("cross_count"))
            .contains("obfuscated open-access count that ignores the caller's consents", "At most 100 cells", "cellsOmitted")
            .contains("the open channel uses it for none of the three", "These cells are never the authorized answer")
            .contains("get_adapter_code with resultType cross_count", "phs999999");
    }

    @Test
    void theAdapterCodeDescriptionCarriesTheHandoffRulesAndTheRefusals() throws Exception {
        String description = descriptions().get("get_adapter_code");

        assertThat(description).contains("read from the PICSURE_TOKEN environment variable; it never contains a token")
            .contains("Show the code to the user and do not run it unless the user asks.", "Before the code runs, run check.")
            .contains("show install to the user and run it only after the user confirms; never install silently")
            .contains(
                "the script needs curl 7.55 or later and jq",
                "Report only what the code prints. Do not open the result files unless the user asks you to."
            ).contains("language: python, r, or bash", "exact and filtered by the user's consents", "so always say which kind of number")
            .contains("a genomic filter with min or max", "a REQUIRED or ANY_RECORD_OF filter with values, min, or max")
            .contains("a FILTER with both values and min or max or with neither", "a blank entry in values", "phs999999");
    }

    @Test
    void theSelectFieldSaysItDoesNothingForOpenCountsAndWhatItDoesInAdapterCode() throws Exception {
        for (String name : List.of("count_participants", "cross_count", "get_adapter_code")) {
            String inputSchema = objectMapper.writeValueAsString(tools().get(name).path("inputSchema"));

            assertThat(inputSchema).as(name).contains(SELECT_SENTENCES).doesNotContain("Passed on for cross counts");
        }
    }

    @Test
    void withheldSaysNoResultWasReturnedAndNamesNoParticipantThreshold() throws Exception {
        JsonNode crossCount = tools().get("cross_count");
        String outputSchema = objectMapper.writeValueAsString(crossCount.path("outputSchema"));

        assertThat(crossCount.path("description").asText()).contains(WITHHELD_SENTENCE).doesNotContain("too few participants");
        assertThat(outputSchema).contains(WITHHELD_SENTENCE).doesNotContain("too few participants");
    }

    @Test
    void noDescriptionUsesARealAccessionOrADash() throws Exception {
        for (Map.Entry<String, String> entry : descriptions().entrySet()) {
            assertThat(entry.getValue()).as(entry.getKey()).doesNotContain("phs000001", String.valueOf((char) 0x2014), "-".repeat(2));
        }
    }

    private String instructions() throws Exception {
        JsonNode result = call("""
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
            "capabilities":{},"clientInfo":{"name":"test-client","version":"1.0.0"}}}""");
        JsonNode instructions = result.path("instructions");
        assertThat(instructions.isTextual()).isTrue();
        return instructions.asText();
    }

    private Map<String, String> descriptions() throws Exception {
        Map<String, String> descriptions = new HashMap<>();
        tools().forEach((name, tool) -> descriptions.put(name, tool.path("description").asText()));
        return descriptions;
    }

    private Map<String, JsonNode> tools() throws Exception {
        JsonNode result = call("""
            {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""");
        Map<String, JsonNode> tools = new HashMap<>();
        for (JsonNode tool : result.path("tools")) {
            tools.put(tool.path("name").asText(), tool);
        }
        assertThat(tools).hasSize(6);
        return tools;
    }

    private JsonNode call(String body) throws Exception {
        String response = RestClient.create("http://localhost:" + port).post().uri("/mcp").contentType(MediaType.APPLICATION_JSON)
            .header("Accept", "application/json, text/event-stream").body(body).retrieve().body(String.class);
        JsonNode json = objectMapper.readTree(response);
        assertThat(json.has("error")).isFalse();
        return json.path("result");
    }
}

package edu.harvard.hms.dbmi.avillach.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.mcp.tool.AdapterCodeTool;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Pins the text the model reads. It reads the server {@code instructions} from a real {@code initialize} response and the tool descriptions
 * from a real {@code tools/list} response over {@code /mcp}, and asserts that every sentence carrying a rule is present verbatim. Rewording
 * a rule then fails here, so the change shows up in review as an edit to this test. It also holds the instructions, every tool description,
 * and every schema description to the length Claude Code shows before cutting the text off.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"PICSURE_GATEWAY_URL=http://gateway.test:8080", "MCP_SERVICE_TOKEN=test-mcp-token",
        "MCP_ADAPTER_BASE_URL=https://picsure.test"}
)
class InstructionsTest {

    private static final int MAX_INSTRUCTION_WORDS = 450;

    /** Claude Code cuts the server instructions and each tool description at this many characters. */
    private static final int CLIENT_TEXT_LIMIT = 2048;

    private static final String ROUTING_RULE = "Do not route authorized work around this server.";

    private static final List<String> RULE_SENTENCES = List.of(
        "It never reaches participant-level or authorized data.", ROUTING_RULE,
        "Even with a shell and the picsure adapter installed, generate the code with get_adapter_code and hand it to the user instead of "
            + "calling the adapter yourself.",
        "To find variables or browse studies and facets, use search_concepts, list_facets, get_concept, and get_concepts.",
        "For a rough cohort size or a feasibility check, use count_participants or cross_count.",
        "For exact counts under the user's own consents, use get_adapter_code with resultType count.",
        "For participant rows, timestamps, or an export, use get_adapter_code with resultType participant or timestamp.",
        "To change adapter code the user already has, edit that code directly and use this server only to look up concept paths and "
            + "check open counts.",
        "The dictionary ANDs every word of a search with prefix matching, so a multi-word search must describe one concept.",
        "Search synonyms and abbreviations as separate terms, several at once with the terms argument of search_concepts.",
        "An open count from count_participants or cross_count is obfuscated and ignores the user's consents.",
        "The adapter returns an exact count filtered by the user's consents.", "Always say which kind of number you are reporting.",
        "Never present an open count as the authorized answer.",
        "Show the code from get_adapter_code to the user. Do not run it unless the user asks.", "Before you run it, run its check command.",
        "If the connector is missing or older than requires.minVersion, show the install command and ask before running it.",
        "Never install anything without asking.", "If you cannot run commands, show check, install, and the code together.",
        "Report only what the code prints. Do not open the result files unless the user asks you to."
    );

    private static final List<String> SELECT_SENTENCES = List.of(
        "select has no effect on the open count tools, count_participants and cross_count.",
        "For get_adapter_code, select adds output columns for participant and timestamp results, and names the concept paths cross_count "
            + "counts on the authorized channel."
    );

    /** A colon right after a word and followed by whitespace or the end of a line, which reads as a clause connector. */
    private static final Pattern COLON_CONNECTOR = Pattern.compile("\\S:(\\s|$)", Pattern.MULTILINE);

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
    void theInstructionsFitTheClientLimitWithTheRoutingRuleInTheFirstHalf() throws Exception {
        String instructions = instructions();

        assertThat(instructions).hasSizeLessThanOrEqualTo(CLIENT_TEXT_LIMIT);
        assertThat(instructions.indexOf(ROUTING_RULE)).isBetween(0, instructions.length() / 2);
    }

    @Test
    void everyToolDescriptionAndSchemaDescriptionFitsTheClientLimit() throws Exception {
        for (Map.Entry<String, JsonNode> tool : tools().entrySet()) {
            assertThat(tool.getValue().path("description").asText()).as(tool.getKey()).hasSizeLessThanOrEqualTo(CLIENT_TEXT_LIMIT);
            List<String> schemaDescriptions = new ArrayList<>();
            collectDescriptions(tool.getValue().path("inputSchema"), schemaDescriptions);
            collectDescriptions(tool.getValue().path("outputSchema"), schemaDescriptions);
            assertThat(schemaDescriptions).as(tool.getKey()).isNotEmpty()
                .allSatisfy(description -> assertThat(description).hasSizeLessThanOrEqualTo(CLIENT_TEXT_LIMIT));
        }
    }

    @Test
    void theDictionaryToolDescriptionsStateTheirCapsAndThatTheDataIsOpen() throws Exception {
        Map<String, String> descriptions = descriptions();

        assertThat(descriptions.get("search_concepts")).contains("open-access dictionary metadata only, never participant data")
            .contains("up to 20 values", "valuesOmitted", "at most 25", "get_adapter_code")
            .contains("The dictionary ANDs every word of a search with prefix matching, so a multi-word search must describe one concept")
            .contains("Search synonyms and abbreviations as separate terms, which the terms argument does in one call.")
            .contains("must describe one concept. For example, \"blood pressure hypertension\" finds nothing")
            .contains("Give either search or terms (up to 5), not both.", "matchedTerms", "truncated", "warnings");
        assertThat(descriptions.get("list_facets")).contains("open-access dictionary metadata only, never participant data")
            .contains("At most 25 categories and 25 facets per category", "categoriesOmitted", "facetsOmitted")
            .contains(
                "The dictionary ANDs every word of the search with prefix matching, so a multi-word search must describe one concept."
            ).contains(
                "Search synonyms and abbreviations as separate terms; search_concepts takes several in one call with its terms argument."
            );
        assertThat(descriptions.get("get_concept")).contains("open-access dictionary metadata only, never participant data")
            .contains("never participant data. The metadata is the display name, description, type,")
            .contains("up to 20 categorical values", "up to 10 metadata entries", "300 characters");
        assertThat(descriptions.get("get_concepts")).contains("open-access dictionary metadata only, never participant data")
            .contains("Takes 1 to 25 concept paths.")
            .contains("never participant data. For each concept found, it returns the same fields get_concept returns")
            .contains("Paths the dictionary does not know come back in notFound rather than as an error.");
    }

    @Test
    void theCountToolDescriptionsSayTheNumbersAreObfuscatedAndNeverTheAuthorizedAnswer() throws Exception {
        Map<String, String> descriptions = descriptions();

        assertThat(descriptions.get("count_participants"))
            .contains("obfuscated open-access count that ignores the caller's consents", "suppressed true, with threshold")
            .contains("ignores the caller's consents and study access. It carries a small random offset")
            .contains(
                "It is never the authorized answer. For an exact count filtered by the user's consents, pass the same query to "
                    + "get_adapter_code with resultType count."
            ).contains("get_adapter_code with resultType count", "phs999999")
            .contains("give genomic filters values only, with no min or max")
            .contains("Build the query from conceptPath values that search_concepts returns.")
            .contains("pass the same query to get_adapter_code with resultType count")
            .contains("The query has no result type, no not field, and no authorization fields, and the tools reject all three.");
        assertThat(descriptions.get("cross_count"))
            .contains("obfuscated open-access count that ignores the caller's consents", "At most 100 cells", "cellsOmitted")
            .contains("the open channel uses it for none of the three")
            .contains("CROSS_COUNT gives one cell per study consent concept. The open channel replaces select with every concept path")
            .contains(
                "These cells are never the authorized answer. For exact counts filtered by the user's consents, use get_adapter_code "
                    + "with resultType cross_count"
            ).contains("get_adapter_code with resultType cross_count", "phs999999")
            .contains("CONTINUOUS_CROSS_COUNT gives one cell per bin", "or one cell per distinct value where the site does not bin");
    }

    @Test
    void theAdapterCodeDescriptionCarriesTheHandoffRulesAndTheRefusals() throws Exception {
        String description = descriptions().get("get_adapter_code");

        assertThat(description)
            .contains("The code reads the user's own token from the PICSURE_TOKEN environment variable and never contains a token.")
            .contains("Show the code to the user and do not run it unless the user asks.", "This tool runs no query and returns no data.")
            .contains(
                "resultType count prints one number, cross_count prints one number per concept path, and participant and timestamp "
                    + "write a CSV under picsure_results/ and print only its size and path."
            ).contains("language is python, r, or bash.", "bash needs curl 7.55 or later and jq")
            .contains(
                "Pick the language the user works in, from their request, project files such as .R, .ipynb, or shell scripts, the "
                    + "notebook kernel, or their existing code, and ask when that does not settle it."
            ).contains("for exact counts filtered by the user's consents")
            .contains(
                "select chooses the concept paths cross_count counts and adds output columns for participant and timestamp, and count "
                    + "ignores it."
            ).contains("A genomic filter with min or max, since genomic filters take values only.")
            .contains("A REQUIRED or ANY_RECORD_OF filter with values, min, or max.")
            .contains("A FILTER with both values and min or max, or with neither.", "A blank entry in values.")
            .contains("A query with no filter, genomic filter, or select path. For count, select alone is not enough.", "phs999999")
            .contains("The tool refuses the following, with a message naming the field. A genomic filter with min or max")
            .contains("A genomic filter with no values.").doesNotContain("Each of these is refused").contains(
                "With checkConcepts true, the first " + AdapterCodeTool.MAX_CHECKED_PATHS + " distinct concept paths are checked against "
                    + "the dictionary, and warnings report unknown paths and any cutoff without blocking the code."
            );
    }

    @Test
    void theCheckConceptsFieldStatesHowManyPathsTheCheckCovers() throws Exception {
        JsonNode checkConcepts = tools().get("get_adapter_code").path("inputSchema").path("properties").path("checkConcepts");

        assertThat(checkConcepts.path("description").asText()).isEqualTo(
            "Check the first " + AdapterCodeTool.MAX_CHECKED_PATHS + " distinct concept paths against the dictionary. Defaults to false"
        );
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
    void adapterCodeWarningsTellTheModelToPassThemOn() throws Exception {
        String outputSchema = objectMapper.writeValueAsString(tools().get("get_adapter_code").path("outputSchema"));

        assertThat(outputSchema).contains("Notes to pass on to the user")
            .contains("a concept path check that stopped at the first 100 paths", "why the paths could not be checked");
    }

    @Test
    void searchResultFieldsSayWhatTruncatedAndWarningsMean() throws Exception {
        JsonNode searchConcepts = tools().get("search_concepts");
        String outputSchema = objectMapper.writeValueAsString(searchConcepts.path("outputSchema"));
        String inputSchema = objectMapper.writeValueAsString(searchConcepts.path("inputSchema"));

        assertThat(outputSchema).contains("matched more than 25 distinct concepts on the pages asked for and only the first 25 were kept")
            .contains("One entry per term whose search failed, naming the term and the failure; those terms returned no concepts.")
            .contains("The search text of a single search", "The terms of a search by terms, in the order given");
        assertThat(inputSchema).contains("at most 200 characters", "punctuation is ignored and splits words")
            .contains("punctuation in it is ignored and splits words");
    }

    @Test
    void noToolDescriptionOrInstructionJoinsClausesWithAColon() throws Exception {
        List<String> texts = new ArrayList<>(descriptions().values());
        texts.add(instructions());

        for (String text : texts) {
            assertThat(text.replace("Example arguments: ", "")).doesNotContainPattern(COLON_CONNECTOR);
        }
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

    private static void collectDescriptions(JsonNode node, List<String> descriptions) {
        if (node.isObject()) {
            node.properties().forEach(field -> {
                if (field.getKey().equals("description") && field.getValue().isTextual()) {
                    descriptions.add(field.getValue().asText());
                } else {
                    collectDescriptions(field.getValue(), descriptions);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectDescriptions(child, descriptions));
        }
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
        assertThat(tools).hasSize(7);
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

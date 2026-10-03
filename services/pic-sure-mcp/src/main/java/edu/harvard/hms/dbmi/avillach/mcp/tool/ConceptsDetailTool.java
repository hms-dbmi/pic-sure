package edu.harvard.hms.dbmi.avillach.mcp.tool;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryClient;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryConcept;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** MCP tool that fetches several concepts' metadata from the PIC-SURE data dictionary in one call. */
@Component
public class ConceptsDetailTool {

    /** Most concept paths one call may look up. */
    public static final int MAX_CONCEPT_PATHS = 25;


    private final DictionaryClient dictionary;

    /**
     * Creates the tool.
     *
     * @param dictionary the dictionary client
     */
    public ConceptsDetailTool(DictionaryClient dictionary) {
        this.dictionary = dictionary;
    }

    /**
     * Looks up several concepts by path and returns the ones found, trimmed as {@code get_concept} trims one, with the paths the dictionary
     * did not return listed in {@code notFound}.
     *
     * @param context the MCP transport context carrying the caller's headers
     * @param conceptPaths 1 to {@value #MAX_CONCEPT_PATHS} concept paths, as search_concepts reports them
     * @return the concepts found and the paths not found
     * @throws ToolFailure with a model-facing message for a bad argument or a failed dictionary call
     */
    @AuditEvent(type = "SEARCH", action = "concept.detail")
    @McpTool(
        name = "get_concepts", description = """
            Get several variables (concepts) from the PIC-SURE data dictionary in one call, by conceptPath exactly as \
            search_concepts returned them. Takes 1 to 25 concept paths. Returns open-access dictionary metadata only, never \
            participant data: for each concept found, the same fields get_concept returns (display name, description, type, up \
            to 20 categorical values with valuesOmitted for the rest, min and max for continuous concepts, and up to 10 metadata \
            entries with each value cut at 300 characters). Paths the dictionary does not know come back in notFound rather than \
            as an error.""", generateOutputSchema = true,
        annotations = @McpTool.McpAnnotations(
            title = "Get concepts", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        )
    )
    public ConceptsDetailResult getConcepts(
        McpTransportContext context,
        @McpToolParam(description = "1 to 25 concept paths as returned by search_concepts", required = true) List<String> conceptPaths
    ) {
        List<String> paths = validPaths(conceptPaths);
        List<DictionaryConcept> found =
            DictionaryCalls.run(DictionaryClient.DETAIL_PATH, () -> dictionary.conceptsDetail(paths, CallerHeaders.from(context)));
        Set<String> foundPaths = found.stream().map(DictionaryConcept::conceptPath).collect(Collectors.toSet());
        List<String> notFound = paths.stream().filter(path -> !foundPaths.contains(path)).toList();
        return new ConceptsDetailResult(found.stream().map(ConceptSummary::detail).toList(), notFound);
    }

    private static List<String> validPaths(List<String> conceptPaths) {
        if (conceptPaths == null || conceptPaths.isEmpty() || conceptPaths.size() > MAX_CONCEPT_PATHS) {
            throw new ToolFailure("Argument 'conceptPaths' must hold 1 to " + MAX_CONCEPT_PATHS + " concept paths.");
        }
        List<String> valid = new ArrayList<>();
        for (int i = 0; i < conceptPaths.size(); i++) {
            valid.add(ToolArguments.requireText("conceptPaths[" + i + "]", conceptPaths.get(i), ConceptDetailTool.MAX_CONCEPT_PATH_LENGTH));
        }
        return valid.stream().distinct().toList();
    }
}

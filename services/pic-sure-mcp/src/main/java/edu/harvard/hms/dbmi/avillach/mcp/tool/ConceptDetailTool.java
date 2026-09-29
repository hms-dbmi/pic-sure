package edu.harvard.hms.dbmi.avillach.mcp.tool;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryClient;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryConcept;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/** MCP tool that fetches one concept's metadata from the PIC-SURE data dictionary. */
@Component
public class ConceptDetailTool {

    /** Longest dataset name accepted, in characters. */
    public static final int MAX_DATASET_LENGTH = 200;

    /** Longest concept path accepted, in characters. */
    public static final int MAX_CONCEPT_PATH_LENGTH = 2000;


    private final DictionaryClient dictionary;

    /**
     * Creates the tool.
     *
     * @param dictionary the dictionary client
     */
    public ConceptDetailTool(DictionaryClient dictionary) {
        this.dictionary = dictionary;
    }

    /**
     * Fetches one concept and returns it trimmed the same way as a search result, plus its name and a few metadata entries.
     *
     * @param context the MCP transport context carrying the caller's headers
     * @param dataset the dataset the concept belongs to, as search_concepts reports it
     * @param conceptPath the concept path, as search_concepts reports it
     * @return the concept
     * @throws ToolFailure with a model-facing message for a bad argument, an unknown concept path, or a failed dictionary call
     */
    @AuditEvent(type = "SEARCH", action = "concept.detail")
    @McpTool(
        name = "get_concept", description = """
            Get one variable (concept) from the PIC-SURE data dictionary by dataset and conceptPath, both exactly as \
            search_concepts returned them. Returns open-access dictionary metadata only, never participant data: display name, \
            description, type, up to 20 categorical values with valuesOmitted for the rest, min and max for continuous \
            concepts, and up to 10 metadata entries with each value cut at 300 characters. Call search_concepts first to find \
            valid paths.""", generateOutputSchema = true,
        annotations = @McpTool.McpAnnotations(
            title = "Get concept", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        )
    )
    public ConceptSummary getConcept(
        McpTransportContext context, @McpToolParam(description = "Dataset the concept belongs to", required = true) String dataset,
        @McpToolParam(description = "Concept path as returned by search_concepts", required = true) String conceptPath
    ) {
        String datasetName = ToolArguments.requireText("dataset", dataset, MAX_DATASET_LENGTH);
        if (datasetName.chars().allMatch(c -> c == '.')) {
            throw new ToolFailure("Argument 'dataset' is not a valid dataset name.");
        }
        String path = ToolArguments.requireText("conceptPath", conceptPath, MAX_CONCEPT_PATH_LENGTH);
        DictionaryConcept concept =
            DictionaryCalls.run(DictionaryClient.DETAIL_PATH, () -> lookup(datasetName, path, CallerHeaders.from(context)));
        if (concept == null) {
            throw ToolFailure.conceptNotFound();
        }
        return ConceptSummary.detail(concept);
    }

    private DictionaryConcept lookup(String dataset, String path, CallerHeaders caller) {
        try {
            return dictionary.conceptDetail(dataset, path, caller);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }
}

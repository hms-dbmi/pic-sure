package edu.harvard.hms.dbmi.avillach.mcp.tool;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.codegen.AdapterCodeGenerator;
import edu.harvard.hms.dbmi.avillach.mcp.codegen.AdapterQuery;
import edu.harvard.hms.dbmi.avillach.mcp.codegen.GeneratedCode;
import edu.harvard.hms.dbmi.avillach.mcp.codegen.Language;
import edu.harvard.hms.dbmi.avillach.mcp.codegen.ResultKind;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryClient;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryConcept;
import edu.harvard.hms.dbmi.avillach.mcp.query.QueryBinder;
import edu.harvard.hms.dbmi.avillach.mcp.query.QueryInput;
import io.modelcontextprotocol.common.McpTransportContext;
import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * The {@code get_adapter_code} tool: hands authorized work back to the user as code they run with their own token. It makes no query and
 * never reads the caller's {@code Authorization} header; the only outbound call is the optional concept check against the open dictionary.
 * Registered as a tool specification bean, which calls {@link #handle} through this bean so the audit aspect sees every call.
 */
@Component
public class AdapterCodeTool {

    /** The tool name. */
    public static final String NAME = "get_adapter_code";

    /** The tool title. */
    public static final String TITLE = "Get adapter code";

    /** Most concept paths sent to the dictionary in one check. */
    public static final int MAX_CHECKED_PATHS = 100;

    /** The schema description of {@code checkConcepts}, which states how many concept paths the check covers. */
    public static final String CHECK_CONCEPTS_DESCRIPTION =
        "Check the first " + MAX_CHECKED_PATHS + " distinct concept paths against the dictionary. Defaults to false";

    /** The tool description, with one complete worked example. */
    public static final String DESCRIPTION = """
        Write code that runs a query on PIC-SURE's authorized channel in the user's own environment, for exact counts filtered by the \
        user's consents, participant rows, or timestamps. This tool runs no query and returns no data. The code reads the user's own \
        token from the PICSURE_TOKEN environment variable and never contains a token. Show the code to the user and do not run it \
        unless the user asks. resultType count prints one number, cross_count prints one number per concept path, and participant \
        and timestamp write a CSV under picsure_results/ and print only its size and path. language is python, r, or bash. python \
        and r use the picsure adapters, and bash needs curl 7.55 or later and jq. Pick the language the user works in, from their \
        request, project files such as .R, .ipynb, or shell scripts, the notebook kernel, or their existing code, and ask when that \
        does not settle it. The query has the count_participants shape, with no result type and no not field. select chooses the \
        concept paths cross_count counts and adds output columns for participant and timestamp, and count ignores it. The tool \
        refuses the following, with a message naming the field. A genomic filter with min or max, since genomic filters take values \
        only. A genomic filter with no values. A REQUIRED or ANY_RECORD_OF filter with values, min, or max. A FILTER with both values \
        and min or max, or with neither. A blank entry in values. A query with no filter, genomic filter, or select path. For count, \
        select alone is not enough. With checkConcepts true, the first 100 distinct concept paths are checked against the dictionary, \
        and warnings report unknown paths and any cutoff without blocking the code. Example arguments: \
        {"language":"python","resultType":"participant","query":{"select":["\\\\phs999999\\\\bmi\\\\"],"phenotypicClause":\
        {"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs999999\\\\age\\\\","min":40,"max":65}}}""";

    private static final Logger log = LoggerFactory.getLogger(AdapterCodeTool.class);

    private final AdapterCodeGenerator generator;

    private final DictionaryClient dictionary;

    /**
     * Creates the tool.
     *
     * @param generator writes the code
     * @param dictionary the dictionary client, used only for the optional concept check
     */
    public AdapterCodeTool(AdapterCodeGenerator generator, DictionaryClient dictionary) {
        this.generator = generator;
        this.dictionary = dictionary;
    }

    /**
     * Binds the raw arguments and writes the code. Binding happens here so a rejected argument is a failure of the audited call.
     *
     * @param context the MCP transport context, used only to replay the caller's headers on the optional concept check
     * @param arguments the raw arguments of the {@code tools/call} request, possibly null
     * @return the code and its setup
     * @throws ToolFailure with a model-facing message for an unbindable or missing argument, an incomplete query, or a filter or genomic
     *         filter the adapters cannot express
     */
    @AuditEvent(type = "OTHER", action = "adapter.code")
    public AdapterCodeResult handle(McpTransportContext context, Map<String, Object> arguments) {
        Input input = QueryBinder.bind(arguments, Input.class);
        ToolArguments.require("query", input == null ? null : input.query());
        ToolArguments.require("resultType", input.resultType());
        ToolArguments.require("language", input.language());
        AdapterQuery query = generator.walk(input.query(), input.resultType());
        GeneratedCode code = generator.generate(query, input.language());
        List<String> warnings = new ArrayList<>(generator.warnings(query));
        if (Boolean.TRUE.equals(input.checkConcepts())) {
            warnings.addAll(checkConcepts(query.conceptPaths(), context));
        }
        return new AdapterCodeResult(
            code.language().name(), new AdapterCodeResult.Requires(code.packageName(), code.minVersion(), code.runtime()), code.check(),
            code.install(), code.code(), warnings.isEmpty() ? null : warnings
        );
    }

    private List<String> checkConcepts(List<String> paths, McpTransportContext context) {
        List<String> warnings = new ArrayList<>();
        List<String> checked = paths.size() > MAX_CHECKED_PATHS ? paths.subList(0, MAX_CHECKED_PATHS) : paths;
        if (checked.size() < paths.size()) {
            warnings.add("Only the first " + MAX_CHECKED_PATHS + " concept paths were checked.");
        }
        if (checked.isEmpty()) {
            return warnings;
        }
        List<DictionaryConcept> found;
        try {
            found =
                DictionaryCalls.run(DictionaryClient.DETAIL_PATH, () -> dictionary.conceptsDetail(checked, CallerHeaders.from(context)));
        } catch (ToolFailure e) {
            warnings.add("The concept paths could not be checked against the dictionary: " + e.getMessage());
            return warnings;
        } catch (RuntimeException e) {
            log.warn("The concept check failed with {}", e.getClass().getName());
            warnings.add("The concept paths could not be checked against the dictionary.");
            return warnings;
        }
        Set<String> known = found.stream().filter(Objects::nonNull).map(DictionaryConcept::conceptPath).filter(Objects::nonNull)
            .collect(Collectors.toSet());
        checked.stream().filter(path -> !known.contains(path))
            .forEach(path -> warnings.add("Concept path not found in the dictionary, check it with search_concepts: " + path));
        return warnings;
    }

    /**
     * The whole argument object of {@code get_adapter_code}. The input schema is generated from this record as a root type, so the
     * recursive clause definitions sit at the schema root where their references resolve.
     *
     * @param query the query to write code for
     * @param resultType the result the code asks for
     * @param language the language to write
     * @param checkConcepts whether to check the concept paths against the dictionary first, false when absent
     */
    public record Input(
        @Schema(description = "The query to write code for") QueryInput query,
        @Schema(description = "count, cross_count, participant, or timestamp") ResultKind resultType,
        @Schema(description = "python, r, or bash") Language language,
        @Schema(requiredMode = NOT_REQUIRED, description = CHECK_CONCEPTS_DESCRIPTION) Boolean checkConcepts
    ) {
    }
}

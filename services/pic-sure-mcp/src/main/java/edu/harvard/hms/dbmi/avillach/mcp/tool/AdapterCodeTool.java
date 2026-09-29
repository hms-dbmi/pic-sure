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

    /** The tool description, with one complete worked example. */
    public static final String DESCRIPTION = """
        Write code that runs a query on PIC-SURE's authorized channel in the user's own environment, for what the open-access tools \
        cannot give: exact counts filtered by the user's consents, participant rows, or timestamps. This tool runs no query and returns \
        no data. It returns the code and its setup as separate fields: requires (the connector package, its minimum version, and the \
        runtime), check (a command that prints the installed connector version), install (the command that installs the connector), \
        and code. The code connects to this site with the user's own token, read from the PICSURE_TOKEN environment variable; it never \
        contains a token. Show the code to the user and do not run it unless the user asks. Before the code runs, run check. If the \
        connector is missing or older than requires.minVersion, show install to the user and run it only after the user confirms. \
        For r, minVersion is a release tag; compare it with the tag check prints. For bash there is no minVersion, and check only \
        confirms curl and jq are installed. \
        Results from this code are exact and filtered by the user's consents, unlike count_participants and cross_count, which return \
        obfuscated open-access counts that ignore consents, so always say which kind of number you report. resultType: count prints one \
        number; cross_count prints one number per concept path; participant and timestamp write a CSV file under picsure_results/ and \
        print only its size and path. language: python, r, or bash. python and r use the picsure adapters; bash calls the REST API \
        with curl and jq. Pick it from what the user works in (their request, project files, a notebook kernel, code they already \
        have) and ask when that does not settle it. The query takes the same shape as in count_participants, with no \
        result type and no not field. select adds output columns for participant and timestamp. Genomic filters take values only. Set \
        checkConcepts to true to check every concept path against the dictionary first; unknown paths come back in warnings and never \
        block the code. Example arguments: \
        {"language":"python","resultType":"participant","query":{"select":["\\\\phs000001\\\\bmi\\\\"],"phenotypicClause":\
        {"operator":"AND","phenotypicClauses":[\
        {"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs000001\\\\sex\\\\","values":["Female"]},\
        {"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs000001\\\\age\\\\","min":40,"max":65}]}}}""";

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
        if (input == null || input.query() == null) {
            throw new ToolFailure("Argument 'query' is required.");
        }
        if (input.resultType() == null) {
            throw new ToolFailure("Argument 'resultType' is required.");
        }
        if (input.language() == null) {
            throw new ToolFailure("Argument 'language' is required.");
        }
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
        @Schema(
            requiredMode = NOT_REQUIRED, description = "Check every concept path against the dictionary first. Defaults to false"
        ) Boolean checkConcepts
    ) {
    }
}

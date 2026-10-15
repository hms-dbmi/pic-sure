package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import edu.harvard.hms.dbmi.avillach.ai.error.ErrorSummary;
import edu.harvard.hms.dbmi.avillach.ai.mcp.McpToolGateway;
import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolDefinition;
import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolResult;
import edu.harvard.hms.dbmi.avillach.ai.model.ConverseModelClient;
import edu.harvard.hms.dbmi.avillach.ai.model.ConversationEntry;
import edu.harvard.hms.dbmi.avillach.ai.model.ConversationEntry.AssistantToolCallEntry;
import edu.harvard.hms.dbmi.avillach.ai.model.ConversationEntry.ToolResultEntry;
import edu.harvard.hms.dbmi.avillach.ai.model.ConversationEntry.UserEntry;
import edu.harvard.hms.dbmi.avillach.ai.model.ModelTurnResult;
import edu.harvard.hms.dbmi.avillach.ai.model.ModelUnavailableException;
import edu.harvard.hms.dbmi.avillach.ai.model.RequestedToolCall;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

/**
 * Runs one chat turn's tool-use loop. The dictionary/count lookups are dispatched to {@link McpToolGateway}; the query/facets/search
 * proposal tool ({@link ProposeQueryTool}) is the one exception, handled entirely locally -- it is not a data-access call, so it never
 * reaches the gateway (see {@link ProposeQueryTool}'s class Javadoc for why).
 *
 * <p>The request's own {@code query}/{@code facets}/{@code search} are validated before anything else ({@link ChatStateValidator}). A
 * proposal that passes every check -- shape, concept paths and facets all seen in a tool result this turn -- is held, and the final
 * {@link ChatResponse} carries each of {@code query}/{@code facets}/{@code search} only if a held proposal differs from what the request
 * arrived with. The UI hands a returned {@code query} straight to its execution logic, so nothing that failed a check is ever held: a
 * rejected proposal exists only as a tool error the model can repair, and what the model wrote is never echoed -- the response is rebuilt
 * from the validated fields. If the iteration cap is hit, whatever was already accepted still counts; a proposal still being repaired
 * simply never was.
 */
@Service
class ToolUseLoopService implements ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ToolUseLoopService.class);

    static final String SYSTEM_PROMPT = loadPrompt("/prompts/system-prompt.txt");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Only unambiguous verbs: a false match silently drops the researcher's columns, a miss just leaves one to remove in the UI. */
    private static final Pattern REMOVAL_REQUEST =
        Pattern.compile("(?i)\\b(?:remove|drop|delete|exclude|replace|swap|take out|get rid of)\\b");

    private static final String LIST_FACETS = "list_facets";
    private static final String COUNT_PARTICIPANTS = "count_participants";
    private static final String SEARCH_CONCEPTS = "search_concepts";

    private static final int MAX_EMPTY_RETRIES = 2;

    /** Sent as a tool result, not a user message: Converse needs alternating turns and every tool call answered. */
    static final String CAP_REACHED_RESULT =
        "Not run: the limit on tool calls for this message is reached. Do not call any more tools. Answer the researcher now in words, "
            + "using only the tool results you already have; if nothing matched, say so and suggest what they could search for instead.";

    /** gpt-oss retries a failing call with small argument changes that can't fix a server-side error, burning the iteration cap. */
    static final int MAX_REPEATED_FAILURES = 3;

    static final String REPEATED_FAILURE_NOTE =
        " (This tool already failed with this same error in this message, so retrying it, even with different arguments, will not help. "
            + "Use a different tool if one can answer, or answer the researcher now in words from the results you have and say what "
            + "could not be looked up.)";

    static final String REPEATED_FAILURE_STOP_RESULT =
        "This tool has failed the same way " + MAX_REPEATED_FAILURES + " times in this message, so no more tools will run. Do not call "
            + "any more tools. Answer the researcher now in words, using only the tool results you already have, and say what could not "
            + "be looked up.";

    static final String FALLBACK_TEXT =
        "I wasn't able to finish gathering the information for this request. Please try rephrasing, or ask again.";

    private final ConverseModelClient modelClient;
    private final McpToolGateway toolGateway;
    private final ProposeQueryTool proposeQueryTool;
    private final int maxIterations;
    private final Set<String> excludedTools;

    ToolUseLoopService(
        ConverseModelClient modelClient, McpToolGateway toolGateway, ProposeQueryTool proposeQueryTool, int maxIterations
    ) {
        this(modelClient, toolGateway, proposeQueryTool, maxIterations, Set.of());
    }

    /**
     * @param excludedTools gateway tool names withheld from the model and refused if it requests them anyway -- tools the connected UI
     *        has no use for. Costs input tokens on every model call otherwise, since the definitions are resent each time.
     */
    @Autowired
    ToolUseLoopService(
        ConverseModelClient modelClient, McpToolGateway toolGateway, ProposeQueryTool proposeQueryTool,
        @Value("${picsure.ai.max-tool-iterations:8}") int maxIterations,
        @Value("${picsure.ai.mcp.excluded-tools:get_adapter_code}") Set<String> excludedTools
    ) {
        this.modelClient = modelClient;
        this.toolGateway = toolGateway;
        this.proposeQueryTool = proposeQueryTool;
        this.maxIterations = maxIterations;
        this.excludedTools = Set.copyOf(excludedTools);
    }

    @Override
    public ChatResponse handle(ChatRequest request, CallerContext caller) {
        MDC.put("conversationId", request.conversationId());
        MDC.put("chatRequestId", request.requestId());
        TurnStats stats = new TurnStats();
        try {
            // Inside the try so a rejected request still gets its one turn line (a WARN naming the invalid field).
            ChatStateValidator.requireValid(request);
            return handleTurn(request, caller, stats);
        } catch (PicsureException e) {
            logFailedTurn(caller, stats, e.getStatus(), e.getErrorType(), e.getCause() == null ? e : e.getCause());
            throw e;
        } catch (RuntimeException e) {
            logFailedTurn(caller, stats, HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", e);
            // Already logged in one line; escaping as-is, the servlet container would log it again with its full stack trace.
            throw new PicsureException(
                HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Something went wrong answering this message. Please try again."
            );
        } finally {
            MDC.remove("conversationId");
            MDC.remove("chatRequestId");
        }
    }

    /** One sentence per line in the file, so prompt edits diff cleanly; joined back into one paragraph. */
    private static String loadPrompt(String resource) {
        try (InputStream in = ToolUseLoopService.class.getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip).filter(line -> !line.isEmpty())
                .collect(Collectors.joining(" "));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private enum Stop { NONE, ITERATION_CAP, REPEATED_FAILURE }

    private ChatResponse handleTurn(ChatRequest request, CallerContext caller, TurnStats stats) {
        List<ToolDefinition> tools =
            new ArrayList<>(listTools(caller).stream().filter(tool -> !excludedTools.contains(tool.name())).toList());
        tools.add(proposeQueryTool.definition());
        List<ConversationEntry> history = new ArrayList<>();
        history.add(new UserEntry(CurrentState.appendedTo(request)));
        TurnState state = new TurnState(ChatStateValidator.isAbsent(request.query()) ? null : ChatStateValidator.query(request.query()));
        state.conceptPaths.learnFrom(request.query());
        state.knownFacets.learnFrom(request.facets());

        log.debug("Chat turn start: tools={} maxIterations={}", tools.stream().map(ToolDefinition::name).toList(), maxIterations);

        ModelTurnResult turn = callModel(history, tools, stats);
        int emptyRetries = 0;
        while (stats.modelCalls < maxIterations && !state.toolsStopped) {
            List<String> unknownPaths = turn.hasToolCalls() || state.pathRepairAsked ? List.of() : unknownPathsIn(turn.text(), state);
            if (turn.hasToolCalls()) {
                history.add(new AssistantToolCallEntry(turn.text(), turn.toolCalls()));
                dispatchToolCalls(turn.toolCalls(), history, caller, state, stats);
            } else if (isEmptyReply(turn) && !state.hasProposal() && emptyRetries < MAX_EMPTY_RETRIES) {
                // gpt-oss sometimes ends with no content right after tool results; asking again samples a fresh reply.
                emptyRetries++;
                stats.corrections.add("emptyReplyRetry");
                log.debug("Model call #{} returned no content; retrying ({}/{})", stats.modelCalls, emptyRetries, MAX_EMPTY_RETRIES);
            } else if (!unknownPaths.isEmpty()) {
                // Asked once; a reply that is still wrong goes out as written rather than hiding what the model invented.
                state.pathRepairAsked = true;
                stats.corrections.add("pathRepair");
                log.debug("Reply names {} path(s) no tool returned; asking the model to correct them: {}", unknownPaths.size(), unknownPaths);
                history.add(new AssistantToolCallEntry(turn.text(), List.of()));
                history.add(new UserEntry(pathRepairMessage(unknownPaths, state.conceptPaths)));
            } else {
                break;
            }
            turn = callModel(history, tools, stats);
        }
        Stop stop = !turn.hasToolCalls() ? Stop.NONE : state.toolsStopped ? Stop.REPEATED_FAILURE : Stop.ITERATION_CAP;
        if (stop != Stop.NONE) {
            // One last call so the model can answer from the results it has; the total stays bounded at maxIterations + 1.
            history.add(new AssistantToolCallEntry(turn.text(), turn.toolCalls()));
            turn.toolCalls().forEach(call -> history.add(new ToolResultEntry(call.id(), call.name(), CAP_REACHED_RESULT)));
            turn = callModel(history, tools, stats);
        }
        if (!turn.hasToolCalls() && !state.hasProposal()) {
            proposeFromReply(turn.text(), history, caller, state, stats);
        }
        if (!turn.hasToolCalls() && !state.hasProposal()) {
            proposeCountedQuery(turn.text(), history, caller, state, stats);
        }

        ChatResponse response = respond(turn.hasToolCalls() ? null : turn.text(), state, request);
        List<String> proposed = new ArrayList<>();
        if (response.query() != null) {
            proposed.add("query");
        }
        if (response.facets() != null) {
            proposed.add("facets");
        }
        if (response.search() != null) {
            proposed.add("search");
        }
        log.info(
            "Chat turn: outcome={} stop={} proposed=[{}] userId={} {}", FALLBACK_TEXT.equals(response.response()) ? "fallback" : "answered",
            stop.name().toLowerCase(), String.join(", ", proposed), caller.userId(), stats.summary()
        );
        return response;
    }

    /** 5xx is this service or a dependency failing; anything else is a request this turn could not serve. */
    private static void logFailedTurn(CallerContext caller, TurnStats stats, HttpStatus status, String errorType, Throwable error) {
        log.atLevel(status.is5xxServerError() ? Level.ERROR : Level.WARN).log(
            "Chat turn: outcome=error status={} errorType={} userId={} {} error={}", status.value(), errorType, caller.userId(),
            stats.summary(), ErrorSummary.of(error)
        );
    }

    private List<ToolDefinition> listTools(CallerContext caller) {
        try {
            return toolGateway.listTools(caller);
        } catch (RuntimeException e) {
            PicsureException unavailable = new PicsureException(
                HttpStatus.SERVICE_UNAVAILABLE, "tools_unavailable", "The data dictionary is currently unavailable. Please try again."
            );
            unavailable.initCause(e);
            throw unavailable;
        }
    }

    /** gpt-oss sometimes writes its proposal as the reply instead of calling propose_query. */
    private void proposeFromReply(String text, List<ConversationEntry> history, CallerContext caller, TurnState state, TurnStats stats) {
        String arguments = ReplyText.proposalArguments(text);
        if (arguments != null) {
            stats.corrections.add("proposalFromReplyText");
            log.debug("Reply is a proposal written as text; checking it as a propose_query call");
            dispatchToolCalls(List.of(new RequestedToolCall("reply-text", ProposeQueryTool.NAME, arguments)), history, caller, state, stats);
        }
    }

    /** gpt-oss sometimes counts a cohort and says the query changed without ever calling propose_query. */
    private void proposeCountedQuery(String text, List<ConversationEntry> history, CallerContext caller, TurnState state, TurnStats stats) {
        if (state.lastCountedQuery != null && ReplyText.claimsQueryChanged(text)) {
            stats.corrections.add("countedQueryProposal");
            log.debug("Reply says the query changed but nothing was proposed; checking the counted query as a propose_query call");
            String arguments = JSON.createObjectNode().set("query", state.lastCountedQuery).toString();
            dispatchToolCalls(List.of(new RequestedToolCall("counted-query", ProposeQueryTool.NAME, arguments)), history, caller, state, stats);
        }
    }

    private static boolean isEmptyReply(ModelTurnResult turn) {
        return turn.text() == null || turn.text().isBlank();
    }

    private static List<String> unknownPathsIn(String text, TurnState state) {
        return ReplyText.unknownPaths(ReplyText.cleaned(text, state.conceptPaths), state.conceptPaths);
    }

    static String pathRepairMessage(List<String> unknownPaths, ConceptPaths paths) {
        StringBuilder message = new StringBuilder(
            "Your reply names concept paths that no tool result returned, so the researcher cannot find them:\n"
        );
        for (String path : unknownPaths) {
            String owner = paths.ownerOfValueIn(path);
            message.append("- `").append(path).append("`: ");
            if (owner != null) {
                message.append("\"").append(ConceptPaths.segments(path).getLast()).append("\" is not a variable. It is one of the values of `")
                    .append(owner).append("`. Name that path and mention the value.\n");
            } else if (paths.isAncestorOfSeen(path)) {
                message.append("this is a parent of paths the results returned, but no tool returned it. Name the returned paths under it "
                    + "instead, each in full.\n");
            } else {
                message.append("no tool returned this path. Use a path exactly as a tool returned it, or leave it out.\n");
            }
        }
        message.append("Write your whole reply to the researcher again with these paths fixed. Do not mention this correction.");
        return message.toString();
    }

    /** Attaches each accepted proposal part that actually differs from the state the request arrived with. */
    private ChatResponse respond(String text, TurnState state, ChatRequest request) {
        String shownSearch = request.search() == null ? "" : request.search();
        Set<FacetSelection> shownFacets = request.facets() == null ? Set.of() : Set.copyOf(request.facets());
        ProposeQueryInput currentQuery = state.currentQuery;
        JsonNode query = null;
        if (state.query != null) {
            ProposeQueryInput proposedQuery = keepingCurrentSelect(state.query, currentQuery, request.message());
            // Same treatment on both sides, so a select the UI would add anyway, or a not:false the model left out, doesn't count as a change.
            ProposeQueryInput emitted = proposedQuery.mapPaths(state.conceptPaths::canonical).withFilterPathsSelected();
            boolean unchanged = currentQuery != null && ChatStateValidator.toJson(emitted.withoutFalseNots())
                .equals(ChatStateValidator.toJson(currentQuery.withFilterPathsSelected().withoutFalseNots()));
            query = unchanged ? null : ChatStateValidator.toJson(emitted);
        }
        List<FacetSelection> facets = null;
        if (state.facets != null) {
            facets = Set.copyOf(state.facets).equals(shownFacets) ? null : List.copyOf(state.facets);
        }
        String search = state.search == null || state.search.equals(shownSearch) ? null : state.search;
        String reply = ReplyText.cleaned(text, state.conceptPaths);
        if (!state.hasProposal() && replyShowsSearchResults(reply, state)) {
            String found = state.searches.lastMatchedSearchWithoutMisses();
            search = found == null || found.equals(shownSearch) ? null : found;
            List<FacetSelection> narrowing = state.searches.facetsForSearch(found);
            facets = narrowing == null || Set.copyOf(narrowing).equals(shownFacets) ? null : List.copyOf(narrowing);
        }
        if (reply == null || reply.isBlank()) {
            reply = query != null || facets != null || search != null ? ReplyText.PROPOSAL_ATTACHED_TEXT : FALLBACK_TEXT;
        }
        return new ChatResponse(reply, query, facets, search);
    }

    /** Nothing was proposed, but the reply talks about variables a search found, so the table should show that search. */
    private static boolean replyShowsSearchResults(String reply, TurnState state) {
        boolean namesOneSearchsPaths = state.searches.matchedSearches() == 1 && ReplyText.namesKnownPath(reply, state.conceptPaths);
        return ReplyText.claimsScreenChanged(reply) || state.searches.lastSearchWasNarrowed() || namesOneSearchsPaths;
    }

    /** Models often propose only the variables they're adding, so the researcher's columns are kept unless they asked to remove one. */
    private static ProposeQueryInput keepingCurrentSelect(ProposeQueryInput proposed, ProposeQueryInput current, String message) {
        if (current == null || current.select() == null || current.select().isEmpty()) {
            return proposed;
        }
        boolean proposedHasSelect = proposed.select() != null && !proposed.select().isEmpty();
        if (proposedHasSelect && message != null && REMOVAL_REQUEST.matcher(message).find()) {
            return proposed;
        }
        Map<String, String> merged = new LinkedHashMap<>();
        current.select().forEach(path -> merged.putIfAbsent(ConceptPaths.normalize(path), path));
        if (proposedHasSelect) {
            proposed.select().forEach(path -> merged.putIfAbsent(ConceptPaths.normalize(path), path));
        }
        return new ProposeQueryInput(List.copyOf(merged.values()), proposed.phenotypicClause(), proposed.genomicFilters());
    }

    private ModelTurnResult callModel(List<ConversationEntry> history, List<ToolDefinition> tools, TurnStats stats) {
        long start = System.nanoTime();
        ModelTurnResult result;
        try {
            result = modelClient.send(SYSTEM_PROMPT, history, tools);
        } catch (ModelUnavailableException e) {
            // Distinct from a tool/MCP failure (see dispatchToolCalls), which is folded back into the
            // conversation and never reaches the HTTP layer -- the two must stay distinguishable.
            PicsureException unavailable = new PicsureException(
                HttpStatus.SERVICE_UNAVAILABLE, "model_unavailable", "The AI model is currently unavailable. Please try again."
            );
            unavailable.initCause(e);
            throw unavailable;
        }
        stats.addModelCall(result);
        log.info(
            "Model call: n={} durationMs={} done={} toolCalls={} textChars={} inputTokens={} outputTokens={}", stats.modelCalls,
            (System.nanoTime() - start) / 1_000_000, result.done(),
            result.hasToolCalls() ? result.toolCalls().stream().map(RequestedToolCall::name).toList() : List.of(),
            result.text() == null ? 0 : result.text().length(), result.inputTokens(), result.outputTokens()
        );
        log.debug("Model call #{} text: {}", stats.modelCalls, result.text());
        return result;
    }

    private void dispatchToolCalls(
        List<RequestedToolCall> toolCalls, List<ConversationEntry> history, CallerContext caller, TurnState state, TurnStats stats
    ) {
        for (RequestedToolCall call : toolCalls) {
            log.debug("Tool call requested: name={} id={} {}", call.name(), call.id(), call.argumentsJson());
            long start = System.nanoTime();
            JsonNode arguments = readJson(call.argumentsJson());
            ToolResult result = state.toolsStopped ? ToolResult.failure(CAP_REACHED_RESULT)
                : withRepeatedFailureCheck(call, callToolSafely(call, arguments, caller, state), state);
            if (!result.error()) {
                state.conceptPaths.learnFrom(result.content());
                if (SEARCH_CONCEPTS.equals(call.name())) {
                    state.searches.learn(arguments, readJson(result.content()));
                }
                if (COUNT_PARTICIPANTS.equals(call.name())) {
                    state.learnCountedQuery(arguments);
                }
                if (LIST_FACETS.equals(call.name())) {
                    state.facetsListed = true;
                    state.knownFacets.learnFromListFacets(result.content());
                }
            }
            stats.addToolCall(call.name(), result);
            log.info(
                "Tool call: name={} id={} durationMs={} error={} chars={}", call.name(), call.id(), (System.nanoTime() - start) / 1_000_000,
                result.error(), result.content() == null ? 0 : result.content().length()
            );
            log.debug("Tool result content: name={} id={} {}", call.name(), call.id(), result.content());
            history.add(new ToolResultEntry(call.id(), call.name(), result.content()));
        }
    }

    /** Keyed by error text, not arguments: the looping retries vary their arguments while the error stays the same. */
    private static ToolResult withRepeatedFailureCheck(RequestedToolCall call, ToolResult result, TurnState state) {
        if (!result.error()) {
            return result;
        }
        int failures = state.failureCounts.merge(call.name() + "\n" + result.content(), 1, Integer::sum);
        if (failures >= MAX_REPEATED_FAILURES) {
            state.toolsStopped = true;
            log.warn("Tool {} failed the same way {} times; stopping tool use for this turn", call.name(), failures);
            return ToolResult.failure(REPEATED_FAILURE_STOP_RESULT);
        }
        return failures > 1 ? ToolResult.failure(result.content() + REPEATED_FAILURE_NOTE) : result;
    }

    /** gpt-oss uses guessed facets without calling list_facets; looking them up once lets the check run against real facets. */
    private void lookUpFacetsIfNeeded(JsonNode arguments, CallerContext caller, TurnState state) {
        JsonNode facets = arguments.path("facets");
        if (state.facetsListed || !facets.isArray() || facets.isEmpty()) {
            return;
        }
        state.facetsListed = true;
        String search = arguments.path("search").isTextual() ? arguments.path("search").asText() : "";
        log.debug("Proposal has facets but no list_facets ran this turn; looking them up");
        try {
            ToolResult result = toolGateway.callTool(LIST_FACETS, JSON.createObjectNode().put("query", search).toString(), caller);
            if (!result.error()) {
                state.knownFacets.learnFromListFacets(result.content());
            }
        } catch (RuntimeException e) {
            // The model didn't make this call, so its failure isn't reported as the model's; unknown facets are then refused as usual.
            log.warn("Facet lookup failed; checking the proposal's facets against what is already known: {}", ErrorSummary.of(e));
        }
    }

    /** @return the refusal, or null when every facet is known */
    private ToolResult refuseUnknownFacets(JsonNode arguments, CallerContext caller, TurnState state) {
        List<FacetSelection> facets = FacetSelection.listedIn(arguments);
        if (facets.isEmpty()) {
            return null;
        }
        lookUpFacetsIfNeeded(arguments, caller, state);
        FacetSelection unknown = state.knownFacets.firstUnknownIn(facets);
        return unknown == null ? null : ToolResult.failure(ProposeQueryTool.unknownFacetMessage(unknown));
    }

    private static JsonNode readJson(String json) {
        try {
            return json == null ? MissingNode.getInstance() : JSON.readTree(json);
        } catch (JsonProcessingException e) {
            return MissingNode.getInstance();
        }
    }

    /** Given an empty category list, the model has invented studies and counts; a note in the result itself stops that. */
    private static ToolResult withNoCategoriesNote(ToolResult result) {
        if (result.error() || result.content() == null) {
            return result;
        }
        if (readJson(result.content()) instanceof ObjectNode object && object.path("categories").isArray()
            && object.path("categories").isEmpty()) {
            object.put(
                "note", "There are no categories, studies or data types to browse by. Do not invent any or show a list of them. "
                    + "Tell the researcher there are none, and call propose_query with a search for what they asked about instead."
            );
            return ToolResult.success(object.toString());
        }
        return result;
    }

    /** A tool/MCP failure never crashes the turn -- it becomes a normal, model-visible error result. */
    private ToolResult callToolSafely(RequestedToolCall call, JsonNode arguments, CallerContext caller, TurnState state) {
        try {
            if (excludedTools.contains(call.name())) {
                return ToolResult.failure("Unknown tool: " + call.name());
            }
            // A concept path the model was never shown is invented (small models copy the placeholder paths in tool examples), and
            // would run against nothing or be proposed to the researcher. Refuse it so the model searches first.
            // Arguments that don't parse can't be checked, so they are refused too rather than slipping past the guard.
            if (ConceptPaths.isMalformed(call.argumentsJson())) {
                return ToolResult.failure(
                    "The arguments for " + call.name() + " are not valid JSON. Backslashes in a conceptPath must be escaped as \\\\ in "
                        + "JSON. Call the tool again with valid JSON arguments."
                );
            }
            String unseenPath = state.conceptPaths.firstUnseenIn(call.argumentsJson());
            if (unseenPath != null) {
                return ToolResult.failure(
                    "Unknown concept path: " + unseenPath + ". A conceptPath must come from a search_concepts or get_concept result in "
                        + "this conversation, exactly as returned. Call search_concepts first, wait for its results, then use a path from them."
                );
            }
            if (ProposeQueryTool.NAME.equals(call.name())) {
                lookUpFacetsIfNeeded(arguments, caller, state);
                ProposeQueryTool.Outcome outcome = proposeQueryTool.propose(call.argumentsJson(), state.knownFacets);
                return outcome.proposal() == null ? outcome.result() : ProposalRules.apply(outcome.proposal(), state);
            }
            if (SEARCH_CONCEPTS.equals(call.name())) {
                ToolResult unknownFacet = refuseUnknownFacets(arguments, caller, state);
                if (unknownFacet != null) {
                    return unknownFacet;
                }
                ToolResult balanced = TermBalancedSearch.search(call.argumentsJson(), json -> toolGateway.callTool(SEARCH_CONCEPTS, json, caller));
                if (balanced != null) {
                    return balanced;
                }
            }
            ToolResult result = toolGateway.callTool(call.name(), call.argumentsJson(), caller);
            return LIST_FACETS.equals(call.name()) ? withNoCategoriesNote(result) : result;
        } catch (RuntimeException e) {
            log.warn("Tool call failed: name={} id={} error={}", call.name(), call.id(), ErrorSummary.of(e));
            return ToolResult.failure("The " + call.name() + " lookup failed: " + e.getMessage());
        }
    }
}

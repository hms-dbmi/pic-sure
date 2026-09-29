package edu.harvard.hms.dbmi.avillach.mcp.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.codegen.CodegenCases;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryClient;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryPage;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.OpenQueryClient;
import edu.harvard.hms.dbmi.avillach.mcp.tool.AdapterCodeTool;
import edu.harvard.hms.dbmi.avillach.mcp.tool.ConceptDetailTool;
import edu.harvard.hms.dbmi.avillach.mcp.tool.ConceptSearchTool;
import edu.harvard.hms.dbmi.avillach.mcp.tool.CountTool;
import edu.harvard.hms.dbmi.avillach.mcp.tool.CrossCountTool;
import edu.harvard.hms.dbmi.avillach.mcp.tool.FacetTool;
import edu.harvard.hms.dbmi.avillach.mcp.tool.ToolFailure;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Covers {@link ToolAuditAspect} around the real tool classes, proxied the way Spring proxies them: one event per call with the
 * annotation's type and action, the outcome, the request ID, the user ID and only the allow-listed argument fields, no credential or query
 * body in any event, a logging failure that never reaches the tool, and a request ID that is in the MDC for the call and gone after it.
 */
class ToolAuditAspectTest {

    private static final String BEARER = "SECRET-BEARER-7c1e";
    private static final String API_KEY = "SECRET-API-KEY-93ab";
    private static final String MCP_TOKEN = "SECRET-MCP-TOKEN-51d0";
    private static final String BODY_PATH = "SECRET-BODY-PATH-e402";
    private static final ObjectMapper JSON = new ObjectMapper();

    private LoggingClient client;
    private DictionaryClient dictionary;
    private OpenQueryClient openQuery;
    private McpTransportContext context;
    private ConceptSearchTool search;
    private ConceptDetailTool detail;
    private FacetTool facets;
    private CountTool count;
    private CrossCountTool crossCount;

    @BeforeEach
    void setUp() {
        client = mock(LoggingClient.class);
        dictionary = mock(DictionaryClient.class);
        openQuery = mock(OpenQueryClient.class);
        context = McpTransportContext
            .create(Map.of(CallerHeaders.KEY, new CallerHeaders("Bearer " + BEARER, API_KEY, "req-42", "203.0.113.7", "user-7")));
        ToolAuditAspect aspect = new ToolAuditAspect(client);
        search = proxy(new ConceptSearchTool(dictionary), aspect);
        detail = proxy(new ConceptDetailTool(dictionary), aspect);
        facets = proxy(new FacetTool(dictionary), aspect);
        count = proxy(new CountTool(openQuery), aspect);
        crossCount = proxy(new CrossCountTool(openQuery), aspect);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static <T> T proxy(T target, ToolAuditAspect aspect) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    private LoggingEvent onlyEvent() {
        ArgumentCaptor<LoggingEvent> event = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(client, times(1)).send(event.capture(), isNull(), org.mockito.ArgumentMatchers.eq("req-42"));
        return event.getValue();
    }

    private static String serialized(LoggingEvent event) throws Exception {
        return JSON.writeValueAsString(event);
    }

    @Test
    void searchConceptsSendsOneSearchEventWithTheAllowListedFields() {
        when(dictionary.searchConcepts(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), any()))
            .thenReturn(new DictionaryPage(List.of(), 0L, null));

        search.searchConcepts(context, "blood pressure", 2, 15);

        LoggingEvent event = onlyEvent();
        assertThat(event.getEventType()).isEqualTo("SEARCH");
        assertThat(event.getAction()).isEqualTo("concept.search");
        assertThat(event.getRequest().getRequestId()).isEqualTo("req-42");
        assertThat(event.getError()).isNull();
        assertThat(event.getMetadata()).containsExactly(
            Map.entry("outcome", "success"), Map.entry("user_id", "user-7"), Map.entry("query", "blood pressure"), Map.entry("page", 2),
            Map.entry("page_size", 15)
        );
    }

    @Test
    void listFacetsSendsAFacetSearchEvent() {
        when(dictionary.listFacets(any(), any())).thenReturn(List.of());

        facets.listFacets(context, "sex");

        LoggingEvent event = onlyEvent();
        assertThat(event.getEventType()).isEqualTo("SEARCH");
        assertThat(event.getAction()).isEqualTo("facet.search");
        assertThat(event.getMetadata()).containsEntry("outcome", "success").containsEntry("query", "sex");
    }

    @Test
    void getConceptSendsAConceptDetailEventWithDatasetAndPath() {
        when(dictionary.conceptDetail(any(), any(), any())).thenReturn(null);

        assertThatThrownBy(() -> detail.getConcept(context, "phs1", "\\phs1\\sex\\")).isInstanceOf(ToolFailure.class);

        LoggingEvent event = onlyEvent();
        assertThat(event.getAction()).isEqualTo("concept.detail");
        assertThat(event.getMetadata()).containsEntry("dataset", "phs1").containsEntry("concept_path", "\\phs1\\sex\\")
            .containsEntry("outcome", "failure");
    }

    @Test
    void aToolFailureIsAFailureEventThatKeepsTheActionAndRethrows() {
        assertThatThrownBy(() -> search.searchConcepts(context, " ", null, null)).isInstanceOf(ToolFailure.class)
            .hasMessage("Argument 'query' is required.");

        LoggingEvent event = onlyEvent();
        assertThat(event.getEventType()).isEqualTo("SEARCH");
        assertThat(event.getAction()).isEqualTo("concept.search");
        assertThat(event.getMetadata()).containsEntry("outcome", "failure");
        assertThat(event.getError()).containsExactly(Map.entry("error_type", "tool_failure"));
    }

    @Test
    void anUnexpectedExceptionIsAnInternalFailureEventAndRethrows() {
        when(dictionary.listFacets(any(), any())).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> facets.listFacets(context, null)).isInstanceOf(RuntimeException.class);

        LoggingEvent event = onlyEvent();
        assertThat(event.getMetadata()).containsEntry("outcome", "failure");
        assertThat(event.getError()).containsExactly(Map.entry("error_type", "internal"));
    }

    @Test
    void countSendsAQuerySyncEventWithResultTypeCountAndNothingFromTheBody() throws Exception {
        when(openQuery.querySync(any(), any())).thenReturn("12 ±3");

        count.handle(context, Map.of("query", Map.of("phenotypicClause", filter(BODY_PATH))));

        LoggingEvent event = onlyEvent();
        assertThat(event.getEventType()).isEqualTo("QUERY");
        assertThat(event.getAction()).isEqualTo("query.sync");
        assertThat(event.getMetadata())
            .containsExactly(Map.entry("outcome", "success"), Map.entry("user_id", "user-7"), Map.entry("result_type", "COUNT"));
        assertThat(serialized(event)).doesNotContain(BODY_PATH);
    }

    @Test
    void crossCountSendsTheChosenResultType() {
        when(openQuery.querySync(any(), any())).thenReturn("{}");

        crossCount.handle(context, Map.of("resultType", "CATEGORICAL_CROSS_COUNT", "query", Map.of()));

        LoggingEvent event = onlyEvent();
        assertThat(event.getAction()).isEqualTo("query.sync");
        assertThat(event.getMetadata()).containsEntry("result_type", "CATEGORICAL_CROSS_COUNT").doesNotContainKey("query");
    }

    @Test
    void aBindingFailureOnAQueryToolIsAFailureEvent() {
        Map<String, Object> withNot = Map.of("query", Map.of("phenotypicClause", Map.of("operator", "AND", "not", true)));

        assertThatThrownBy(() -> count.handle(context, withNot)).isInstanceOf(ToolFailure.class)
            .hasMessage("Field 'not' is not part of this tool's input.");

        LoggingEvent event = onlyEvent();
        assertThat(event.getAction()).isEqualTo("query.sync");
        assertThat(event.getMetadata()).containsEntry("outcome", "failure").containsEntry("result_type", "COUNT");
        assertThat(event.getError()).containsExactly(Map.entry("error_type", "tool_failure"));
    }

    @Test
    void adapterCodeSendsItsLowercaseResultTypeAndNothingFromTheBody() throws Exception {
        AdapterCodeTool adapterCode =
            proxy(new AdapterCodeTool(CodegenCases.generator(CodegenCases.SETUP), dictionary), new ToolAuditAspect(client));
        Map<String, Object> arguments = Map.of(
            "resultType", "participant", "language", "bash", "query",
            Map.of("select", List.of(BODY_PATH), "phenotypicClause", Map.of("phenotypicFilterType", "REQUIRED", "conceptPath", BODY_PATH))
        );

        adapterCode.handle(context, arguments);

        LoggingEvent event = onlyEvent();
        assertThat(event.getEventType()).isEqualTo("OTHER");
        assertThat(event.getAction()).isEqualTo("adapter.code");
        assertThat(event.getMetadata()).containsEntry("result_type", "participant").containsEntry("outcome", "success");
        assertThat(serialized(event)).doesNotContain(BODY_PATH);
    }

    @Test
    void anArbitraryResultTypeStringIsNotCopiedIntoTheEvent() {
        assertThatThrownBy(() -> crossCount.handle(context, Map.of("resultType", BODY_PATH + " lower", "query", Map.of())))
            .isInstanceOf(ToolFailure.class);

        assertThat(onlyEvent().getMetadata()).doesNotContainKey("result_type");
    }

    @Test
    void noCredentialAppearsInAnyEvent() throws Exception {
        when(dictionary.searchConcepts(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), any()))
            .thenReturn(new DictionaryPage(List.of(), 0L, null));
        when(openQuery.querySync(any(), any())).thenReturn("12 ±3");
        List<String> tokens = List.of(BEARER, API_KEY, MCP_TOKEN);

        search.searchConcepts(context, "sex", null, null);
        count.handle(context, Map.of("query", Map.of("phenotypicClause", filter(BODY_PATH))));
        assertThatThrownBy(() -> count.handle(context, Map.of("query", Map.of("bogus", MCP_TOKEN)))).isInstanceOf(ToolFailure.class);

        ArgumentCaptor<LoggingEvent> events = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(client, times(3)).send(events.capture(), isNull(), any());
        for (LoggingEvent event : events.getAllValues()) {
            String json = serialized(event);
            assertThat(json).doesNotContain(tokens).doesNotContain(BODY_PATH);
        }
    }

    @Test
    void aMissingUserIdLeavesTheFieldOut() {
        when(dictionary.listFacets(any(), any())).thenReturn(List.of());
        McpTransportContext anonymous =
            McpTransportContext.create(Map.of(CallerHeaders.KEY, new CallerHeaders(null, null, "req-42", null)));

        facets.listFacets(anonymous, null);

        assertThat(onlyEvent().getMetadata()).doesNotContainKey("user_id");
    }

    @Test
    void aThrowingLoggingClientNeverFailsTheToolOrChangesItsException() {
        doThrow(new IllegalStateException("logging is down")).when(client).send(any(), any(), any());
        when(dictionary.listFacets(any(), any())).thenReturn(List.of());

        assertThat(facets.listFacets(context, "sex")).isNotNull();
        assertThatThrownBy(() -> search.searchConcepts(context, " ", null, null)).isInstanceOf(ToolFailure.class)
            .hasMessage("Argument 'query' is required.");
    }

    @Test
    void aDisabledClientLeavesTheToolWorking() {
        LoggingClient disabled = LoggingClient.noOp();
        FacetTool quiet = proxy(new FacetTool(dictionary), new ToolAuditAspect(disabled));
        when(dictionary.listFacets(any(), any())).thenReturn(List.of());

        assertThat(quiet.listFacets(context, null)).isNotNull();
    }

    @Test
    void theRequestIdIsInTheMdcDuringTheCallAndClearAfter() {
        AtomicReference<String> seen = new AtomicReference<>();
        when(dictionary.listFacets(any(), any())).thenAnswer(invocation -> {
            seen.set(MDC.get(CallerHeaders.MDC_KEY));
            return new ArrayList<>();
        });
        assertThat(MDC.get(CallerHeaders.MDC_KEY)).isNull();

        facets.listFacets(context, null);

        assertThat(seen.get()).isEqualTo("req-42");
        assertThat(MDC.get(CallerHeaders.MDC_KEY)).isNull();
    }

    @Test
    void theMdcIsClearAfterAFailedCall() {
        assertThatThrownBy(() -> search.searchConcepts(context, "", null, null)).isInstanceOf(ToolFailure.class);

        assertThat(MDC.get(CallerHeaders.MDC_KEY)).isNull();
    }

    @Test
    void longSearchTextIsTruncatedAndControlCharactersAreReplaced() {
        when(dictionary.listFacets(any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> facets.listFacets(context, "a\nb" + "x".repeat(600))).isInstanceOf(ToolFailure.class);

        assertThat(String.valueOf(onlyEvent().getMetadata().get("query"))).hasSize(ToolAuditAspect.MAX_FIELD_LENGTH).startsWith("a b");
    }

    private static Map<String, Object> filter(String conceptPath) {
        return Map.of("phenotypicFilterType", "FILTER", "conceptPath", conceptPath, "values", List.of("Female"));
    }
}

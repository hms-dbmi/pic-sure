package edu.harvard.hms.dbmi.avillach.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.config.GatewayRequestInterceptor;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryClient;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Covers the three dictionary tools against a mock gateway: the request each one sends (URL, query parameters, body, replayed caller
 * headers, empty consents), how results are mapped and capped, and how downstream failures become {@link ToolFailure}s that carry no
 * downstream text.
 */
class DictionaryToolsTest {

    private static final String GATEWAY = "http://gateway.test:8080";
    private static final String BEARER = "Bearer caller-token";
    private static final String LEAK = "SECRET-DOWNSTREAM-BODY-7f3a";

    private MockRestServiceServer server;
    private ConceptSearchTool search;
    private FacetTool facets;
    private ConceptDetailTool detail;
    private DictionaryClient client;
    private McpTransportContext context;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder =
            RestClient.builder().baseUrl(GATEWAY).requestInterceptor(new GatewayRequestInterceptor(GATEWAY, "mcp-token"));
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DictionaryClient(builder.build());
        search = new ConceptSearchTool(client);
        facets = new FacetTool(client);
        detail = new ConceptDetailTool(client);
        context = McpTransportContext.create(Map.of(CallerHeaders.KEY, new CallerHeaders(BEARER, null, "req-1", null)));
    }

    @Test
    void searchPostsFilterWithEmptyConsentsAndReplaysTheCaller() {
        server.expect(requestTo(GATEWAY + "/dictionary/concepts?page_number=0&page_size=10")).andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", BEARER)).andExpect(header("X-Request-Id", "req-1"))
            .andExpect(header("X-PIC-SURE-MCP-TOKEN", "mcp-token"))
            .andExpect(content().json("{\"facets\":[],\"search\":\"blood pressure\",\"consents\":[]}", true))
            .andRespond(withSuccess("{\"content\":[],\"totalElements\":0}", MediaType.APPLICATION_JSON));

        ConceptSearchResult result = search.searchConcepts(context, "blood pressure", null, null, null);

        server.verify();
        assertThat(result.query()).isEqualTo("blood pressure");
        assertThat(result.page()).isZero();
        assertThat(result.pageSize()).isEqualTo(10);
        assertThat(result.total()).isZero();
        assertThat(result.concepts()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"0,0,1", "2,7,7", "-4,100,25", "1,0,1"})
    void searchBoundsPagingArguments(int page, int pageSize, int expectedSize) {
        server.expect(requestTo(GATEWAY + "/dictionary/concepts?page_number=" + Math.max(page, 0) + "&page_size=" + expectedSize))
            .andRespond(withSuccess("{\"content\":[],\"totalElements\":0}", MediaType.APPLICATION_JSON));

        ConceptSearchResult result = search.searchConcepts(context, "sex", null, page, pageSize);

        server.verify();
        assertThat(result.pageSize()).isEqualTo(expectedSize);
    }

    @Test
    void searchMapsAContinuousAndACategoricalConcept() {
        String values25 = IntStream.range(0, 25).mapToObj(i -> "\"v" + i + "\"").collect(Collectors.joining(","));
        server.expect(requestTo(GATEWAY + "/dictionary/concepts?page_number=0&page_size=10")).andRespond(withSuccess("""
            {"content":[
              {"type":"Continuous","conceptPath":"\\\\phs1\\\\age\\\\","name":"age","display":"Age","dataset":"phs1",
               "description":"Age in years","allowFiltering":true,"min":18.0,"max":90.5,"studyAcronym":"FHS",
               "meta":{"secret":"x"},"children":[{"conceptPath":"c"}],"table":{"a":1},"study":{"b":2},"unknownField":true},
              {"type":"Categorical","conceptPath":"\\\\phs1\\\\sex\\\\","name":"sex","display":"Sex","dataset":"phs1",
               "description":"","values":[%s],"studyAcronym":"FHS","meta":{"secret":"y"}}
            ],"pageable":{"pageNumber":0},"totalElements":38,"totalPages":4}""".formatted(values25), MediaType.APPLICATION_JSON));

        ConceptSearchResult result = search.searchConcepts(context, "x", null, 0, 10);

        assertThat(result.total()).isEqualTo(38);
        ConceptSummary age = result.concepts().get(0);
        assertThat(age.type()).isEqualTo("continuous");
        assertThat(age.conceptPath()).isEqualTo("\\phs1\\age\\");
        assertThat(age.min()).isEqualTo(18.0);
        assertThat(age.max()).isEqualTo(90.5);
        assertThat(age.values()).isNull();
        assertThat(age.valuesOmitted()).isNull();
        assertThat(age.meta()).isNull();
        assertThat(age.name()).isNull();
        ConceptSummary sex = result.concepts().get(1);
        assertThat(sex.type()).isEqualTo("categorical");
        assertThat(sex.values()).hasSize(20).startsWith("v0").endsWith("v19");
        assertThat(sex.valuesOmitted()).isEqualTo(5);
        assertThat(sex.min()).isNull();
        assertThat(sex.meta()).isNull();
    }

    @Test
    void searchReadsTheTotalFromTheNestedPageForm() {
        server.expect(requestTo(GATEWAY + "/dictionary/concepts?page_number=0&page_size=10"))
            .andRespond(withSuccess("{\"content\":[],\"page\":{\"size\":10,\"totalElements\":41}}", MediaType.APPLICATION_JSON));

        assertThat(search.searchConcepts(context, "x", null, null, null).total()).isEqualTo(41);
    }

    @Test
    void categoricalWithExactlyTheCapReportsNoOmission() {
        String values20 = IntStream.range(0, 20).mapToObj(i -> "\"v" + i + "\"").collect(Collectors.joining(","));
        server.expect(requestTo(GATEWAY + "/dictionary/concepts?page_number=0&page_size=10")).andRespond(
            withSuccess(
                "{\"content\":[{\"type\":\"Categorical\",\"conceptPath\":\"p\",\"values\":[" + values20 + "]}],\"totalElements\":1}",
                MediaType.APPLICATION_JSON
            )
        );

        ConceptSummary concept = search.searchConcepts(context, "x", null, null, null).concepts().get(0);

        assertThat(concept.values()).hasSize(20);
        assertThat(concept.valuesOmitted()).isNull();
    }

    @Test
    void searchRejectsBadQueriesWithoutCallingTheGateway() {
        assertThatThrownBy(() -> search.searchConcepts(context, null, null, null, null)).isInstanceOf(ToolFailure.class)
            .hasMessage("Give either 'query' or 'terms'.");
        assertThatThrownBy(() -> search.searchConcepts(context, "   ", null, null, null)).isInstanceOf(ToolFailure.class);
        assertThatThrownBy(() -> search.searchConcepts(context, "x".repeat(501), null, null, null)).isInstanceOf(ToolFailure.class);
        assertThatThrownBy(() -> search.searchConcepts(context, "a\nb", null, null, null)).isInstanceOf(ToolFailure.class);
        server.verify();
    }

    @Test
    void facetsPostsTheSameFilterShapeWithEmptyConsents() {
        server.expect(requestTo(GATEWAY + "/dictionary/facets")).andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", BEARER)).andExpect(header("X-PIC-SURE-MCP-TOKEN", "mcp-token"))
            .andExpect(content().json("{\"facets\":[],\"search\":\"asthma\",\"consents\":[]}", true)).andRespond(
                withSuccess(
                    "[{\"name\":\"study_ids\",\"display\":\"Study\",\"description\":\"d\",\"facets\":"
                        + "[{\"name\":\"phs1\",\"display\":\"FHS\",\"count\":12,\"children\":[],\"meta\":{}}]}]",
                    MediaType.APPLICATION_JSON
                )
            );

        FacetResult result = facets.listFacets(context, "asthma");

        server.verify();
        assertThat(result.query()).isEqualTo("asthma");
        assertThat(result.categoriesOmitted()).isNull();
        assertThat(result.categories()).singleElement().satisfies(category -> {
            assertThat(category.name()).isEqualTo("study_ids");
            assertThat(category.facetsOmitted()).isNull();
            assertThat(category.facets()).singleElement().satisfies(f -> {
                assertThat(f.name()).isEqualTo("phs1");
                assertThat(f.count()).isEqualTo(12);
            });
        });
    }

    @Test
    void facetsWithNoSearchSendsAnEmptySearchString() {
        server.expect(requestTo(GATEWAY + "/dictionary/facets"))
            .andExpect(content().json("{\"facets\":[],\"search\":\"\",\"consents\":[]}", true))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        FacetResult result = facets.listFacets(context, null);

        server.verify();
        assertThat(result.categories()).isEmpty();
    }

    @Test
    void facetsCapCategoriesAndFacetsPerCategory() {
        String facetJson =
            IntStream.range(0, 30).mapToObj(i -> "{\"name\":\"f" + i + "\",\"count\":" + i + "}").collect(Collectors.joining(","));
        String categoriesJson = IntStream.range(0, 27).mapToObj(i -> "{\"name\":\"c" + i + "\",\"facets\":[" + facetJson + "]}")
            .collect(Collectors.joining(","));
        server.expect(requestTo(GATEWAY + "/dictionary/facets"))
            .andRespond(withSuccess("[" + categoriesJson + "]", MediaType.APPLICATION_JSON));

        FacetResult result = facets.listFacets(context, "");

        assertThat(result.categories()).hasSize(FacetTool.MAX_CATEGORIES);
        assertThat(result.categoriesOmitted()).isEqualTo(2);
        assertThat(result.categories().get(0).facets()).hasSize(FacetTool.MAX_FACETS_PER_CATEGORY);
        assertThat(result.categories().get(0).facetsOmitted()).isEqualTo(5);
    }

    @Test
    void detailPostsTheRawConceptPathToTheDatasetUrl() {
        String path = "\\phs1\\pht1\\phv1\\age\\";
        server.expect(requestTo(GATEWAY + "/dictionary/concepts/detail/phs1")).andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", BEARER)).andExpect(header("X-PIC-SURE-MCP-TOKEN", "mcp-token"))
            .andExpect(content().string(path)).andRespond(
                withSuccess(
                    "{\"type\":\"Continuous\",\"conceptPath\":\"p\",\"name\":\"age\",\"display\":\"Age\",\"dataset\":\"phs1\","
                        + "\"min\":1.0,\"max\":2.0,\"children\":[{\"conceptPath\":\"c\"}],\"meta\":{\"unit\":\"years\",\"long\":\""
                        + "y".repeat(400) + "\"}}",
                    MediaType.APPLICATION_JSON
                )
            );

        ConceptSummary result = detail.getConcept(context, "phs1", path);

        server.verify();
        assertThat(result.name()).isEqualTo("age");
        assertThat(result.type()).isEqualTo("continuous");
        assertThat(result.meta()).containsEntry("unit", "years");
        assertThat(result.meta().get("long")).hasSize(ConceptSummary.MAX_META_VALUE_LENGTH);
    }

    @Test
    void detailEncodesADatasetThatHoldsASlash() {
        server.expect(requestTo(GATEWAY + "/dictionary/concepts/detail/a%2Fb"))
            .andRespond(withSuccess("{\"type\":\"Categorical\",\"conceptPath\":\"p\"}", MediaType.APPLICATION_JSON));

        detail.getConcept(context, "a/b", "\\p\\");

        server.verify();
    }

    @Test
    void detailClientEncodesATraversalDatasetInsideTheDetailPath() {
        server.expect(requestTo(GATEWAY + "/dictionary/concepts/detail/..%2F..%2Fhpds%2Fauth")).andExpect(request -> {
            assertThat(request.getURI().getRawPath()).startsWith("/dictionary/concepts/detail/").doesNotContain("/hpds/");
            assertThat(request.getURI().normalize().getRawPath()).startsWith("/dictionary/concepts/detail/");
        }).andRespond(withSuccess("{\"type\":\"Categorical\",\"conceptPath\":\"p\"}", MediaType.APPLICATION_JSON));

        client.conceptDetail("../../hpds/auth", "\\p\\", new CallerHeaders(BEARER, null, "req-1", null));

        server.verify();
    }

    @Test
    void detailRejectsBadArgumentsWithoutCallingTheGateway() {
        assertThatThrownBy(() -> detail.getConcept(context, null, "\\p\\")).isInstanceOf(ToolFailure.class);
        assertThatThrownBy(() -> detail.getConcept(context, "phs1", null)).isInstanceOf(ToolFailure.class);
        assertThatThrownBy(() -> detail.getConcept(context, "..", "\\p\\")).isInstanceOf(ToolFailure.class);
        assertThatThrownBy(() -> detail.getConcept(context, "phs1", "x".repeat(2001))).isInstanceOf(ToolFailure.class);
        server.verify();
    }

    @Test
    void detailNotFoundPointsTheModelToSearch() {
        server.expect(requestTo(GATEWAY + "/dictionary/concepts/detail/phs1")).andRespond(withStatus(HttpStatus.NOT_FOUND).body(LEAK));

        assertThatThrownBy(() -> detail.getConcept(context, "phs1", "\\p\\")).isInstanceOf(ToolFailure.class)
            .hasMessage("Concept path not found. Call search_concepts to find valid paths.").hasNoCause();
    }

    @ParameterizedTest
    @CsvSource(
        {"401,The caller is not authorized for the dictionary.", "403,The caller is not authorized for the dictionary.",
            "500,The dictionary is unavailable. Try again shortly.", "503,The dictionary is unavailable. Try again shortly.",
            "400,The dictionary rejected the request."}
    )
    void everyToolMapsDownstreamStatusesToShortMessagesWithoutTheBody(int status, String expected) {
        HttpStatus httpStatus = HttpStatus.valueOf(status);
        server.expect(requestTo(GATEWAY + "/dictionary/concepts?page_number=0&page_size=10"))
            .andRespond(withStatus(httpStatus).body(LEAK).contentType(MediaType.TEXT_PLAIN));
        server.expect(requestTo(GATEWAY + "/dictionary/facets"))
            .andRespond(withStatus(httpStatus).body(LEAK).contentType(MediaType.TEXT_PLAIN));
        server.expect(requestTo(GATEWAY + "/dictionary/concepts/detail/phs1"))
            .andRespond(withStatus(httpStatus).body(LEAK).contentType(MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> search.searchConcepts(context, "x", null, null, null)).isInstanceOf(ToolFailure.class).hasMessage(expected)
            .hasNoCause().satisfies(e -> assertThat(e.getMessage()).doesNotContain(LEAK));
        assertThatThrownBy(() -> facets.listFacets(context, "x")).isInstanceOf(ToolFailure.class).hasMessage(expected).hasNoCause();
        assertThatThrownBy(() -> detail.getConcept(context, "phs1", "\\p\\")).isInstanceOf(ToolFailure.class).hasMessage(expected)
            .hasNoCause();
    }

    @Test
    void searchAndFacetsNotFoundIsNeutralAndDoesNotPointBackAtSearch() {
        server.expect(requestTo(GATEWAY + "/dictionary/concepts?page_number=0&page_size=10"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND).body(LEAK));
        server.expect(requestTo(GATEWAY + "/dictionary/facets")).andRespond(withStatus(HttpStatus.NOT_FOUND).body(LEAK));

        assertThatThrownBy(() -> search.searchConcepts(context, "x", null, null, null)).isInstanceOf(ToolFailure.class)
            .hasMessage("The dictionary endpoint was not found.").hasNoCause();
        assertThatThrownBy(() -> facets.listFacets(context, "x")).isInstanceOf(ToolFailure.class)
            .hasMessage("The dictionary endpoint was not found.").hasNoCause();
    }

    @Test
    void detailWithAnEmptyBodyIsConceptNotFound() {
        server.expect(requestTo(GATEWAY + "/dictionary/concepts/detail/phs1")).andRespond(withSuccess());

        assertThatThrownBy(() -> detail.getConcept(context, "phs1", "\\p\\")).isInstanceOf(ToolFailure.class)
            .hasMessage("Concept path not found. Call search_concepts to find valid paths.").hasNoCause();
    }

    @Test
    void connectionFailureBecomesUnavailableWithoutTheHostOrCause() {
        server.expect(requestTo(GATEWAY + "/dictionary/facets")).andRespond(withException(new IOException("refused gateway.test:8080")));

        assertThatThrownBy(() -> facets.listFacets(context, "x")).isInstanceOf(ToolFailure.class)
            .hasMessage("The dictionary is unavailable. Try again shortly.").hasNoCause();
    }
}

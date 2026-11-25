package edu.harvard.hms.dbmi.avillach.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.config.GatewayRequestInterceptor;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.AuthorizedQueryClient;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryClient;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.OpenQueryClient;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Covers {@code authorized_study_counts} against a mock gateway: study consents lead and skip the dictionary entirely, and without them
 * each dataset's candidate concepts come from the dictionary once and are remembered, and the counts are taken afresh on every call.
 */
class StudyCountsToolTest {

    private static final String GATEWAY = "http://gateway.test:8080";
    private static final String OPEN = GATEWAY + "/hpds/open/query/sync";
    private static final String AUTHORIZED = GATEWAY + "/hpds/auth/query/sync";
    private static final String FACETS = GATEWAY + "/dictionary/facets";
    private static final String CANDIDATES = GATEWAY + "/dictionary/concepts?page_number=0&page_size=25&term_breakdown=true";
    private static final String FALLBACK = GATEWAY + "/dictionary/concepts?page_number=0&page_size=25";
    private static final McpTransportContext CONTEXT =
        McpTransportContext.create(Map.of(CallerHeaders.KEY, new CallerHeaders("Bearer caller-token", null, "req-1", null)));

    private MockRestServiceServer server;
    private MutableClock clock;
    private StudyCountsTool tool;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder =
            RestClient.builder().baseUrl(GATEWAY).requestInterceptor(new GatewayRequestInterceptor(GATEWAY, "mcp-token"));
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        RestClient gateway = builder.build();
        clock = new MutableClock();
        tool = new StudyCountsTool(
            new AuthorizedQueryClient(gateway, gateway), new OpenQueryClient(gateway), new DictionaryClient(gateway), clock
        );
    }

    private void authorizedTotal(String body) {
        server.expect(manyTimes(), requestTo(AUTHORIZED)).andExpect(jsonPath("$.query.expectedResultType").value("COUNT"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void authorizedCells(String body) {
        server.expect(manyTimes(), requestTo(AUTHORIZED)).andExpect(jsonPath("$.query.expectedResultType").value("CROSS_COUNT"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void facets(String... datasets) {
        String list = String.join(",", java.util.Arrays.stream(datasets).map(d -> "{\"name\":\"" + d + "\",\"count\":1}").toList());
        server.expect(manyTimes(), requestTo(FACETS)).andRespond(
            withSuccess(
                "[{\"name\":\"dataset_id\",\"facets\":[" + list + "]},{\"name\":\"data_type\",\"facets\":[{\"name\":\"x\"}]}]",
                MediaType.APPLICATION_JSON
            )
        );
    }

    private void sample(String dataset, String... paths) {
        String content =
            String.join(",", java.util.Arrays.stream(paths).map(p -> "{\"conceptPath\":\"" + p.replace("\\", "\\\\") + "\"}").toList());
        server.expect(once(), requestTo(CANDIDATES)).andExpect(jsonPath("$.facets[0].name").value(dataset))
            .andRespond(withSuccess("{\"content\":[" + content + "]}", MediaType.APPLICATION_JSON));
    }

    private void sampleTwice(String dataset, String path) {
        server.expect(times(2), requestTo(CANDIDATES)).andExpect(jsonPath("$.facets[0].name").value(dataset)).andRespond(
            withSuccess("{\"content\":[{\"conceptPath\":\"" + path.replace("\\", "\\\\") + "\"}]}", MediaType.APPLICATION_JSON)
        );
    }

    @Test
    void studyConsentsLeadAndTheDictionaryIsNeverAsked() {
        server.expect(requestTo(OPEN)).andRespond(
            withSuccess(
                "{\"\\\\_studies_consents\\\\\":\"300 ±3\",\"\\\\_studies_consents\\\\phs1\\\\\":\"200 ±3\",\"\\\\_studies_consents\\\\phs2\\\\\":\"100 ±3\"}",
                MediaType.APPLICATION_JSON
            )
        );
        server.expect(never(), requestTo(FACETS));
        server.expect(never(), requestTo(CANDIDATES));
        server.expect(never(), requestTo(FALLBACK));
        authorizedTotal("290");
        authorizedCells("{\"\\\\_studies_consents\\\\phs1\\\\\":180,\"\\\\_studies_consents\\\\phs2\\\\\":110}");

        StudyCountsResult result = tool.handle(CONTEXT, null);

        assertThat(result.basis()).isEqualTo(StudyCountsResult.STUDY_CONSENTS);
        assertThat(result.studies()).containsExactly(
            new StudyCountsResult.Study("\\_studies_consents\\phs1\\", 180, null),
            new StudyCountsResult.Study("\\_studies_consents\\phs2\\", 110, null)
        );
        assertThat(result.totalParticipants()).isEqualTo(290);
        assertThat(result.note()).isNull();
        server.verify();
    }

    @Test
    void moreStudyConsentsThanTheCellCapAreAllCounted() {
        int studies = CrossCountResult.MAX_CELLS + 5;
        StringBuilder open = new StringBuilder("{\"\\\\_studies_consents\\\\\":\"300 ±3\"");
        for (int i = 0; i < studies; i++) {
            open.append(",\"\\\\_studies_consents\\\\phs").append(i).append("\\\\\":\"200 ±3\"");
        }
        server.expect(requestTo(OPEN)).andRespond(withSuccess(open.append("}").toString(), MediaType.APPLICATION_JSON));
        authorizedTotal("290");
        // One study in each batch of the select limit has a count; the rest are left out of the body.
        authorizedCells("{\"\\\\_studies_consents\\\\phs0\\\\\":5,\"\\\\_studies_consents\\\\phs" + (studies - 1) + "\\\\\":7}");

        StudyCountsResult result = tool.handle(CONTEXT, null);

        assertThat(result.basis()).isEqualTo(StudyCountsResult.STUDY_CONSENTS);
        assertThat(result.studies()).hasSize(studies);
        assertThat(result.studies().get(0).count()).isEqualTo(5);
        assertThat(result.studies().get(studies - 1).count()).isEqualTo(7);
    }

    @Test
    void aDatasetCategoryWithNoFacetsListsNoDatasets() {
        server.expect(requestTo(OPEN)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        authorizedTotal("30");
        server.expect(manyTimes(), requestTo(FACETS)).andRespond(withSuccess("[{\"name\":\"dataset_id\"}]", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> tool.handle(CONTEXT, null)).isInstanceOf(ToolFailure.class).hasMessageContaining("lists no datasets");
    }

    @Test
    void withoutConsentsEachDatasetIsCountedByItsMostPopulatedCandidate() {
        server.expect(requestTo(OPEN)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        authorizedTotal("30");
        facets("A", "B");
        sample("A", "\\A\\rare\\", "\\A\\sex\\");
        sample("B", "\\B\\sex\\", "\\B\\gone\\");
        authorizedCells("{\"\\\\A\\\\rare\\\\\":4,\"\\\\A\\\\sex\\\\\":20,\"\\\\B\\\\sex\\\\\":10,\"\\\\B\\\\gone\\\\\":0}");

        StudyCountsResult result = tool.handle(CONTEXT, null);

        assertThat(result.basis()).isEqualTo(StudyCountsResult.DICTIONARY_SAMPLE);
        assertThat(result.studies())
            .containsExactly(new StudyCountsResult.Study("A", 20, null), new StudyCountsResult.Study("B", 10, null));
        assertThat(result.totalParticipants()).isEqualTo(30);
        assertThat(result.matchesTotal()).isTrue();
        assertThat(result.note()).contains("lower bound");
        server.verify();
    }

    @Test
    void aSecondCallReusesTheRememberedCandidatesUntilTheyExpire() {
        server.expect(manyTimes(), requestTo(OPEN)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        authorizedTotal("20");
        facets("A");
        sampleTwice("A", "\\A\\sex\\");
        authorizedCells("{\"\\\\A\\\\sex\\\\\":20}");

        tool.handle(CONTEXT, null);
        StudyCountsResult again = tool.handle(CONTEXT, null);

        assertThat(again.studies()).containsExactly(new StudyCountsResult.Study("A", 20, null));

        clock.advance(StudyCountsTool.CACHE_TTL.plusSeconds(1));
        tool.handle(CONTEXT, null);
        // The second and only other sampling is the one after expiry; a third would have no expectation left.
        server.verify();
    }

    @Test
    void aDatasetTheDemographicTermsFindNothingInFallsBackToItsFirstConcepts() {
        server.expect(requestTo(OPEN)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        authorizedTotal("7");
        facets("A");
        server.expect(once(), requestTo(CANDIDATES)).andRespond(withSuccess("{\"content\":[]}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(FALLBACK)).andExpect(jsonPath("$.facets[0].name").value("A"))
            .andRespond(withSuccess("{\"content\":[{\"conceptPath\":\"\\\\A\\\\first\\\\\"}]}", MediaType.APPLICATION_JSON));
        authorizedCells("{\"\\\\A\\\\first\\\\\":7}");

        StudyCountsResult result = tool.handle(CONTEXT, null);

        assertThat(result.studies()).containsExactly(new StudyCountsResult.Study("A", 7, null));
        server.verify();
    }

    @Test
    void aDatasetWithNothingTheUserCanSeeIsReportedAsNoDataNotAsAGuess() {
        server.expect(requestTo(OPEN)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        authorizedTotal("10");
        facets("A", "B");
        sample("A", "\\A\\sex\\");
        sample("B", "\\B\\sex\\");
        authorizedCells("{\"\\\\A\\\\sex\\\\\":10,\"\\\\B\\\\sex\\\\\":0}");

        StudyCountsResult result = tool.handle(CONTEXT, null);

        assertThat(result.studies()).containsExactly(new StudyCountsResult.Study("A", 10, null), new StudyCountsResult.Study("B", 0, true));
    }

    @Test
    void aCallerWithNoTokenHearsSoBeforeAnyRequest() {
        McpTransportContext noToken = McpTransportContext.create(Map.of(CallerHeaders.KEY, new CallerHeaders(null, null, "req-1", null)));

        assertThatThrownBy(() -> tool.handle(noToken, null)).isInstanceOf(ToolFailure.class).hasMessageContaining("Sign in");
        server.verify();
    }

    /** A clock the test moves by hand, to cross the cache's expiry. */
    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-11-26T12:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

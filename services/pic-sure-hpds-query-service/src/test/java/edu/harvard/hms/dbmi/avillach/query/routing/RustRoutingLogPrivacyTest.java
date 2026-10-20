package edu.harvard.hms.dbmi.avillach.query.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Shadow comparison logs carry no patient data: after a match, a count mismatch, a cross count mismatch, a Rust refusal and a search
 * mismatch, no captured log line holds either side's body, a count from it, or a digest of it, while the router's lines still carry the
 * type, stored query id, statuses, latencies, result and normalization.
 */
@TestPropertySource(properties = "hpds.rs.mode=shadow")
class RustRoutingLogPrivacyTest extends RustRoutingTestSupport {

    private static final String JAVA_COUNT = "48213";
    private static final String RUST_COUNT = "48217";
    private static final String JAVA_CROSS = "{\"\\\\sex\\\\\":731905,\"\\\\age\\\\\":66042}";
    private static final String RUST_CROSS = "{\"\\\\sex\\\\\":731906,\"\\\\age\\\\\":66042}";
    private static final String RUST_REFUSAL = "unknown concept \\\\secret-concept-name\\\\ in authorization filter";
    private static final String JAVA_SEARCH =
        "{\"searchQuery\":\"age\",\"results\":{\"phenotypes\":{\"\\\\private\\\\path\\\\\":{\"patientCount\":5531}}}}";
    private static final String RUST_SEARCH =
        "{\"searchQuery\":\"age\",\"results\":{\"phenotypes\":{\"\\\\private\\\\path\\\\\":{\"patientCount\":5532}}}}";

    private void sync(String query, String javaBody, int rustStatus, String rustBody) throws Exception {
        javaHpds.resetAll();
        rustHpds.resetAll();
        javaHpds.stubFor(
            post(urlEqualTo("/PIC-SURE/v3/query/sync"))
                .willReturn(aResponse().withStatus(200).withHeader("queryMetadata", "q-1").withBody(javaBody))
        );
        rustHpds.stubFor(post(urlEqualTo("/PIC-SURE/v3/query/sync")).willReturn(aResponse().withStatus(rustStatus).withBody(rustBody)));
        mockMvc.perform(caller("/hpds/auth/query/sync", query)).andExpect(status().isOk());
        assertThat(router.awaitShadowIdle(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void noLogLineHoldsABodyACountOrADigest() throws Exception {
        List<String> lines;
        List<String> routerLines;
        try (LogCapture capture = new LogCapture()) {
            sync(COUNT_QUERY, JAVA_COUNT, 200, JAVA_COUNT);
            sync(COUNT_QUERY, JAVA_COUNT, 200, RUST_COUNT);
            sync(CROSS_COUNT_QUERY, JAVA_CROSS, 200, RUST_CROSS);
            sync(COUNT_QUERY, JAVA_COUNT, 400, RUST_REFUSAL);
            javaHpds.stubFor(post(urlEqualTo("/PIC-SURE/v3/search")).willReturn(okJson(JAVA_SEARCH)));
            rustHpds.stubFor(post(urlEqualTo("/PIC-SURE/v3/search")).willReturn(okJson(RUST_SEARCH)));
            mockMvc.perform(caller("/hpds/auth/search", "{\"query\":\"age\"}")).andExpect(status().isOk());
            assertThat(router.awaitShadowIdle(Duration.ofSeconds(5))).isTrue();
            lines = capture.lines();
            routerLines = capture.routerLines();
        }

        assertThat(routerLines).hasSize(5);
        assertThat(routerLines).allSatisfy(
            line -> assertThat(line).contains("hpds_rs_shadow type=").contains("java_status=").contains("rust_status=").contains("java_ms=")
                .contains("rust_ms=").contains("result=").contains("normalization=")
        );
        assertThat(routerLines).anySatisfy(line -> assertThat(line).contains("query_id=" + STORED_ID).contains("result=match"));
        assertThat(routerLines).filteredOn(line -> line.contains("result=mismatch")).hasSize(4);

        for (String body : List.of(JAVA_COUNT, RUST_COUNT, JAVA_CROSS, RUST_CROSS, RUST_REFUSAL, JAVA_SEARCH, RUST_SEARCH)) {
            for (String form : LogCapture.forbiddenForms(body)) {
                assertThat(lines).as("log lines must not hold %s", form).noneMatch(line -> line.contains(form));
            }
        }
        for (String count : List.of("731905", "731906", "66042", "5531", "5532", "secret-concept-name")) {
            assertThat(lines).as("log lines must not hold %s", count).noneMatch(line -> line.contains(count));
        }
    }
}

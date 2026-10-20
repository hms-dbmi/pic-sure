package edu.harvard.hms.dbmi.avillach.query.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.TestPropertySource;

import com.github.tomakehurst.wiremock.http.Fault;

import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;

/** Serve mode end to end: Rust answers eligible calls, and every Rust failure falls back to Java's answer for that call. */
@TestPropertySource(properties = "hpds.rs.mode=serve")
class RustRoutingServeTest extends RustRoutingTestSupport {

    private void stubJavaSync(String body) {
        javaHpds.stubFor(
            post(urlEqualTo("/PIC-SURE/v3/query/sync"))
                .willReturn(aResponse().withStatus(200).withHeader("queryMetadata", "java-id").withBody(body))
        );
    }

    @Test
    void rustAnswersAndJavaIsNotCalled() throws Exception {
        stubJavaSync("1234");
        rustHpds.stubFor(
            post(urlEqualTo("/PIC-SURE/v3/query/sync"))
                .willReturn(aResponse().withStatus(200).withHeader("queryMetadata", "rust-id").withBody("1234"))
        );

        mockMvc.perform(caller("/hpds/auth/query/sync", COUNT_QUERY)).andExpect(status().isOk()).andExpect(content().string("1234"))
            .andExpect(header().string("queryMetadata", "rust-id"))
            .andExpect(header().string(HpdsRouter.BACKEND_HEADER, HpdsRouter.BACKEND_RUST));

        assertThat(received(javaHpds)).isEmpty();
        assertThat(received(rustHpds)).singleElement().satisfies(r -> {
            assertThat(r.getBodyAsString()).contains("\\\\_consents\\\\").contains(CONSENT_VALUE);
            assertThat(r.getHeader("Authorization")).isEqualTo("Bearer " + RUST_TOKEN);
        });
        verify(operationsClient).save(argThat((SaveQueryRequest r) -> "rust-id".equals(r.resourceResultId())));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 500, 503})
    void aRustErrorStatusFallsBackToJavasAnswer(int status) throws Exception {
        stubJavaSync("1234");
        rustHpds.stubFor(post(urlEqualTo("/PIC-SURE/v3/query/sync")).willReturn(aResponse().withStatus(status).withBody("refused")));

        mockMvc.perform(caller("/hpds/auth/query/sync", COUNT_QUERY)).andExpect(status().isOk()).andExpect(content().string("1234"))
            .andExpect(header().string(HpdsRouter.BACKEND_HEADER, HpdsRouter.BACKEND_JAVA_FALLBACK));

        assertThat(received(rustHpds).get(0).getBodyAsString()).isEqualTo(received(javaHpds).get(0).getBodyAsString());
        verify(operationsClient).save(argThat((SaveQueryRequest r) -> "java-id".equals(r.resourceResultId())));
    }

    @Test
    void aFallbackLogsNeitherBody() throws Exception {
        String javaBody = "{\"\\\\sex\\\\\":918273}";
        String rustBody = "unknown concept \\\\private-concept\\\\ refused";
        stubJavaSync(javaBody);
        rustHpds.stubFor(post(urlEqualTo("/PIC-SURE/v3/query/sync")).willReturn(aResponse().withStatus(400).withBody(rustBody)));

        List<String> lines;
        List<String> routerLines;
        try (LogCapture capture = new LogCapture()) {
            mockMvc.perform(caller("/hpds/auth/query/sync", CROSS_COUNT_QUERY)).andExpect(status().isOk());
            lines = capture.lines();
            routerLines = capture.routerLines();
        }

        assertThat(routerLines).singleElement()
            .satisfies(line -> assertThat(line).contains("hpds_rs_fallback type=CROSS_COUNT reason=status-4xx rust_status=400 rust_ms="));
        for (String body : List.of(javaBody, rustBody)) {
            for (String form : LogCapture.forbiddenForms(body)) {
                assertThat(lines).as("log lines must not hold %s", form).noneMatch(line -> line.contains(form));
            }
        }
        assertThat(lines).noneMatch(line -> line.contains("918273") || line.contains("private-concept"));
    }

    @Test
    void aDroppedConnectionFallsBackToJavasAnswer() throws Exception {
        stubJavaSync("{\"\\\\sex\\\\\":10}");
        rustHpds.stubFor(post(urlEqualTo("/PIC-SURE/v3/query/sync")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        mockMvc.perform(caller("/hpds/auth/query/sync", CROSS_COUNT_QUERY)).andExpect(status().isOk())
            .andExpect(content().string("{\"\\\\sex\\\\\":10}"))
            .andExpect(header().string(HpdsRouter.BACKEND_HEADER, HpdsRouter.BACKEND_JAVA_FALLBACK));
    }

    @Test
    void aSlowRustFallsBackToJavasAnswer() throws Exception {
        stubJavaSync("1234");
        rustHpds.stubFor(
            post(urlEqualTo("/PIC-SURE/v3/query/sync")).willReturn(aResponse().withStatus(200).withBody("9").withFixedDelay(4000))
        );

        mockMvc.perform(caller("/hpds/auth/query/sync", COUNT_QUERY)).andExpect(status().isOk()).andExpect(content().string("1234"))
            .andExpect(header().string(HpdsRouter.BACKEND_HEADER, HpdsRouter.BACKEND_JAVA_FALLBACK));
    }

    @Test
    void searchAndValuesAreServedByRust() throws Exception {
        rustHpds.stubFor(
            post(urlEqualTo("/PIC-SURE/v3/search")).willReturn(okJson("{\"searchQuery\":\"age\",\"results\":{\"from\":\"rust\"}}"))
        );
        rustHpds.stubFor(
            get(urlPathEqualTo("/PIC-SURE/v3/search/values/")).willReturn(okJson("{\"results\":[\"APOE\"],\"page\":1,\"total\":1}"))
        );

        mockMvc.perform(caller("/hpds/auth/search", "{\"query\":\"age\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.results.from").value("rust"))
            .andExpect(header().string(HpdsRouter.BACKEND_HEADER, HpdsRouter.BACKEND_RUST));
        mockMvc.perform(callerGet("/hpds/auth/search/values?genomicConceptPath=Gene_with_variant&query=AP")).andExpect(status().isOk())
            .andExpect(jsonPath("$.results[0]").value("APOE"))
            .andExpect(header().string(HpdsRouter.BACKEND_HEADER, HpdsRouter.BACKEND_RUST));

        assertThat(received(javaHpds)).isEmpty();
    }

    @Test
    void anUnreadableSearchAnswerFallsBackToJava() throws Exception {
        javaHpds.stubFor(
            post(urlEqualTo("/PIC-SURE/v3/search")).willReturn(okJson("{\"searchQuery\":\"age\",\"results\":{\"from\":\"java\"}}"))
        );
        rustHpds.stubFor(post(urlEqualTo("/PIC-SURE/v3/search")).willReturn(aResponse().withStatus(200).withBody("not json")));

        mockMvc.perform(caller("/hpds/auth/search", "{\"query\":\"age\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.results.from").value("java"))
            .andExpect(header().string(HpdsRouter.BACKEND_HEADER, HpdsRouter.BACKEND_JAVA_FALLBACK));
    }

    @Test
    void theOpenBackendStaysOnJava() throws Exception {
        javaHpds.stubFor(post(urlEqualTo("/OPEN/v3/search")).willReturn(okJson("{\"searchQuery\":\"age\",\"results\":{}}")));

        mockMvc.perform(caller("/hpds/open/search", "{\"query\":\"age\"}")).andExpect(status().isOk())
            .andExpect(header().doesNotExist(HpdsRouter.BACKEND_HEADER));

        assertThat(received(rustHpds)).isEmpty();
    }

    @Test
    void asyncCreatesStayOnJava() throws Exception {
        javaHpds
            .stubFor(post(urlEqualTo("/PIC-SURE/v3/query")).willReturn(okJson("{\"resourceResultId\":\"rr-1\",\"status\":\"PENDING\"}")));

        mockMvc.perform(caller("/hpds/auth/query", COUNT_QUERY)).andExpect(status().isOk());

        assertThat(received(rustHpds)).isEmpty();
    }
}

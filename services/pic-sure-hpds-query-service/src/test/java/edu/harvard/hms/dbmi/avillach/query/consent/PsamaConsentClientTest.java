package edu.harvard.hms.dbmi.avillach.query.consent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

class PsamaConsentClientTest {

    private static WireMockServer psama;

    @BeforeAll
    static void start() {
        psama = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        psama.start();
    }

    @AfterAll
    static void stop() {
        psama.stop();
    }

    @Test
    void fetchesCallerConsentsWithCallerTokenAndServiceMarker() {
        psama.stubFor(
            get(urlEqualTo("/auth/user/me/consents")).willReturn(
                okJson("{\"userId\":\"8f2fd22b-f093-4fce-83ae-965a924b89c3\",\"consents\":{\"\\\\_consents\\\\\":[\"phs001.c1\"]}}")
            )
        );
        PsamaConsentClient client = new PsamaConsentClient(RestClient.builder().baseUrl("http://localhost:" + psama.port()).build());

        Map<String, Set<String>> result = client.fetch("Bearer caller-token");

        assertThat(result).containsEntry("\\_consents\\", Set.of("phs001.c1"));
        psama.verify(
            getRequestedFor(urlEqualTo("/auth/user/me/consents")).withHeader("Authorization", equalTo("Bearer caller-token"))
                .withHeader("X-Client-Type", equalTo("service"))
        );
    }

    @Test
    void nonSuccessfulResponseFailsClosedAsBadGateway() {
        psama.stubFor(get(urlEqualTo("/auth/user/me/consents")).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client().fetch("Bearer caller-token")).isInstanceOfSatisfying(PicsureException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(error.getErrorType()).isEqualTo("consent_lookup_failed");
        });
    }

    @Test
    void malformedResponseFailsClosedAsBadGateway() {
        psama.stubFor(get(urlEqualTo("/auth/user/me/consents")).willReturn(okJson("not-json")));

        assertThatThrownBy(() -> client().fetch("Bearer caller-token"))
            .isInstanceOfSatisfying(PicsureException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void emptyConsentSetFailsClosedAsBadGateway() {
        psama.stubFor(get(urlEqualTo("/auth/user/me/consents")).willReturn(okJson("{\"consents\":{}}")));

        assertThatThrownBy(() -> client().fetch("Bearer caller-token"))
            .isInstanceOfSatisfying(PicsureException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void missingBaseUrlFailsClosedAsBadGateway() {
        PsamaConsentClient client = new PsamaConsentClient(RestClient.builder().build());

        assertThatThrownBy(() -> client.fetch("Bearer caller-token"))
            .isInstanceOfSatisfying(PicsureException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    private PsamaConsentClient client() {
        return new PsamaConsentClient(RestClient.builder().baseUrl("http://localhost:" + psama.port()).build());
    }
}

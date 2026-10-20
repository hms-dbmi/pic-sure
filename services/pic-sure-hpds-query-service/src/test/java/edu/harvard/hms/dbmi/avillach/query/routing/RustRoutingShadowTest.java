package edu.harvard.hms.dbmi.avillach.query.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;

/** Shadow mode end to end: Java answers, Rust receives the identical scoped request afterwards, and the two answers are compared. */
@TestPropertySource(properties = "hpds.rs.mode=shadow")
class RustRoutingShadowTest extends RustRoutingTestSupport {

    @Test
    void bothBackendsReceiveTheIdenticalScopedRequestAndJavasAnswerIsReturned() throws Exception {
        javaHpds.stubFor(
            post(urlEqualTo("/PIC-SURE/v3/query/sync"))
                .willReturn(aResponse().withStatus(200).withHeader("queryMetadata", "java-id").withBody("1234"))
        );
        rustHpds.stubFor(
            post(urlEqualTo("/PIC-SURE/v3/query/sync"))
                .willReturn(aResponse().withStatus(200).withHeader("queryMetadata", "rust-id").withBody("999"))
        );

        mockMvc.perform(caller("/hpds/auth/query/sync", COUNT_QUERY)).andExpect(status().isOk()).andExpect(content().string("1234"))
            .andExpect(header().string("queryMetadata", "java-id"))
            .andExpect(header().string(HpdsRouter.BACKEND_HEADER, HpdsRouter.BACKEND_JAVA_SHADOW));
        assertThat(router.awaitShadowIdle(Duration.ofSeconds(5))).isTrue();

        List<LoggedRequest> java = received(javaHpds);
        List<LoggedRequest> rust = received(rustHpds);
        assertThat(java).hasSize(1);
        assertThat(rust).hasSize(1);
        assertThat(rust.get(0).getBodyAsString()).isEqualTo(java.get(0).getBodyAsString()).contains("\\\\_consents\\\\")
            .contains(CONSENT_VALUE);
        assertThat(java.get(0).getHeader("Authorization")).isEqualTo("Bearer " + JAVA_TOKEN);
        assertThat(rust.get(0).getHeader("Authorization")).isEqualTo("Bearer " + RUST_TOKEN);
        verify(operationsClient).save(argThat((SaveQueryRequest r) -> "java-id".equals(r.resourceResultId())));
    }

    @Test
    void aMirroredJavaErrorCarriesTheShadowLabel() throws Exception {
        javaHpds.stubFor(post(urlEqualTo("/PIC-SURE/v3/query/sync")).willReturn(aResponse().withStatus(400).withBody("refused")));
        rustHpds.stubFor(post(urlEqualTo("/PIC-SURE/v3/query/sync")).willReturn(aResponse().withStatus(400).withBody("refused")));

        mockMvc.perform(caller("/hpds/auth/query/sync", COUNT_QUERY)).andExpect(status().isBadRequest())
            .andExpect(header().string(HpdsRouter.BACKEND_HEADER, HpdsRouter.BACKEND_JAVA_SHADOW));
        assertThat(router.awaitShadowIdle(Duration.ofSeconds(5))).isTrue();

        assertThat(received(rustHpds)).hasSize(1);
    }

    @Test
    void searchIsMirroredWithTheSameBody() throws Exception {
        String answer = "{\"searchQuery\":\"age\",\"results\":{\"phenotypes\":{}}}";
        javaHpds.stubFor(post(urlEqualTo("/PIC-SURE/v3/search")).willReturn(okJson(answer)));
        rustHpds.stubFor(post(urlEqualTo("/PIC-SURE/v3/search")).willReturn(okJson(answer)));

        mockMvc.perform(caller("/hpds/auth/search", "{\"query\":\"age\"}")).andExpect(status().isOk())
            .andExpect(header().string(HpdsRouter.BACKEND_HEADER, HpdsRouter.BACKEND_JAVA_SHADOW));
        assertThat(router.awaitShadowIdle(Duration.ofSeconds(5))).isTrue();

        assertThat(received(rustHpds)).singleElement()
            .satisfies(r -> assertThat(r.getBodyAsString()).isEqualTo(received(javaHpds).get(0).getBodyAsString()));
    }

    @Test
    void aTypeOutsideTheListIsNotMirrored() throws Exception {
        javaHpds.stubFor(post(urlEqualTo("/PIC-SURE/v3/query/sync")).willReturn(aResponse().withStatus(200).withBody("{\"count\":1}")));

        mockMvc.perform(caller("/hpds/auth/query/sync", UNLISTED_TYPE_QUERY)).andExpect(status().isOk())
            .andExpect(header().doesNotExist(HpdsRouter.BACKEND_HEADER));
        assertThat(router.awaitShadowIdle(Duration.ofSeconds(5))).isTrue();

        assertThat(received(rustHpds)).isEmpty();
    }

    @Test
    void theOpenBackendIsNeverMirrored() throws Exception {
        javaHpds.stubFor(post(urlEqualTo("/OPEN/v3/search")).willReturn(okJson("{\"searchQuery\":\"age\",\"results\":{}}")));

        mockMvc.perform(caller("/hpds/open/search", "{\"query\":\"age\"}")).andExpect(status().isOk());
        assertThat(router.awaitShadowIdle(Duration.ofSeconds(5))).isTrue();

        assertThat(received(rustHpds)).isEmpty();
    }

    @Test
    void asyncCreatesStayOnJava() throws Exception {
        javaHpds
            .stubFor(post(urlEqualTo("/PIC-SURE/v3/query")).willReturn(okJson("{\"resourceResultId\":\"rr-1\",\"status\":\"PENDING\"}")));

        mockMvc.perform(caller("/hpds/auth/query", COUNT_QUERY)).andExpect(status().isOk());
        assertThat(router.awaitShadowIdle(Duration.ofSeconds(5))).isTrue();

        assertThat(received(rustHpds)).isEmpty();
    }
}

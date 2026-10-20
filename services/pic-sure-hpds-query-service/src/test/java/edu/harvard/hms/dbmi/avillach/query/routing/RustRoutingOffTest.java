package edu.harvard.hms.dbmi.avillach.query.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import edu.harvard.hms.dbmi.avillach.query.config.HpdsProperties.RsMode;

/** The kill switch: with {@code hpds.rs.mode=off} and every other Rust setting in place, Rust HPDS receives nothing. */
@TestPropertySource(properties = "hpds.rs.mode=off")
class RustRoutingOffTest extends RustRoutingTestSupport {

    @Test
    void offSendsNothingToRust() throws Exception {
        javaHpds.stubFor(post(urlEqualTo("/PIC-SURE/v3/query/sync")).willReturn(aResponse().withStatus(200).withBody("1234")));
        javaHpds.stubFor(post(urlEqualTo("/PIC-SURE/v3/search")).willReturn(okJson("{\"searchQuery\":\"age\",\"results\":{}}")));

        mockMvc.perform(caller("/hpds/auth/query/sync", COUNT_QUERY)).andExpect(status().isOk()).andExpect(content().string("1234"))
            .andExpect(header().doesNotExist(HpdsRouter.BACKEND_HEADER));
        mockMvc.perform(caller("/hpds/auth/search", "{\"query\":\"age\"}")).andExpect(status().isOk())
            .andExpect(header().doesNotExist(HpdsRouter.BACKEND_HEADER));
        assertThat(router.awaitShadowIdle(Duration.ofSeconds(5))).isTrue();

        assertThat(router.mode()).isEqualTo(RsMode.OFF);
        assertThat(received(javaHpds)).hasSize(2);
        assertThat(received(rustHpds)).isEmpty();
    }
}

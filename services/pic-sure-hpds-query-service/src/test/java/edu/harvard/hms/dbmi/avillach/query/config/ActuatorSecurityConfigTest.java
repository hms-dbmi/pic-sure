package edu.harvard.hms.dbmi.avillach.query.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Proves the {@code ActuatorSecurityConfig} chain (order 0) and the pre-existing {@link WebSecurityConfig} main chain (order 10) coexist on
 * hpds-query-service: the actuator chain owns {@code /actuator/**} (shallow health open, detail/prometheus gated by
 * {@code X-Application-Token}) while the main chain's {@code authenticated()} rule on {@code /hpds/**} is untouched.
 *
 * <p>The test config points {@code hpds.auth-url}/{@code hpds.open-url} at an unreachable placeholder (see
 * {@code src/test/resources/application.yml}), so the deep HPDS-reachability indicator legitimately reports DOWN here (503) -- what this
 * test pins is that health is never 401 (open to the load balancer regardless of UP/DOWN) and that component detail visibility tracks the
 * token, not the HTTP status.
 *
 * <p>{@code src/test/resources/application.yml} is a self-contained test config that Spring Boot's classpath config loading resolves
 * INSTEAD OF (not merged with) {@code src/main/resources/application.yml} -- test-classes precedes classes on the Surefire classpath, and
 * {@code classpath:/application.yml} resolves to a single resource. The production {@code management.*}/{@code picsure.actuator.*} block
 * therefore never reaches this test context, so this class supplies the equivalent settings itself via {@code @TestPropertySource} rather
 * than relying on the (shadowed) main resource file.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureObservability // prometheus export is disabled by default in @SpringBootTest contexts
@TestPropertySource(
    properties = {"picsure.actuator.require-token=true", "picsure.actuator.token=query-secret",
        "management.endpoints.web.exposure.include=health,info,prometheus,metrics",
        "management.endpoint.health.show-details=when_authorized", "management.endpoint.health.probes.enabled=true",
        "management.endpoint.health.group.liveness.include=livenessState"}
)
class ActuatorSecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void healthIsOpenAndShallowWithoutToken() throws Exception {
        MvcResult res = mockMvc.perform(get("/actuator/health")).andReturn();
        assertThat(res.getResponse().getStatus()).isNotEqualTo(401); // open to the load balancer regardless of UP/DOWN
        JsonNode body = json.readTree(res.getResponse().getContentAsString());
        assertThat(body.has("status")).isTrue();
        assertThat(body.has("components")).isFalse();
    }

    @Test
    void livenessIsOpenWithoutToken() throws Exception {
        MvcResult res = mockMvc.perform(get("/actuator/health/liveness")).andReturn();
        assertThat(res.getResponse().getStatus()).isNotEqualTo(401);
    }

    @Test
    void healthRevealsComponentDetailWithValidToken() throws Exception {
        MvcResult res = mockMvc.perform(get("/actuator/health").header("X-Application-Token", "query-secret")).andReturn();
        JsonNode body = json.readTree(res.getResponse().getContentAsString());
        assertThat(body.has("components")).isTrue(); // DETAIL revealed regardless of UP/DOWN
    }

    @Test
    void prometheusRejectedWithoutToken() throws Exception {
        MvcResult res = mockMvc.perform(get("/actuator/prometheus")).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void prometheusOkWithToken() throws Exception {
        MvcResult res = mockMvc.perform(get("/actuator/prometheus").header("X-Application-Token", "query-secret")).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
    }

    /** Coexistence: the main chain's authenticated() gate on /hpds/** must be unaffected by the actuator chain. */
    @Test
    void hpdsEndpointStillRequiresAuthenticationAlongsideActuatorChain() throws Exception {
        mockMvc.perform(post("/hpds/auth/query").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"q\"}"))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }
}

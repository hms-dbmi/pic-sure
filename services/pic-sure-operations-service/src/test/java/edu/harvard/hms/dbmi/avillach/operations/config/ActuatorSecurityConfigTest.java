package edu.harvard.hms.dbmi.avillach.operations.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * operations-service: the actuator chain owns {@code /actuator/**} (shallow health open, detail/prometheus gated by
 * {@code X-Application-Token}) while the main chain's {@code SUPER_ADMIN} rule on {@code /configuration/admin/**} is untouched.
 *
 * <p>{@code src/test/resources/application.yml} is a self-contained test config (H2 datasource, etc.) that Spring Boot's classpath config
 * loading resolves INSTEAD OF (not merged with) {@code src/main/resources/application.yml} -- test-classes precedes classes on the Surefire
 * classpath, and {@code classpath:/application.yml} resolves to a single resource. The production {@code management.*}/
 * {@code picsure.actuator.*} block therefore never reaches this test context, so this class supplies the equivalent settings itself via
 * {@code @TestPropertySource} rather than relying on the (shadowed) main resource file.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability // prometheus export is disabled by default in @SpringBootTest contexts
@TestPropertySource(
    properties = {"picsure.actuator.require-token=true", "picsure.actuator.token=ops-secret",
        "management.endpoints.web.exposure.include=health,info,prometheus,metrics",
        "management.endpoint.health.show-details=when_authorized", "management.endpoint.health.probes.enabled=true",
        "management.endpoint.health.group.liveness.include=livenessState",
        "management.endpoint.health.group.readiness.include=readinessState,db"}
)
class ActuatorSecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void healthIsOpenAndShallowWithoutToken() throws Exception {
        MvcResult res = mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andReturn();
        JsonNode body = json.readTree(res.getResponse().getContentAsString());
        assertThat(body.has("status")).isTrue();
        assertThat(body.has("components")).isFalse();
    }

    @Test
    void livenessIsOpenWithoutToken() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }

    @Test
    void healthRevealsComponentDetailWithValidToken() throws Exception {
        MvcResult res =
            mockMvc.perform(get("/actuator/health").header("X-Application-Token", "ops-secret")).andExpect(status().isOk()).andReturn();
        JsonNode body = json.readTree(res.getResponse().getContentAsString());
        assertThat(body.has("components")).isTrue();
    }

    @Test
    void prometheusRejectedWithoutToken() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    }

    @Test
    void prometheusOkWithToken() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").header("X-Application-Token", "ops-secret")).andExpect(status().isOk());
    }

    /** Coexistence: the main chain's SUPER_ADMIN gate on /configuration/admin/** must be unaffected by the actuator chain. */
    @Test
    void adminEndpointStillRequiresSuperAdminAlongsideActuatorChain() throws Exception {
        mockMvc.perform(post("/configuration/admin").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }
}

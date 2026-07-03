package edu.harvard.hms.dbmi.avillach.commons.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ActuatorSecurityHelperTest.TestApp.class)
@AutoConfigureMockMvc
@AutoConfigureObservability // Spring Boot disables metrics export in tests by default; the /actuator/prometheus
                            // endpoint this test exercises needs it enabled to be registered.
@TestPropertySource(
    properties = {"picsure.actuator.require-token=true", "picsure.actuator.token=secret-123",
        "management.endpoints.web.exposure.include=health,info,prometheus,metrics",
        "management.endpoint.health.show-details=when_authorized"}
)
class ActuatorSecurityHelperTest {

    @SpringBootApplication
    @EnableConfigurationProperties(ActuatorTokenProperties.class)
    static class TestApp {
        @Bean
        @Order(0)
        SecurityFilterChain actuator(HttpSecurity http, ActuatorTokenProperties props) throws Exception {
            return ActuatorSecurityHelper.actuatorChain(http, props);
        }

        @Bean
        @Order(10)
        SecurityFilterChain main(HttpSecurity http) throws Exception {
            return http.csrf(c -> c.disable()).authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
        }
    }

    @Autowired
    MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void healthIsOpenAndShallowWithoutToken() throws Exception {
        MvcResult res = mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andReturn();
        JsonNode body = json.readTree(res.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("UP");
        assertThat(body.has("components")).isFalse(); // no detail leaks to anonymous callers
    }

    @Test
    void healthRevealsComponentDetailWithValidToken() throws Exception {
        MvcResult res =
            mvc.perform(get("/actuator/health").header("X-Application-Token", "secret-123")).andExpect(status().isOk()).andReturn();
        JsonNode body = json.readTree(res.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("UP");
        assertThat(body.has("components")).isTrue(); // DETAIL revealed
        assertThat(body.get("components").has("diskSpace")).isTrue(); // an always-present component
    }

    @Test
    void prometheusRequiresToken() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    }

    @Test
    void prometheusOkWithToken() throws Exception {
        mvc.perform(get("/actuator/prometheus").header("X-Application-Token", "secret-123")).andExpect(status().isOk());
    }
}

package edu.harvard.hms.dbmi.avillach.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Checkmarx reports a missing {@code Strict-Transport-Security} header against {@code GatewayErrors#write}, the helper
 * {@code BufferingFilter} uses for its 413 short-circuit. That helper sets only a status, a content type, and a body; response headers come
 * from Spring Security's {@code HeadersConfigurer}, which neither gateway filter chain disables.
 *
 * <p>These two tests separate the framework behavior from the deployment assertion. Over a secure request the header IS written, even on a
 * response produced by a servlet filter that runs before Spring MVC. Over a non-secure request it is NOT -- Spring Security's HSTS writer
 * is conditional on {@code request.isSecure()}. That second case is exactly why TLS-forwarding behavior at the production edge still has to
 * be verified there: if the ingress terminates TLS and the gateway does not see the request as secure, no HSTS header is emitted no matter
 * what this code does.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = {"picsure.gateway.security.max-body-bytes=32"})
class StrictTransportSecurityHeaderTest {

    private static final String OVERSIZED_BODY = "x".repeat(4096);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aFilterGeneratedErrorOverHttpsCarriesStrictTransportSecurity() throws Exception {
        MvcResult result =
            mockMvc.perform(post("/picsure/query").secure(true).contentType("application/json").content(OVERSIZED_BODY)).andReturn();

        assertEquals(413, result.getResponse().getStatus(), "the oversized body must reach GatewayErrors.write");
        assertTrue(result.getResponse().getContentAsString().contains("REQUEST_BODY_TOO_LARGE"));
        String hsts = result.getResponse().getHeader("Strict-Transport-Security");
        assertNotNull(hsts, "Spring Security's default headers must still apply to a filter-generated response");
        assertTrue(hsts.contains("max-age="), "HSTS must carry a max-age: " + hsts);
    }

    @Test
    void theSameErrorOverPlainHttpCarriesNoStrictTransportSecurity() throws Exception {
        MvcResult result =
            mockMvc.perform(post("/picsure/query").secure(false).contentType("application/json").content(OVERSIZED_BODY)).andReturn();

        assertEquals(413, result.getResponse().getStatus());
        assertNull(
            result.getResponse().getHeader("Strict-Transport-Security"),
            "HSTS is conditional on request.isSecure(), so the deployed edge must make forwarded HTTPS visible to the gateway"
        );
    }
}

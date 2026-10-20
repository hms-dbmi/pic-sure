package edu.harvard.hms.dbmi.avillach.query.routing;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.query.consent.PsamaConsentClient;
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;

/**
 * Boots the whole query service against two stub HPDS servers, one standing in for Java HPDS and one for Rust HPDS, with consent scoping on
 * and the caller's consents served by a mocked PSAMA client. Subclasses pick {@code hpds.rs.mode}; every other routing setting is shared:
 * Rust at {@code /PIC-SURE} on its own server with token {@value #RUST_TOKEN}, a shadow sample of 1, and the types COUNT, CROSS_COUNT,
 * SEARCH and SEARCH_VALUES.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
abstract class RustRoutingTestSupport {

    static final String RUST_TOKEN = "rs-token";
    static final String JAVA_TOKEN = "java-token";
    static final String CONSENT_VALUE = "phs000001.c1";
    static final UUID STORED_ID = UUID.fromString("00000000-0000-0000-0000-00000000abcd");
    static final String COUNT_QUERY = "{\"query\":{\"expectedResultType\":\"COUNT\"}}";
    static final String CROSS_COUNT_QUERY = "{\"query\":{\"select\":[\"\\\\sex\\\\\"],\"expectedResultType\":\"CROSS_COUNT\"}}";
    static final String UNLISTED_TYPE_QUERY = "{\"query\":{\"expectedResultType\":\"VARIANT_COUNT_FOR_QUERY\"}}";

    /**
     * The two stub servers live for the whole test JVM. Spring caches one context per routing mode and reuses it across test classes, and a
     * cached context keeps the ports it was built with, so servers restarted per class would leave it pointing at closed ports.
     */
    static final WireMockServer javaHpds = started();
    static final WireMockServer rustHpds = started();

    private static WireMockServer started() {
        WireMockServer server = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        return server;
    }

    @DynamicPropertySource
    static void routingProps(DynamicPropertyRegistry registry) {
        registry.add("hpds.auth-url", () -> "http://localhost:" + javaHpds.port() + "/PIC-SURE");
        registry.add("hpds.auth-token", () -> JAVA_TOKEN);
        registry.add("hpds.open-url", () -> "http://localhost:" + javaHpds.port() + "/OPEN");
        registry.add("hpds.rs.url", () -> "http://localhost:" + rustHpds.port() + "/PIC-SURE");
        registry.add("hpds.rs.token", () -> RUST_TOKEN);
        registry.add("hpds.rs.shadow-sample", () -> "1.0");
        registry.add("hpds.rs.types", () -> "COUNT,CROSS_COUNT,SEARCH,SEARCH_VALUES");
        registry.add("hpds.rs.timeout-ms", () -> "2000");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    HpdsRouter router;

    @MockitoBean
    OperationsClient operationsClient;

    @MockitoBean
    PsamaConsentClient psama;

    @BeforeEach
    void resetStubs() {
        javaHpds.resetAll();
        rustHpds.resetAll();
        when(psama.fetch(any())).thenReturn(Map.of("\\_consents\\", Set.of(CONSENT_VALUE)));
        when(operationsClient.save(any())).thenReturn(STORED_ID);
    }

    /** A POST of {@code body} to {@code path} as an authenticated caller. */
    static MockHttpServletRequestBuilder caller(String path, String body) {
        return post(path).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice").header("Authorization", "Bearer caller-token")
            .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** A GET of {@code path} as an authenticated caller. */
    static MockHttpServletRequestBuilder callerGet(String path) {
        return get(path).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice").header("Authorization", "Bearer caller-token");
    }

    /** Every request the server received, oldest first. */
    static List<LoggedRequest> received(WireMockServer server) {
        return server.getAllServeEvents().reversed().stream().map(event -> event.getRequest()).toList();
    }
}

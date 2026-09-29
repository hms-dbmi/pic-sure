package edu.harvard.hms.dbmi.avillach.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sun.net.httpserver.HttpServer;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Covers the gateway {@link RestClient} as the application context builds it: bound to {@code picsure.mcp.gateway-url}, carrying the
 * caller's headers and the MCP credential, logging without secrets, and refusing to start without its required properties.
 */
class GatewayClientConfigTest {

    private static final String BEARER = "Bearer secret-bearer-value";
    private static final String API_KEY = "secret-api-key-value";
    private static final String MCP_TOKEN = "secret-mcp-token-value";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(HttpMessageConvertersAutoConfiguration.class, RestClientAutoConfiguration.class))
        .withUserConfiguration(GatewayClientConfig.class).withPropertyValues(
            "picsure.mcp.gateway-url=http://gateway.test:8080", "picsure.mcp.service-token=" + MCP_TOKEN,
            "picsure.mcp.adapter.base-url=https://picsure.test"
        );

    @Test
    void outboundCallReplaysCallerHeadersAndTheClientAddsTheMcpToken() {
        runner.run(context -> {
            RestClient.Builder builder = context.getBean(GatewayClientConfig.GATEWAY_REST_CLIENT, RestClient.class).mutate();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            CallerHeaders caller = new CallerHeaders(BEARER, API_KEY, "req-123", "203.0.113.7");

            server.expect(requestTo("http://gateway.test:8080/dictionary/concepts")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", BEARER)).andExpect(header("X-PICSURE-API-Key", API_KEY))
                .andExpect(header("X-Request-Id", "req-123")).andExpect(header("X-Forwarded-For", "203.0.113.7"))
                .andExpect(header("X-PIC-SURE-MCP-TOKEN", MCP_TOKEN)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

            builder.build().post().uri("/dictionary/concepts").headers(h -> caller.applyTo(h)).body("{}").retrieve().toBodilessEntity();

            server.verify();
        });
    }

    @Test
    void tokenlessCallerStillGetsTheMcpTokenFromTheClient() {
        runner.run(context -> {
            RestClient.Builder builder = context.getBean(GatewayClientConfig.GATEWAY_REST_CLIENT, RestClient.class).mutate();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            CallerHeaders caller = new CallerHeaders(null, null, null, null);

            server.expect(requestTo("http://gateway.test:8080/dictionary/facets")).andExpect(headerDoesNotExist("Authorization"))
                .andExpect(headerDoesNotExist("X-PICSURE-API-Key")).andExpect(header("X-PIC-SURE-MCP-TOKEN", MCP_TOKEN))
                .andRespond(withSuccess());

            builder.build().post().uri("/dictionary/facets").headers(h -> caller.applyTo(h)).retrieve().toBodilessEntity();

            server.verify();
        });
    }

    @Test
    void logsAndStringFormsCarryNoCredential() {
        Logger callerLog = (Logger) LoggerFactory.getLogger(CallerHeaders.class);
        Logger clientLog = (Logger) LoggerFactory.getLogger(GatewayRequestInterceptor.class);
        Level callerLevel = callerLog.getLevel();
        Level clientLevel = clientLog.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        callerLog.addAppender(appender);
        clientLog.addAppender(appender);
        callerLog.setLevel(Level.DEBUG);
        clientLog.setLevel(Level.DEBUG);
        try {
            runner.run(context -> {
                RestClient.Builder builder = context.getBean(GatewayClientConfig.GATEWAY_REST_CLIENT, RestClient.class).mutate();
                MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
                CallerHeaders caller = new CallerHeaders(BEARER, API_KEY, "req-123", "203.0.113.7");
                server.expect(requestTo("http://gateway.test:8080/hpds/open/query/sync")).andRespond(withSuccess());

                try (CallerHeaders.MdcScope ignored = caller.bindRequestId()) {
                    builder.build().post().uri("/hpds/open/query/sync").headers(h -> caller.applyTo(h)).retrieve().toBodilessEntity();
                }

                server.verify();
                assertThat(caller.toString()).doesNotContain(secrets());
                assertThat(context.getBean(McpProperties.class).toString()).doesNotContain(secrets());
            });
        } finally {
            callerLog.detachAppender(appender);
            clientLog.detachAppender(appender);
            callerLog.setLevel(callerLevel);
            clientLog.setLevel(clientLevel);
        }

        List<String> lines = appender.list.stream().map(GatewayClientConfigTest::render).toList();
        assertThat(lines).hasSizeGreaterThanOrEqualTo(2).allSatisfy(line -> assertThat(line).doesNotContain(secrets()));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("Gateway call POST /hpds/open/query/sync returned 200"));
        assertThat(appender.list).anySatisfy(event -> assertThat(event.getMDCPropertyMap()).containsEntry("requestId", "req-123"));
    }

    @Test
    void startupFailsWithBlankGatewayUrl() {
        runner.withPropertyValues("picsure.mcp.gateway-url=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(BindValidationException.class)
                .hasMessageContaining("gatewayUrl");
        });
    }

    @Test
    void startupFailsWithBlankServiceToken() {
        runner.withPropertyValues("picsure.mcp.service-token=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(BindValidationException.class)
                .hasMessageContaining("serviceToken");
        });
    }

    @Test
    void startupFailsWithBlankAdapterBaseUrl() {
        runner.withPropertyValues("picsure.mcp.adapter.base-url=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(BindValidationException.class).hasMessageContaining("baseUrl");
        });
    }

    @Test
    void startupFailsWithWhitespaceServiceToken() {
        runner.withInitializer(
            context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("whitespace-token", Map.of("picsure.mcp.service-token", " \t ")))
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(BindValidationException.class)
                .hasMessageContaining("serviceToken");
        });
    }

    @Test
    void refusesARequestToAnyHostOtherThanTheGateway() {
        runner.run(context -> {
            RestClient.Builder builder = context.getBean(GatewayClientConfig.GATEWAY_REST_CLIENT, RestClient.class).mutate();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            RestClient client = builder.build();

            assertThatThrownBy(() -> client.get().uri("http://hpds.internal:8080/query").retrieve().toBodilessEntity())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("hpds.internal");
            assertThatThrownBy(() -> client.get().uri("http://gateway.test:9999/query").retrieve().toBodilessEntity())
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> client.get().uri("https://gateway.test:8080/query").retrieve().toBodilessEntity())
                .isInstanceOf(IllegalStateException.class);

            server.verify();
        });
    }

    @Test
    void doesNotFollowRedirects() throws Exception {
        AtomicInteger landed = new AtomicInteger();
        HttpServer gateway = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int port = gateway.getAddress().getPort();
        gateway.createContext("/start", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + port + "/landed");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        gateway.createContext("/landed", exchange -> {
            landed.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        gateway.start();
        try {
            runner.withPropertyValues("picsure.mcp.gateway-url=http://127.0.0.1:" + port).run(context -> {
                RestClient client = context.getBean(GatewayClientConfig.GATEWAY_REST_CLIENT, RestClient.class);

                ResponseEntity<Void> response = client.get().uri("/start").retrieve().toBodilessEntity();

                assertThat(response.getStatusCode().value()).isEqualTo(302);
                assertThat(landed).hasValue(0);
            });
        } finally {
            gateway.stop(0);
        }
    }

    @Test
    void adapterFlagsDefaultToOff() {
        runner.run(context -> {
            McpProperties.Adapter adapter = context.getBean(McpProperties.class).adapter();
            assertThat(adapter.baseUrl()).isEqualTo("https://picsure.test");
            assertThat(adapter.includeConsents()).isFalse();
            assertThat(adapter.supportsGenomic()).isFalse();
            assertThat(adapter.pythonMinVersion()).isEqualTo("3.0.0");
            assertThat(adapter.rTag()).isEqualTo("v3.0.0");
        });
    }

    private static String[] secrets() {
        return new String[] {"secret-bearer-value", API_KEY, MCP_TOKEN};
    }

    private static String render(ILoggingEvent event) {
        return event.getFormattedMessage() + " " + event.getMDCPropertyMap();
    }
}

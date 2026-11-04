package edu.harvard.hms.dbmi.avillach.query.config;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;

import com.github.tomakehurst.wiremock.WireMockServer;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.hms.dbmi.avillach.query.aggregate.AggregateBackendClient;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector;

/**
 * Boots the HPDS and aggregate wiring against the main {@code application.yml}, which the test classpath shadows, and checks that one
 * {@code hpds.api-path} setting drives every HPDS call the service makes.
 */
class HpdsApiPathConfigTest {

    WireMockServer hpds;

    @BeforeEach
    void start() {
        hpds = new WireMockServer(0);
        hpds.start();
    }

    @AfterEach
    void stop() {
        hpds.stop();
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withInitializer(context -> {
            try {
                new YamlPropertySourceLoader().load("main-application-yml", new FileSystemResource("src/main/resources/application.yml"))
                    .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }).withUserConfiguration(
            HpdsClientConfig.class, AggregateConfig.class, AggregateHttpClientConfig.class, HpdsBackendSelector.class,
            AggregateBackendClient.class
        );
    }

    @Test
    void anApiPathOverrideMovesTheAggregateOpenCallsWithTheQueryCalls() {
        String base = "http://localhost:" + hpds.port();
        hpds.stubFor(post(urlEqualTo("/v4/query/sync")).willReturn(okJson("{}")));

        runner().withPropertyValues("hpds.api-path=/v4", "hpds.open-url=" + base, "aggregate.hpds-open-url=" + base).run(context -> {
            assertThat(context.getBean(HpdsBackendSelector.class).select("open").baseUrl()).isEqualTo(base + "/v4");
            context.getBean(AggregateBackendClient.class).querySync(new GeneralQueryRequest().setQuery("{}"));
            hpds.verify(postRequestedFor(urlEqualTo("/v4/query/sync")));
        });
    }

    @Test
    void anUnsetApiPathDefaultsBothClientsToV3() {
        String base = "http://localhost:" + hpds.port();
        hpds.stubFor(post(urlEqualTo("/v3/query/sync")).willReturn(okJson("{}")));

        runner().withPropertyValues("hpds.open-url=" + base, "aggregate.hpds-open-url=" + base).run(context -> {
            assertThat(context.getBean(HpdsBackendSelector.class).select("open").baseUrl()).isEqualTo(base + "/v3");
            context.getBean(AggregateBackendClient.class).querySync(new GeneralQueryRequest().setQuery("{}"));
            hpds.verify(postRequestedFor(urlEqualTo("/v3/query/sync")));
        });
    }
}

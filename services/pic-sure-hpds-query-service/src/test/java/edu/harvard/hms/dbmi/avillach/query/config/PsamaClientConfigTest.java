package edu.harvard.hms.dbmi.avillach.query.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class PsamaClientConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withUserConfiguration(PsamaClientConfig.class)
        .withPropertyValues("psama.base-url=http://psama:8090", "psama.connect-timeout-sec=2", "psama.read-timeout-sec=10");

    @Test
    void bindsPsamaUrlAndBoundedTimeoutsAndBuildsClient() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PsamaProperties.class);
            assertThat(context).hasBean("psamaConsentRestClient");
            assertThat(context.getBean("psamaConsentRestClient", RestClient.class)).isNotNull();

            PsamaProperties properties = context.getBean(PsamaProperties.class);
            assertThat(properties.getBaseUrl()).isEqualTo("http://psama:8090");
            assertThat(properties.getConnectTimeoutSec()).isEqualTo(2);
            assertThat(properties.getReadTimeoutSec()).isEqualTo(10);
        });
    }
}

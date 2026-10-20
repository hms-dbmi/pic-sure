package edu.harvard.hms.dbmi.avillach.query.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;

import edu.harvard.hms.dbmi.avillach.query.config.HpdsProperties.RsMode;
import edu.harvard.hms.dbmi.avillach.query.routing.HpdsRouter;
import edu.harvard.hms.dbmi.avillach.query.routing.RustHpdsClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Boots the HPDS client wiring and the router against the main {@code application.yml} and checks that each {@code HPDS_RS_*} environment
 * variable reaches {@link HpdsProperties.Rs}, and that a bad value stops startup.
 */
class RustRoutingSettingsBindingTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withInitializer(context -> {
            try {
                new YamlPropertySourceLoader().load("main-application-yml", new FileSystemResource("src/main/resources/application.yml"))
                    .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }).withUserConfiguration(HpdsClientConfig.class, RustHpdsClient.class, HpdsRouter.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);
    }

    @Test
    void unsetVariablesLeaveRoutingOffWithTheDefaults() {
        runner().run(context -> {
            HpdsProperties.Rs rs = context.getBean(HpdsProperties.class).getRs();
            assertThat(rs.getUrl()).isEmpty();
            assertThat(rs.getToken()).isEmpty();
            assertThat(rs.getMode()).isEqualTo(RsMode.OFF);
            assertThat(rs.getTypes()).containsExactly("COUNT", "CROSS_COUNT", "CATEGORICAL_CROSS_COUNT", "CONTINUOUS_CROSS_COUNT");
            assertThat(rs.getShadowSample()).isEqualTo(0.1);
            assertThat(rs.getShadowMaxInFlight()).isEqualTo(4);
            assertThat(rs.getTimeoutMs()).isEqualTo(30000);
            assertThat(context.getBean(HpdsRouter.class).mode()).isEqualTo(RsMode.OFF);
        });
    }

    @Test
    void everyVariableIsBound() {
        runner().withPropertyValues(
            "HPDS_RS_URL=http://hpds-rs:8080/PIC-SURE", "HPDS_RS_TOKEN=secret", "HPDS_RS_MODE=shadow", "HPDS_RS_TYPES=COUNT,SEARCH",
            "HPDS_RS_SHADOW_SAMPLE=0.25", "HPDS_RS_SHADOW_MAX_IN_FLIGHT=8", "HPDS_RS_TIMEOUT_MS=1500"
        ).run(context -> {
            HpdsProperties.Rs rs = context.getBean(HpdsProperties.class).getRs();
            assertThat(rs.getUrl()).isEqualTo("http://hpds-rs:8080/PIC-SURE");
            assertThat(rs.getToken()).isEqualTo("secret");
            assertThat(rs.getMode()).isEqualTo(RsMode.SHADOW);
            assertThat(rs.getTypes()).containsExactlyInAnyOrder("COUNT", "SEARCH");
            assertThat(rs.getShadowSample()).isEqualTo(0.25);
            assertThat(rs.getShadowMaxInFlight()).isEqualTo(8);
            assertThat(rs.getTimeoutMs()).isEqualTo(1500);
            HpdsRouter router = context.getBean(HpdsRouter.class);
            assertThat(router.mode()).isEqualTo(RsMode.SHADOW);
            assertThat(router.eligible("auth", HpdsRouter.SEARCH)).isTrue();
            assertThat(router.eligible("auth", "CROSS_COUNT")).isFalse();
        });
    }

    @Test
    void serveWithoutAUrlIsOff() {
        runner().withPropertyValues("HPDS_RS_MODE=serve")
            .run(context -> assertThat(context.getBean(HpdsRouter.class).mode()).isEqualTo(RsMode.OFF));
    }

    @Test
    void anUnknownModeStopsStartup() {
        runner().withPropertyValues("HPDS_RS_URL=http://hpds-rs:8080/PIC-SURE", "HPDS_RS_MODE=mirror")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void anUnknownTypeStopsStartup() {
        runner().withPropertyValues("HPDS_RS_TYPES=COUNT,COUNTS").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("COUNTS");
        });
    }
}

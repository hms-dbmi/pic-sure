package edu.harvard.hms.dbmi.avillach.query.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * Covers the {@code picsure.query.row-lock.wait-sec} binding, on the bare properties class and through the main {@code application.yml},
 * which the test classpath shadows and is therefore loaded here from its source path.
 */
class QueryRowLockPropertiesTest {

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withInitializer(context -> {
            try {
                new YamlPropertySourceLoader().load("main-application-yml", new FileSystemResource("src/main/resources/application.yml"))
                    .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }).withUserConfiguration(QueryRowLockConfig.class);
    }

    @Test
    void theWaitDefaultsToSixtySeconds() {
        assertThat(new QueryRowLockProperties().getWaitSec()).isEqualTo(60);
    }

    @Test
    void bindsTheWait() {
        var source = new MapConfigurationPropertySource(Map.of("picsure.query.row-lock.wait-sec", "5"));

        QueryRowLockProperties bound = new Binder(source).bind("picsure.query.row-lock", QueryRowLockProperties.class).get();

        assertThat(bound.getWaitSec()).isEqualTo(5);
    }

    @Test
    void theMainConfigurationWaitsSixtySeconds() {
        runner().run(context -> assertThat(context.getBean(QueryRowLockProperties.class).getWaitSec()).isEqualTo(60));
    }

    @Test
    void theEnvironmentCanSetTheWait() {
        runner().withInitializer(
            context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("variables", Map.of("picsure.query.row-lock.wait-sec", "15")))
        ).run(context -> assertThat(context.getBean(QueryRowLockProperties.class).getWaitSec()).isEqualTo(15));
    }
}

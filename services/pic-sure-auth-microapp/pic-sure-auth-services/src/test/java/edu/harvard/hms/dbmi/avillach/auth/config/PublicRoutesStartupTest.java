package edu.harvard.hms.dbmi.avillach.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Starts only {@link PublicRoutesConfiguration} against the packaged {@code application.properties} and checks that a bad entry or an
 * override stops the context instead of starting with a different public surface.
 */
class PublicRoutesStartupTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PublicRoutesConfiguration.class);

    @Test
    void packagedRoutesStartTheContext() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PublicRoutes.class).shipped()).isNotEmpty();
        });
    }

    @Test
    void malformedAdditionalRouteStopsTheContext() {
        runner.withPropertyValues("security.public-routes.additional[0].pattern=user/**").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start with '/'");
        });
    }

    @Test
    void overriddenShippedRouteStopsTheContext() {
        runner.withPropertyValues("security.public-routes.shipped[0].pattern=/user/**").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be overridden");
        });
    }
}

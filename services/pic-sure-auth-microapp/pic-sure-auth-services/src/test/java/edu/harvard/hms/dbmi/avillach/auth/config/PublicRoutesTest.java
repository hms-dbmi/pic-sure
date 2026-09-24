package edu.harvard.hms.dbmi.avillach.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.mock.env.MockEnvironment;

class PublicRoutesTest {

    private static final Resource PACKAGED = properties("""
        security.public-routes.shipped[0].pattern=/tos/latest
        security.public-routes.shipped[0].methods=GET
        security.public-routes.shipped[1].pattern=/authentication/**
        """);

    private static final PublicRoute TOS = new PublicRoute("/tos/latest", Set.of("GET"));

    private static final PublicRoute AUTHENTICATION = new PublicRoute("/authentication/**", null);

    @Test
    void shippedRoutesComeFromThePackagedFile() throws Exception {
        PublicRoutes routes = PublicRoutes.load(PACKAGED, new MockEnvironment());

        assertThat(routes.shipped()).containsExactly(TOS, AUTHENTICATION);
        assertThat(routes.additional()).isEmpty();
    }

    @Test
    void environmentThatRepeatsTheShippedListIsAccepted() throws Exception {
        MockEnvironment environment = new MockEnvironment().withProperty("security.public-routes.shipped[0].pattern", "/tos/latest")
            .withProperty("security.public-routes.shipped[0].methods", "GET")
            .withProperty("security.public-routes.shipped[1].pattern", "/authentication/**");

        assertThat(PublicRoutes.load(PACKAGED, environment).shipped()).containsExactly(TOS, AUTHENTICATION);
    }

    @Test
    void environmentThatReplacesAShippedRouteIsRefused() {
        MockEnvironment environment = new MockEnvironment().withProperty("security.public-routes.shipped[0].pattern", "/user/**");

        assertThatIllegalStateException().isThrownBy(() -> PublicRoutes.load(PACKAGED, environment))
            .withMessageContaining("cannot be overridden");
    }

    @Test
    void environmentAddsRoutesAfterTheShippedOnes() throws Exception {
        MockEnvironment environment = new MockEnvironment().withProperty("security.public-routes.additional[0].pattern", "/extra")
            .withProperty("security.public-routes.additional[0].methods", "post");

        PublicRoutes routes = PublicRoutes.load(PACKAGED, environment);

        PublicRoute extra = new PublicRoute("/extra", Set.of("POST"));
        assertThat(routes.additional()).containsExactly(extra);
        assertThat(routes.all()).containsExactly(TOS, AUTHENTICATION, extra);
    }

    @Test
    void environmentVariablesInTheDocumentedFormAddRoutes() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of(
                    "SECURITY_PUBLICROUTES_ADDITIONAL_0_PATTERN", "/my/public/path/**", "SECURITY_PUBLICROUTES_ADDITIONAL_0_METHODS",
                    "GET,POST", "SECURITY_PUBLICROUTES_ADDITIONAL_1_PATTERN", "/other"
                )
            )
        );

        assertThat(PublicRoutes.load(PACKAGED, environment).additional())
            .containsExactly(new PublicRoute("/my/public/path/**", Set.of("GET", "POST")), new PublicRoute("/other", null));
    }

    @Test
    void environmentVariableThatReplacesAShippedRouteIsRefused() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of("SECURITY_PUBLICROUTES_SHIPPED_0_PATTERN", "/user/**")
            )
        );

        assertThatIllegalStateException().isThrownBy(() -> PublicRoutes.load(PACKAGED, environment))
            .withMessageContaining("cannot be overridden");
    }

    @Test
    void malformedAdditionalRouteFailsTheBind() {
        MockEnvironment environment = new MockEnvironment().withProperty("security.public-routes.additional[0].pattern", "extra");

        assertThatExceptionOfType(BindException.class).isThrownBy(() -> PublicRoutes.load(PACKAGED, environment)).havingRootCause()
            .isInstanceOf(IllegalArgumentException.class).withMessageContaining("must start with '/'");
    }

    @Test
    void malformedShippedRouteFailsTheBind() {
        Resource packaged =
            properties("security.public-routes.shipped[0].pattern=/tos/latest\nsecurity.public-routes.shipped[0].methods=FETCH\n");

        assertThatExceptionOfType(BindException.class).isThrownBy(() -> PublicRoutes.load(packaged, new MockEnvironment()))
            .havingRootCause().isInstanceOf(IllegalArgumentException.class).withMessageContaining("unknown HTTP method");
    }

    @Test
    void packagedFileWithNoShippedRoutesIsRefused() {
        assertThatIllegalStateException().isThrownBy(() -> PublicRoutes.load(properties("server.port=8090\n"), new MockEnvironment()))
            .withMessageContaining("ships no security.public-routes.shipped entries");
    }

    private static Resource properties(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.ISO_8859_1), "test application.properties");
    }
}

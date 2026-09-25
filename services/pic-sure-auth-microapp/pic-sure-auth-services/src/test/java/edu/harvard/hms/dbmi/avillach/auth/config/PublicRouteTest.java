package edu.harvard.hms.dbmi.avillach.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PublicRouteTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void blankPatternIsRejected(String pattern) {
        assertThatIllegalArgumentException().isThrownBy(() -> new PublicRoute(pattern, null)).withMessageContaining("must not be blank");
    }

    @Test
    void patternWithoutLeadingSlashIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PublicRoute("tos/latest", null))
            .withMessageContaining("must start with '/'");
    }

    @Test
    void blankMethodIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PublicRoute("/tos/latest", methods("GET", " ")))
            .withMessageContaining("blank method");
    }

    @Test
    void nullMethodIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PublicRoute("/tos/latest", methods("GET", null)))
            .withMessageContaining("blank method");
    }

    @Test
    void unknownMethodIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PublicRoute("/tos/latest", methods("FETCH")))
            .withMessageContaining("unknown HTTP method FETCH");
    }

    @Test
    void methodsAreTrimmedAndUpperCased() {
        assertThat(new PublicRoute("/tos/latest", methods(" get", "Post ")).methods()).containsExactly("GET", "POST");
    }

    @Test
    void missingMethodsMeanEveryMethod() {
        assertThat(new PublicRoute("/tos/latest", null).methods()).isEmpty();
    }

    @Test
    void routesWithTheSamePatternAndMethodsAreEqual() {
        assertThat(new PublicRoute("/tos/latest", methods("get"))).isEqualTo(new PublicRoute("/tos/latest", Set.of("GET")));
    }

    private static Set<String> methods(String... methods) {
        return new LinkedHashSet<>(Arrays.asList(methods));
    }
}

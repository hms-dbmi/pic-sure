package edu.harvard.hms.dbmi.avillach.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import edu.harvard.hms.dbmi.avillach.gateway.auth.PublicRoute.MatchKind;

/**
 * Each {@link MatchKind} reproduces one of the rules the gateway policy used to hard-code, and a malformed entry is rejected by the
 * constructor so a bad {@code public-routes} block fails container startup rather than a request.
 */
class PublicRouteTest {

    @ParameterizedTest
    @CsvSource({"/system/status, true", "/system/status/, false", "/system/status/x, false", "/v3/system/status, false"})
    void exactMatchesOnlyTheLiteralPath(String requestPath, boolean expected) {
        PublicRoute route = new PublicRoute("/system/status", MatchKind.EXACT, null, null, null);

        assertThat(route.matches("GET", requestPath)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"/logging, true", "/logging/, true", "/logging/audit, true", "/loggingAdmin/x, false", "/x/logging, false"})
    void prefixMatchesOnASegmentBoundary(String requestPath, boolean expected) {
        PublicRoute route = new PublicRoute("/logging", MatchKind.PREFIX, null, null, null);

        assertThat(route.matches("POST", requestPath)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"/openapi.json, true", "/gateway/openapi.json, true", "/openapi.jsonx, false", "/openapi, false"})
    void suffixMatchesTheEndOfThePath(String requestPath, boolean expected) {
        PublicRoute route = new PublicRoute("/openapi.json", MatchKind.SUFFIX, null, null, null);

        assertThat(route.matches("GET", requestPath)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(
        {"/operations/configuration, true", "/operations/configuration/, true", "/operations/configuration/abc-123, true",
            "/operations/configuration/abc-123/, true", "/operations/configuration/admin, false", "/operations/configuration/admin/, false",
            "/operations/configuration/admin/x, false", "/operations/configuration/abc-123/x, false", "/operations/configurationx, false",
            "/operations, false"}
    )
    void singleSegmentChildMatchesTheBasePathAndOneUndeniedSegment(String requestPath, boolean expected) {
        PublicRoute route = new PublicRoute("/operations/configuration", MatchKind.SINGLE_SEGMENT_CHILD, null, Set.of("admin"), null);

        assertThat(route.matches("GET", requestPath)).isEqualTo(expected);
    }

    @Test
    void emptyMethodsMatchesAnyMethod() {
        PublicRoute route = new PublicRoute("/logging", MatchKind.PREFIX, Set.of(), null, null);

        assertThat(route.matches("GET", "/logging/audit")).isTrue();
        assertThat(route.matches("POST", "/logging/audit")).isTrue();
        assertThat(route.matches("DELETE", "/logging/audit")).isTrue();
    }

    @Test
    void explicitMethodsRestrictTheMatch() {
        PublicRoute route = new PublicRoute("/system/status", MatchKind.EXACT, Set.of("GET"), null, null);

        assertThat(route.matches("GET", "/system/status")).isTrue();
        assertThat(route.matches("POST", "/system/status")).isFalse();
    }

    @Test
    void methodsAreComparedCaseInsensitively() {
        PublicRoute route = new PublicRoute("/system/status", MatchKind.EXACT, Set.of("get"), null, null);

        assertThat(route.methods()).containsExactly("GET");
        assertThat(route.matches("GET", "/system/status")).isTrue();
    }

    @Test
    void blankAuditUsernameNormalisesToNull() {
        assertThat(new PublicRoute("/logging", MatchKind.PREFIX, null, null, "  ").auditUsername()).isNull();
        assertThat(new PublicRoute("/system/status", MatchKind.EXACT, null, null, "SYSTEM_MONITOR").auditUsername())
            .isEqualTo("SYSTEM_MONITOR");
    }

    @Test
    void rejectsABlankPath() {
        assertThatThrownBy(() -> new PublicRoute(" ", MatchKind.PREFIX, null, null, null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("path");
    }

    @Test
    void rejectsAPathWithoutALeadingSlash() {
        assertThatThrownBy(() -> new PublicRoute("logging", MatchKind.PREFIX, null, null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("logging");
    }

    @Test
    void rejectsAMissingMatchKind() {
        assertThatThrownBy(() -> new PublicRoute("/logging", null, null, null, null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("match");
    }

    @Test
    void rejectsABlankMethod() {
        assertThatThrownBy(() -> new PublicRoute("/logging", MatchKind.PREFIX, Set.of(" "), null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("method");
    }

    @ParameterizedTest
    @CsvSource({"EXACT", "PREFIX", "SUFFIX"})
    void rejectsADenyListOnAKindThatCannotUseIt(MatchKind kind) {
        assertThatThrownBy(() -> new PublicRoute("/x", kind, null, Set.of("admin"), null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("deny");
    }
}

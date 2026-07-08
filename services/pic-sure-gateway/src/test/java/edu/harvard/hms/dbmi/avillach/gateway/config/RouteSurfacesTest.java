package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Segment-safe route-surface classification. A prefix matches only on an exact equal or a following {@code /} boundary -- never a bare
 * {@code startsWith} -- so a sibling route whose path merely shares a text prefix ({@code /loggingAdmin} vs {@code /logging}) is NOT
 * misclassified as owned.
 */
class RouteSurfacesTest {

    private final RouteSurfaces surfaces = RouteSurfaces.withDefaults();

    @Test
    void ownsEachDefaultPrefixExactlyAndUnderASegmentBoundary() {
        for (String prefix : RouteSurfaceProperties.DEFAULT_OWNED_PREFIXES) {
            assertThat(surfaces.isOwned(prefix)).as("exact %s", prefix).isTrue();
            assertThat(surfaces.isOwned(prefix + "/")).as("%s/", prefix).isTrue();
            assertThat(surfaces.isOwned(prefix + "/deep/path")).as("%s/deep/path", prefix).isTrue();
        }
    }

    @Test
    void doesNotMatchSiblingPathSharingATextPrefix() {
        // /loggingAdmin must NOT match owned prefix /logging
        assertThat(surfaces.isOwned("/loggingAdmin")).isFalse();
        assertThat(surfaces.isOwned("/loggingAdmin/x")).isFalse();
        assertThat(surfaces.isOwned("/datasets")).isFalse(); // vs /dataset
        assertThat(surfaces.isOwned("/hpdsx")).isFalse(); // vs /hpds
    }

    @Test
    void unownedIsEverythingNotOwned() {
        assertThat(surfaces.isCatchAll("/picsure/query/sync")).isTrue();
        assertThat(surfaces.isCatchAll("/v3/search/abc")).isTrue();
        assertThat(surfaces.isCatchAll("/hpds/auth/v3/query/sync")).isFalse();
        assertThat(surfaces.isOwned(null)).isFalse();
        assertThat(surfaces.isCatchAll(null)).isTrue();
    }

    @Test
    void honorsAConfiguredOverrideList() {
        RouteSurfaces custom = new RouteSurfaces(List.of("/only"));
        assertThat(custom.isOwned("/only/x")).isTrue();
        assertThat(custom.isOwned("/hpds/x")).isFalse(); // default prefixes no longer owned under the override
    }
}

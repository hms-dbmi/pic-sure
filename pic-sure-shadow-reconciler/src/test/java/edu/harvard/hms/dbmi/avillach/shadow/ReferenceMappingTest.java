package edu.harvard.hms.dbmi.avillach.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ReferenceMappingTest {

    private ReferenceMapping load() {
        return ReferenceMapping.load(getClass().getResourceAsStream("/target-service-mapping.yml"));
    }

    @Test
    void canonicalizesByLongestMatchingPrefix() {
        ReferenceMapping m = load();
        assertEquals("/query/sync", m.canonical("/picsure/query/sync"));
        assertEquals("/query/sync", m.canonical("/v3/query/sync"));
    }

    @Test
    void exposesRouteMode() {
        ReferenceMapping m = load();
        assertEquals(RouteMode.DECISION_AFFECTING, m.mode("/v3/query/sync"));
        assertEquals(RouteMode.COSMETIC, m.mode("/picsure/query/sync"));
    }

    @Test
    void unmappedPathReturnsItselfAndCosmetic() {
        ReferenceMapping m = load();
        assertEquals("/unknown/x", m.canonical("/unknown/x"));
        assertEquals(RouteMode.COSMETIC, m.mode("/unknown/x"));
    }

    @Test
    void canonicalizesSearchPrefix() {
        ReferenceMapping m = load();
        assertEquals("/search/foo", m.canonical("/picsure/search/foo"));
        assertEquals(RouteMode.COSMETIC, m.mode("/picsure/search/foo"));
    }
}

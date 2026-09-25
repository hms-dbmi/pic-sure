package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentTypeRulesTest {

    private static final JavaClasses FIXTURES =
        new ClassFileImporter().importPackages("edu.harvard.hms.dbmi.avillach.conventions.contenttypefixtures");

    private static final List<String> VIOLATIONS = ContentTypeRules.getDoesNotNarrowConsumes("fixtures", FIXTURES);

    private static void assertFlagged(String handler) {
        assertTrue(VIOLATIONS.stream().anyMatch(v -> v.contains(handler + " ")), handler + " in " + VIOLATIONS);
    }

    private static void assertAccepted(String handler) {
        assertTrue(VIOLATIONS.stream().noneMatch(v -> v.contains(handler + " ")), handler + " in " + VIOLATIONS);
    }

    @Test
    void r14FlagsEveryGetHandlerThatNarrowsConsumes() {
        assertEquals(5, VIOLATIONS.size(), VIOLATIONS.toString());
        assertFlagged("ConsumesController#getJson");
        assertFlagged("ConsumesController#getMixed");
        assertFlagged("ConsumesController#requestGetJson");
        assertFlagged("ConsumesController#requestAnyJson");
        assertFlagged("ClassConsumesController#inherits");
        assertTrue(VIOLATIONS.get(0).startsWith("fixtures :: "), VIOLATIONS.get(0));
    }

    @Test
    void r14SaysWhetherTheConsumesIsSetOrInherited() {
        assertTrue(VIOLATIONS.stream().anyMatch(v -> v.contains("#inherits ") && v.contains("inherits consumes")), VIOLATIONS.toString());
        assertTrue(VIOLATIONS.stream().anyMatch(v -> v.contains("#getJson ") && v.contains("sets consumes")), VIOLATIONS.toString());
    }

    @Test
    void r14AcceptsTheWildcardNoConsumesAndNonGetHandlers() {
        assertAccepted("ConsumesController#getAny");
        assertAccepted("ConsumesController#getPlain");
        assertAccepted("ConsumesController#postJson");
        assertAccepted("ConsumesController#requestPostJson");
        assertAccepted("ClassConsumesController#overrides");
        assertAccepted("ClassConsumesController#post");
    }
}

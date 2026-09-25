package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceRulesTest {

    private static final String FIXTURES = "edu.harvard.hms.dbmi.avillach.conventions.persistencefixtures";
    private static final JavaClasses CLASSES = new ClassFileImporter().importPackages(FIXTURES);

    private static void assertMentions(List<String> violations, String fragment) {
        assertTrue(violations.stream().anyMatch(v -> v.contains(fragment)), fragment + " in " + violations);
    }

    @Test
    void r19FlagsEveryEnumFieldStoredByOrdinal() {
        List<String> violations = PersistenceRules.enumsStoredByName("fixtures", CLASSES, Set.of());

        assertEquals(6, violations.size(), violations.toString());
        assertMentions(violations, "fixtures :: BadEntity#bare stores enum Status by ordinal");
        assertMentions(violations, "BadEntity#defaulted stores enum Status by ordinal");
        assertMentions(violations, "BadEntity#ordinal stores enum Status by ordinal");
        assertMentions(violations, "BadEntity#unconverted stores enum Status by ordinal");
        assertMentions(violations, "BadSuperclass#inherited stores enum Status by ordinal");
        assertMentions(violations, "BadEmbeddable#embedded stores enum Status by ordinal");
    }

    @Test
    void r19AcceptsNamedConvertedAndUnpersistedFields() {
        List<String> violations = PersistenceRules.enumsStoredByName("fixtures", CLASSES, Set.of());

        assertTrue(violations.stream().noneMatch(v -> v.contains("GoodEntity")), violations.toString());
        assertTrue(violations.stream().noneMatch(v -> v.contains("NotPersisted")), violations.toString());
    }

    @Test
    void r19TreatsAReactorEnumFromAnotherModuleAsAnEnum() {
        List<String> violations = PersistenceRules.enumsStoredByName("fixtures", CLASSES, Set.of(FIXTURES + ".ForeignType"));

        assertEquals(7, violations.size(), violations.toString());
        assertMentions(violations, "ForeignEnumEntity#foreign stores enum ForeignType by ordinal");
    }

    @Test
    void enumTypesCollectsEnumsFromEveryModule() {
        Set<String> enums = PersistenceRules.enumTypes(Map.of("fixtures", CLASSES));

        assertEquals(Set.of(FIXTURES + ".Status"), enums);
    }
}

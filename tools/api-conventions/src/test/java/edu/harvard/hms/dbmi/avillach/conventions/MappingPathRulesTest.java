package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingPathRulesTest {

    private static final JavaClasses FIXTURES =
        new ClassFileImporter().importPackages("edu.harvard.hms.dbmi.avillach.conventions.mappingpathfixtures");

    private static void assertMentions(List<String> violations, String fragment) {
        assertTrue(violations.stream().anyMatch(v -> v.contains(fragment)), fragment + " in " + violations);
    }

    @Test
    void r16FlagsEveryTrailingSlashAtClassAndMethodLevel() {
        List<String> violations = MappingPathRules.noTrailingSlash("fixtures", FIXTURES);

        assertEquals(5, violations.size(), violations.toString());
        assertMentions(violations, "fixtures :: SlashedController @RequestMapping path '/slashed/'");
        assertMentions(violations, "SlashedController#list @GetMapping path '/list/'");
        assertMentions(violations, "SlashedController#create @PostMapping path '/also/'");
        assertMentions(violations, "SlashedController#request @RequestMapping path '/request/'");
    }

    @Test
    void r16FlagsAMethodRootPathUnderAClassPath() {
        List<String> violations = MappingPathRules.noTrailingSlash("fixtures", FIXTURES);

        assertMentions(violations, "RootUnderClassPathController#list @GetMapping path '/' under a class path serves '/dataset/named/'");
        assertTrue(violations.stream().noneMatch(v -> v.contains("RootUnderClassPathController#create")), violations.toString());
    }

    @Test
    void r16AllowsTheRootPathAndSlashLessPaths() {
        List<String> violations = MappingPathRules.noTrailingSlash("fixtures", FIXTURES);

        assertTrue(violations.stream().noneMatch(v -> v.contains(":: RootController")), violations.toString());
        assertTrue(violations.stream().noneMatch(v -> v.contains("UnmappedClassController")), violations.toString());
        assertTrue(violations.stream().noneMatch(v -> v.contains("#remove")), violations.toString());
        assertTrue(violations.stream().noneMatch(v -> v.contains("'/fine'")), violations.toString());
    }
}

package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestMethodRulesTest {

    private static final JavaClasses FIXTURES =
        new ClassFileImporter().importPackages("edu.harvard.hms.dbmi.avillach.conventions.requestmethodfixtures");

    @Test
    void r12FlagsHandlersWhoseRequestMappingNamesNoVerb() {
        List<String> violations = RequestMethodRules.requestMappingNamesMethod("fixtures", FIXTURES);

        assertEquals(
            List.of(
                "fixtures :: VerbController#anyVerb carries @RequestMapping with no method, so it answers every HTTP verb",
                "fixtures :: VerbController#emptyMethods carries @RequestMapping with no method, so it answers every HTTP verb"
            ),
            violations
        );
    }

    @Test
    void r12AcceptsNamedVerbsComposedMappingsAndClassLevelPrefixes() {
        List<String> violations = RequestMethodRules.requestMappingNamesMethod("fixtures", FIXTURES);

        for (String accepted : List.of("#oneVerb", "#twoVerbs", "#composed", "NotAController", "VerbController carries")) {
            assertTrue(violations.stream().noneMatch(v -> v.contains(accepted)), accepted + " in " + violations);
        }
    }
}

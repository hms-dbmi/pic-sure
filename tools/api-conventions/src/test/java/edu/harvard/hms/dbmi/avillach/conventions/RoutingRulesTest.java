package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingRulesTest {

    private static final JavaClasses FIXTURES =
        new ClassFileImporter().importPackages("edu.harvard.hms.dbmi.avillach.conventions.routingfixtures");

    private static void assertMentions(List<String> violations, String fragment) {
        assertTrue(violations.stream().anyMatch(v -> v.contains(fragment)), fragment + " in " + violations);
    }

    @Test
    void r17FlagsNamedPathVariablesNoMappedPathDeclares() {
        List<String> violations = RoutingRules.pathVariablesAppearInTemplate("fixtures", FIXTURES);

        assertEquals(3, violations.size(), violations.toString());
        assertMentions(violations, "fixtures :: UnboundVariableController#consents binds @PathVariable(\"userId\") "
            + "but no mapped path [/user/me/consents] declares {userId}");
        assertMentions(violations, "UnboundVariableController#rename binds @PathVariable(\"userId\")");
        assertMentions(violations, "UnboundVariableController#roles binds @PathVariable(\"userId\")");
    }

    @Test
    void r17AcceptsClassLevelRegexCaptureAndAlternativePathsAndSkipsUnnamedVariables() {
        List<String> violations = RoutingRules.pathVariablesAppearInTemplate("fixtures", FIXTURES);

        assertTrue(violations.stream().noneMatch(v -> v.contains("BoundVariableController")), violations.toString());
    }

    @Test
    void r17ReadsVariableNamesFromEveryTemplateForm() {
        assertEquals(List.of("id"), RoutingRules.templateVariables("/user/{id}"));
        assertEquals(List.of("version", "part"), RoutingRules.templateVariables("/v/{version:[0-9]{1,3}}/{part}"));
        assertEquals(List.of("rest"), RoutingRules.templateVariables("/files/{*rest}"));
        assertEquals(List.of(), RoutingRules.templateVariables("/user/me/consents"));
    }
}

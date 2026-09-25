package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationRulesTest {

    private static final JavaClasses FIXTURES =
        new ClassFileImporter().importPackages("edu.harvard.hms.dbmi.avillach.conventions.configurationfixtures");

    private static void assertMentions(List<String> violations, String fragment) {
        assertTrue(violations.stream().anyMatch(v -> v.contains(fragment)), fragment + " in " + violations);
    }

    @Test
    void r18FlagsUnclosedPlaceholdersOnFieldsConstructorsAndMethods() {
        List<String> violations = ConfigurationRules.valuePlaceholdersAreClosed("fixtures", FIXTURES);

        assertEquals(5, violations.size(), violations.toString());
        assertMentions(violations, "PlaceholderSettings#openField @Value(\"${open.field\") opens 1 placeholder(s) or expression(s) but has 0");
        assertMentions(violations, "PlaceholderSettings#secondOpen @Value(\"${first}-${second\") opens 2 placeholder(s) or expression(s) but has 1");
        assertMentions(violations, "PlaceholderSettings#openInsideExpression @Value(\"#{'${open.expression'}\") opens 2");
        assertMentions(violations, "PlaceholderSettings#<init> parameter 1 @Value(\"${open.constructor\")");
        assertMentions(violations, "PlaceholderSettings#configure parameter 1 @Value(\"${open.method\")");
        assertTrue(violations.get(0).startsWith("fixtures :: "), violations.get(0));
    }

    @Test
    void r18AcceptsClosedNestedExpressionAndLiteralValues() {
        List<String> violations = ConfigurationRules.valuePlaceholdersAreClosed("fixtures", FIXTURES);

        for (String accepted : List.of("closedField", "nestedDefault", "literal", "configureClosed", "closedDefault", "closed.constructor")) {
            assertTrue(violations.stream().noneMatch(v -> v.contains(accepted)), accepted + " in " + violations);
        }
    }
}

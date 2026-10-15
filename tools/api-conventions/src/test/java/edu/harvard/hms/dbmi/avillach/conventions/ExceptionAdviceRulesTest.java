package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExceptionAdviceRulesTest {

    private static final JavaClasses FIXTURES =
        new ClassFileImporter().importPackages("edu.harvard.hms.dbmi.avillach.conventions.advicefixtures");

    private static void assertMentions(List<String> violations, String fragment) {
        assertTrue(violations.stream().anyMatch(v -> v.contains(fragment)), fragment + " in " + violations);
    }

    @Test
    void flagsEveryCatchAllHandlerInAnAdviceWithoutTheBaseClass() {
        List<String> violations = ExceptionAdviceRules.catchAllAdviceExtendsBaseHandler("fixtures", FIXTURES);

        assertEquals(4, violations.size(), violations.toString());
        assertMentions(violations, "CatchAllAdvice#unknown handles Exception but CatchAllAdvice does not extend ResponseEntityExceptionHandler");
        assertMentions(violations, "CatchAllAdvice#runtime handles RuntimeException but");
        assertMentions(violations, "CatchAllAdvice#throwable handles Throwable but");
        assertMentions(violations, "ParameterCatchAllAdvice#unknown handles Exception but");
        assertTrue(violations.get(0).startsWith("fixtures :: "), violations.get(0));
    }

    @Test
    void acceptsTheBaseClassNarrowAdviceAndControllerLocalHandlers() {
        List<String> violations = ExceptionAdviceRules.catchAllAdviceExtendsBaseHandler("fixtures", FIXTURES);

        assertTrue(violations.stream().noneMatch(v -> v.contains("#narrow")), violations.toString());
        assertTrue(violations.stream().noneMatch(v -> v.contains("BaseHandlerAdvice")), violations.toString());
        assertTrue(violations.stream().noneMatch(v -> v.contains("NarrowAdvice")), violations.toString());
        assertTrue(violations.stream().noneMatch(v -> v.contains("ControllerLocalHandler")), violations.toString());
    }
}

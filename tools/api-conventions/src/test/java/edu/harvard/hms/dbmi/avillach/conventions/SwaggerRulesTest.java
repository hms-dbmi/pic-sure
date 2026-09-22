package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwaggerRulesTest {

    private static final JavaClasses FIXTURES =
        new ClassFileImporter().importPackages("edu.harvard.hms.dbmi.avillach.conventions.fixtures");

    @Test
    void r1FlagsOnlyTheUntaggedController() {
        List<String> violations = SwaggerRules.tagOrHidden("fixtures", FIXTURES);

        assertEquals(2, violations.size(), violations.toString());
        assertTrue(violations.stream().anyMatch(v -> v.contains("UntaggedController")), violations.toString());
        assertTrue(violations.stream().anyMatch(v -> v.contains("BothTagAndHiddenController") && v.contains("both")), violations.toString());
        assertTrue(violations.get(0).contains("fixtures"), violations.get(0));
    }

    @Test
    void r2FlagsOnlyTheBlankDescription() {
        List<String> violations = SwaggerRules.tagIsComplete("fixtures", FIXTURES);

        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("BlankTagController"), violations.get(0));
        assertTrue(violations.get(0).contains("description"), violations.get(0));
    }

    @Test
    void r3FlagsAMissingOperationAndABlankSummaryAndNothingElse() {
        List<String> violations = SwaggerRules.operationHasSummary("fixtures", FIXTURES);

        assertEquals(3, violations.size(), violations.toString());
        assertTrue(violations.stream().anyMatch(v -> v.contains("NoOperationController#read")), violations.toString());
        assertTrue(violations.stream().anyMatch(v -> v.contains("NoOperationController#create")), violations.toString());
        assertTrue(violations.stream().anyMatch(v -> v.contains("NoOperationController#absentSummary")), violations.toString());
    }

    @Test
    void r3ExemptsAHiddenClassAndIgnoresNonHandlers() {
        List<String> violations = SwaggerRules.operationHasSummary("fixtures", FIXTURES);

        assertTrue(violations.stream().noneMatch(v -> v.contains("HiddenController")), violations.toString());
        assertTrue(violations.stream().noneMatch(v -> v.contains("helper")), violations.toString());
        assertTrue(violations.stream().noneMatch(v -> v.contains("hiddenMethod")), violations.toString());
    }

    @Test
    void r4FlagsMissingCodesBlankDescriptionsAndMissingSuccess() {
        List<String> violations = SwaggerRules.responsesAreDeclared("fixtures", FIXTURES);

        assertEquals(5, violations.size(), violations.toString());
        assertTrue(violations.stream().anyMatch(v -> v.contains("#none") && v.contains("declares no @ApiResponse")), violations.toString());
        assertTrue(violations.stream().anyMatch(v -> v.contains("#badCode") && v.contains("okay")), violations.toString());
        assertTrue(violations.stream().anyMatch(v -> v.contains("#badCode") && v.contains("no 2xx")), violations.toString());
        assertTrue(violations.stream().anyMatch(v -> v.contains("#blankDescription") && v.contains("description")), violations.toString());
        assertTrue(violations.stream().anyMatch(v -> v.contains("#errorOnly") && v.contains("no 2xx")), violations.toString());
    }

    @Test
    void r4AcceptsBothASingleResponseAndAContainer() {
        List<String> violations = SwaggerRules.responsesAreDeclared("fixtures", FIXTURES);

        assertTrue(violations.stream().noneMatch(v -> v.contains("GoodController")), violations.toString());
        assertTrue(violations.stream().noneMatch(v -> v.contains("hiddenMethod")), violations.toString());
    }
}

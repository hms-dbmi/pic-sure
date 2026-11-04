package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuditRulesTest {

    private static final String FIXTURES = "edu.harvard.hms.dbmi.avillach.conventions.auditfixtures";

    private static JavaClasses fixtures(String subPackage) {
        return new ClassFileImporter().importPackages(FIXTURES + "." + subPackage);
    }

    @Test
    void flagsEveryUnlabeledHandlerInAModuleThatLabelsSome() {
        List<String> violations = AuditRules.auditEventOnEveryHandler("partial", fixtures("partial"));

        assertEquals(
            List.of(
                "partial :: AuditedController#create has no @AuditEvent, so it is audited as UNLABELED",
                "partial :: ForgottenController#consents has no @AuditEvent, so it is audited as UNLABELED"
            ),
            violations
        );
    }

    @Test
    void passesAModuleThatLabelsEveryHandler() {
        assertEquals(List.of(), AuditRules.auditEventOnEveryHandler("complete", fixtures("complete")));
    }

    @Test
    void flagsEveryHandlerInAModuleThatLabelsNone() {
        assertEquals(
            List.of("unaudited :: UnauditedController#read has no @AuditEvent, so it is audited as UNLABELED"),
            AuditRules.auditEventOnEveryHandler("unaudited", fixtures("unaudited"))
        );
    }
}

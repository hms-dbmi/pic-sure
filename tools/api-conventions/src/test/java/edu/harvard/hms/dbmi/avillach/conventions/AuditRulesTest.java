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
    void r15FlagsEveryUnlabeledHandlerInAnAuditedModule() {
        List<String> violations = AuditRules.auditEventOnEveryHandler("partial", fixtures("partial"));

        assertEquals(
            List.of(
                "partial :: AuditedController#create has no @AuditEvent, so it is audited under a fallback label; "
                    + "1 of 3 handlers in the module carry it",
                "partial :: ForgottenController#consents has no @AuditEvent, so it is audited under a fallback label; "
                    + "1 of 3 handlers in the module carry it"
            ),
            violations
        );
    }

    @Test
    void r15PassesAModuleThatLabelsEveryHandler() {
        assertEquals(List.of(), AuditRules.auditEventOnEveryHandler("complete", fixtures("complete")));
    }

    @Test
    void r15IgnoresAModuleThatLabelsNoHandler() {
        assertEquals(List.of(), AuditRules.auditEventOnEveryHandler("unaudited", fixtures("unaudited")));
    }
}

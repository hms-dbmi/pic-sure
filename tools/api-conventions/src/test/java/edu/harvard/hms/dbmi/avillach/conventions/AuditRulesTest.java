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

    @Test
    void flagsInternalHandlersWithoutThePrefixAndThePrefixOutsideInternalPaths() {
        List<String> violations = AuditRules.internalHandlersUseInternalAuditAction("internalmisnamed", fixtures("internalmisnamed"));

        assertEquals(
            List.of(
                "internalmisnamed :: MisnamedInternalController#list maps [/internal/things] under an internal path segment, "
                    + "so its @AuditEvent action 'thing.list' must start with 'internal_'",
                "internalmisnamed :: MisnamedInternalController#search maps [/internal/search/{backend}] under an internal path "
                    + "segment, so its @AuditEvent action 'search.execute' must start with 'internal_'",
                "internalmisnamed :: MisnamedInternalController#sync maps [/internal/things/sync] under an internal path "
                    + "segment, so its @AuditEvent action 'thing.internal_sync' must start with 'internal_'",
                "internalmisnamed :: MisnamedPublicController#export maps [/internal/things/export] under an internal path "
                    + "segment, so its @AuditEvent action 'thing.export' must start with 'internal_'",
                "internalmisnamed :: MisnamedPublicController#read has @AuditEvent action 'internal_thing.read' but maps "
                    + "[/things/{thingId}] with no internal path segment",
                "internalmisnamed :: MisnamedPublicController#sync has @AuditEvent action 'internal_thing.sync' but maps "
                    + "[/things/sync] with no internal path segment"
            ),
            violations
        );
    }

    @Test
    void passesPrefixedInternalHandlersAndUnprefixedPublicHandlers() {
        assertEquals(
            List.of(), AuditRules.internalHandlersUseInternalAuditAction("internalnamed", fixtures("internalnamed"))
        );
    }
}

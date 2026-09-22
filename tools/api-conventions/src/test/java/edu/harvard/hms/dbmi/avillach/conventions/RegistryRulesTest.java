package edu.harvard.hms.dbmi.avillach.conventions;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryRulesTest {

    private static final JavaClasses WITH_CONTROLLERS =
        new ClassFileImporter().importPackages("edu.harvard.hms.dbmi.avillach.conventions.fixtures");
    private static final JavaClasses WITHOUT_CONTROLLERS =
        new ClassFileImporter().importPackages("com.tngtech.archunit.core.importer");

    @Test
    void r0aFlagsADocumentedModuleThatYieldsNoController() {
        ModuleRegistry registry = ModuleRegistry.parse(new StringReader("services/empty = documented\n"));

        List<String> violations =
            RegistryRules.documentedModulesYieldControllers(registry, Map.of("services/empty", WITHOUT_CONTROLLERS));

        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("services/empty"), violations.get(0));
    }

    @Test
    void r0aFlagsADocumentedModuleThatWasNeverBuilt() {
        ModuleRegistry registry = ModuleRegistry.parse(new StringReader("services/gone = documented\n"));

        List<String> violations = RegistryRules.documentedModulesYieldControllers(registry, Map.of());

        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("services/gone"), violations.get(0));
    }

    @Test
    void r0aPassesWhenTheModuleHasControllers() {
        ModuleRegistry registry = ModuleRegistry.parse(new StringReader("services/ok = documented\n"));

        assertTrue(
            RegistryRules.documentedModulesYieldControllers(registry, Map.of("services/ok", WITH_CONTROLLERS)).isEmpty()
        );
    }

    @Test
    void r0bFlagsAnUnregisteredModuleThatDeclaresAController() {
        ModuleRegistry registry = ModuleRegistry.parse(new StringReader("services/known = documented\n"));

        List<String> violations =
            RegistryRules.controllerModulesAreRegistered(registry, Map.of("services/surprise", WITH_CONTROLLERS));

        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("services/surprise"), violations.get(0));
    }

    @Test
    void r0bIgnoresAnUnregisteredModuleWithNoController() {
        ModuleRegistry registry = ModuleRegistry.parse(new StringReader("services/known = documented\n"));

        assertTrue(
            RegistryRules.controllerModulesAreRegistered(registry, Map.of("services/quiet", WITHOUT_CONTROLLERS)).isEmpty()
        );
    }
}

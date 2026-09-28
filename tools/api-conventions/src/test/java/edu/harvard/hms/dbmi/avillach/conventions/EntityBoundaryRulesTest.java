package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityBoundaryRulesTest {

    private static final String FIXTURES_PACKAGE = "edu.harvard.hms.dbmi.avillach.conventions.entityfixtures";

    private static final JavaClasses FIXTURES = new ClassFileImporter().importPackages(FIXTURES_PACKAGE);

    private static final List<String> VIOLATIONS = EntityBoundaryRules.noEntityParameters("fixtures", FIXTURES, Set.of());

    private static void assertMentions(List<String> violations, String fragment) {
        assertTrue(violations.stream().anyMatch(v -> v.contains(fragment)), fragment + " in " + violations);
    }

    private static void assertSilentOn(List<String> violations, String fragment) {
        assertTrue(violations.stream().noneMatch(v -> v.contains(fragment)), fragment + " in " + violations);
    }

    @Test
    void flagsEveryShapeThatContainsAnEntity() {
        assertEquals(8, VIOLATIONS.size(), VIOLATIONS.toString());
        assertMentions(VIOLATIONS, "fixtures :: EntityBindingController#bare parameter 0 binds entity User; bind a request record instead");
        assertMentions(VIOLATIONS, "EntityBindingController#list parameter 0 binds entity Role;");
        assertMentions(VIOLATIONS, "EntityBindingController#nested parameter 0 binds entity User;");
        assertMentions(VIOLATIONS, "EntityBindingController#array parameter 0 binds entity User;");
        assertMentions(VIOLATIONS, "EntityBindingController#optional parameter 0 binds entity User;");
        assertMentions(VIOLATIONS, "EntityBindingController#wildcard parameter 0 binds entity Role;");
        assertMentions(VIOLATIONS, "EntityBindingController#both parameter 0 binds entity Role, User;");
    }

    @Test
    void flagsParametersOtherThanTheRequestBody() {
        assertMentions(VIOLATIONS, "EntityBindingController#model parameter 1 binds entity Role;");
        assertSilentOn(VIOLATIONS, "#model parameter 0");
    }

    @Test
    void acceptsDisplayClassesRecordsWithEntityFieldsAndUnmappedMethods() {
        assertSilentOn(VIOLATIONS, "#display");
        assertSilentOn(VIOLATIONS, "#record");
        assertSilentOn(VIOLATIONS, "#unmapped");
    }

    @Test
    void recognisesAnEntityFromAnotherModuleByName() {
        Set<String> entities = EntityBoundaryRules.entityTypes(Map.of("entities", FIXTURES));
        JavaClasses controllerOnly = importWithoutClasspathResolution(FIXTURES_PACKAGE + ".rest");

        assertEquals(Set.of(FIXTURES_PACKAGE + ".entity.User", FIXTURES_PACKAGE + ".entity.Role"), entities);
        assertEquals(List.of(), EntityBoundaryRules.noEntityParameters("fixtures", controllerOnly, Set.of()));
        assertEquals(VIOLATIONS, EntityBoundaryRules.noEntityParameters("fixtures", controllerOnly, entities));
    }

    /**
     * Imports a package the way the reactor test imports one module: classes outside it stay unresolved
     * stubs with no annotations.
     *
     * @param packageName the package to import
     * @return its classes, with every outside reference left unresolved
     */
    private static JavaClasses importWithoutClasspathResolution(String packageName) {
        ArchConfiguration configuration = ArchConfiguration.get();
        boolean resolve = configuration.resolveMissingDependenciesFromClassPath();
        configuration.setResolveMissingDependenciesFromClassPath(false);
        try {
            return new ClassFileImporter().importPackages(packageName);
        } finally {
            configuration.setResolveMissingDependenciesFromClassPath(resolve);
        }
    }
}

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

class SignatureRulesTest {

    private static final String FIXTURES_PACKAGE = "edu.harvard.hms.dbmi.avillach.conventions.typedfixtures";

    private static final JavaClasses FIXTURES = new ClassFileImporter().importPackages(FIXTURES_PACKAGE);

    private static final List<String> VIOLATIONS = SignatureRules.typedHandlerSignatures("fixtures", FIXTURES, Set.of());

    private static void assertReports(List<String> violations, String violation) {
        assertTrue(violations.contains(violation), violation + " in\n  " + String.join("\n  ", violations));
    }

    private static void assertSilentOn(List<String> violations, String fragment) {
        assertTrue(violations.stream().noneMatch(v -> v.contains(fragment)), fragment + " in " + violations);
    }

    @Test
    void flagsEveryUntypedReturn() {
        String at = "fixtures :: UntypedSignaturesController#";
        assertReports(VIOLATIONS, at + "object return Object is not typed: Object is untyped");
        assertReports(VIOLATIONS, at + "wildcardEntity return ResponseEntity<?> is not typed: ? is a wildcard");
        assertReports(VIOLATIONS, at + "boundedWildcard return List<? extends Widget> is not typed: ? extends Widget is a wildcard");
        assertReports(VIOLATIONS, at + "typeVariable return T is not typed: T is a type variable");
        assertReports(VIOLATIONS, at + "rawEntity return ResponseEntity is not typed: ResponseEntity is raw");
        assertReports(VIOLATIONS, at + "rawList return List is not typed: List is raw");
        assertReports(VIOLATIONS, at + "map return Map<String, Object> is not typed: Map<String, Object> is a map");
        assertReports(
            VIOLATIONS,
            at + "hashMap return ResponseEntity<HashMap<String, String>> is not typed: HashMap<String, String> is a map"
        );
        assertReports(
            VIOLATIONS,
            at + "nestedMap return ResponseEntity<List<Map<String, Integer>>> is not typed: Map<String, Integer> is a map"
        );
        assertReports(VIOLATIONS, at + "jsonNode return JsonNode is not typed: JsonNode is a Jackson tree type");
        assertReports(VIOLATIONS, at + "objectNode return Mono<ObjectNode> is not typed: ObjectNode is a Jackson tree type");
        assertReports(VIOLATIONS, at + "page return Page<Widget> is not typed: Page<Widget> is a Spring Data page or slice");
        assertReports(VIOLATIONS, at + "slice return Slice<Widget> is not typed: Slice<Widget> is a Spring Data page or slice");
        assertReports(VIOLATIONS, at + "entity return WidgetEntity is not typed: WidgetEntity is an @Entity");
        assertReports(VIOLATIONS, at + "entityList return List<WidgetEntity> is not typed: WidgetEntity is an @Entity");
        assertReports(VIOLATIONS, at + "entityArray return WidgetEntity[] is not typed: WidgetEntity is an @Entity");
        assertReports(VIOLATIONS, at + "wildcardEnvelope return Envelope<?> is not typed: ? is a wildcard");
        assertReports(VIOLATIONS, at + "mapInEnvelope return Envelope<Map<String, List<?>>> is not typed: Map<String, List<?>> is a map");
    }

    @Test
    void flagsEveryUntypedRequestBody() {
        String at = "fixtures :: UntypedSignaturesController#";
        assertReports(VIOLATIONS, at + "mapBody parameter 0 Map<String, Object> is not typed: Map<String, Object> is a map");
        assertReports(VIOLATIONS, at + "jsonBody parameter 1 JsonNode is not typed: JsonNode is a Jackson tree type");
        assertReports(VIOLATIONS, at + "objectBody parameter 0 Object is not typed: Object is untyped");
        assertReports(VIOLATIONS, at + "entityBody parameter 0 List<WidgetEntity> is not typed: WidgetEntity is an @Entity");
    }

    @Test
    void reportsTheBodyAndTheReturnOfOneHandlerSeparately() {
        String at = "fixtures :: UntypedSignaturesController#both ";
        assertReports(VIOLATIONS, at + "parameter 0 Map<String, String> is not typed: Map<String, String> is a map");
        assertReports(VIOLATIONS, at + "return Map<String, String> is not typed: Map<String, String> is a map");
    }

    @Test
    void checksAHiddenController() {
        assertReports(
            VIOLATIONS,
            "fixtures :: HiddenUntypedController#save return Map<String, UUID> is not typed: Map<String, UUID> is a map"
        );
    }

    @Test
    void flagsNothingElse() {
        assertEquals(25, VIOLATIONS.size(), String.join("\n", VIOLATIONS));
        assertSilentOn(VIOLATIONS, "TypedSignaturesController");
        assertSilentOn(VIOLATIONS, "#jsonBody parameter 0");
    }

    @Test
    void recognisesAnEntityFromAnotherModuleByName() {
        Set<String> entities = EntityBoundaryRules.entityTypes(Map.of("entities", FIXTURES));
        JavaClasses controllersOnly = importWithoutClasspathResolution(FIXTURES_PACKAGE + ".rest");

        assertEquals(Set.of(FIXTURES_PACKAGE + ".entity.WidgetEntity"), entities);
        assertSilentOn(SignatureRules.typedHandlerSignatures("fixtures", controllersOnly, Set.of()), "@Entity");
        assertEquals(VIOLATIONS, SignatureRules.typedHandlerSignatures("fixtures", controllersOnly, entities));
    }

    /**
     * Imports a package the way the reactor test imports one module: classes outside it stay unresolved
     * stubs with no annotations and no supertypes.
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

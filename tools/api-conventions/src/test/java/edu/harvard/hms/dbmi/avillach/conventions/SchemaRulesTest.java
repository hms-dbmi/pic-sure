package edu.harvard.hms.dbmi.avillach.conventions;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaRulesTest {

    private static final String FIXTURES_PACKAGE = "edu.harvard.hms.dbmi.avillach.conventions.schemafixtures";
    private static final String SERVICE = "services/fixture";
    private static final String LIBRARY = "libs/fixture-models";

    private static final ModuleRegistry REGISTRY = ModuleRegistry.parse(new StringReader(SERVICE + " = documented\n"));

    private static final List<String> VIOLATIONS = SchemaRules.documentedModels(REGISTRY, modules(), List.of(LIBRARY));

    private static Map<String, JavaClasses> modules() {
        Map<String, JavaClasses> modules = new LinkedHashMap<>();
        modules.put(SERVICE, new ClassFileImporter().importPackages(FIXTURES_PACKAGE + ".rest", FIXTURES_PACKAGE + ".model"));
        modules.put(LIBRARY, new ClassFileImporter().importPackages(FIXTURES_PACKAGE + ".library"));
        return modules;
    }

    private static void assertReports(List<String> violations, String... expected) {
        for (String violation : expected) {
            assertTrue(violations.contains(violation), violation + " in\n  " + String.join("\n  ", violations));
        }
    }

    private static void assertSilentOn(List<String> violations, String... fragments) {
        for (String fragment : fragments) {
            assertTrue(violations.stream().noneMatch(v -> v.contains(fragment)), fragment + " in " + violations);
        }
    }

    @Test
    void flagsAMissingOrBlankClassDescription() {
        assertReports(
            VIOLATIONS,
            "services/fixture :: NoClassDescription is missing a class-level @Schema description",
            "services/fixture :: BlankClassDescription is missing a class-level @Schema description"
        );
        assertSilentOn(VIOLATIONS, "NoClassDescription#", "BlankClassDescription#");
    }

    @Test
    void flagsMembersWithNoDescription() {
        String at = "services/fixture :: MissingFieldDescriptions#";
        assertReports(
            VIOLATIONS,
            at + "bare is missing a @Schema description",
            at + "bare is missing a @Schema example",
            at + "exampleOnly is missing a @Schema description",
            at + "blank is missing a @Schema description",
            at + "nested is missing a @Schema description",
            at + "attributes is missing a @Schema description",
            at + "flag is missing a @Schema description"
        );
        assertSilentOn(VIOLATIONS, at + "exampleOnly is missing a @Schema example", at + "blank is missing a @Schema example");
    }

    @Test
    void flagsScalarsWithNoExample() {
        String at = "services/fixture :: MissingScalarExamples#";
        assertReports(
            VIOLATIONS,
            at + "name is missing a @Schema example",
            at + "count is missing a @Schema example",
            at + "total is missing a @Schema example",
            at + "id is missing a @Schema example",
            at + "created is missing a @Schema example",
            at + "updated is missing a @Schema example",
            at + "released is missing a @Schema example",
            at + "blankExample is missing a @Schema example"
        );
        assertSilentOn(VIOLATIONS, "MissingScalarExamples is missing", "MissingScalarExamples#name is missing a @Schema description");
    }

    @Test
    void flagsCollectionsAndArraysOfScalarsWithNoExample() {
        String at = "services/fixture :: MissingCollectionExamples#";
        assertReports(
            VIOLATIONS,
            at + "names is missing a @Schema example",
            at + "ids is missing a @Schema example",
            at + "totals is missing a @Schema example",
            at + "aliases is missing a @Schema example",
            at + "counts is missing a @Schema example",
            at + "matrix is missing a @Schema example"
        );
        assertSilentOn(VIOLATIONS, at + "flags", at + "kinds");
    }

    @Test
    void exemptsBooleansEnumsNestedModelsMapsAndTypeVariablesFromTheExample() {
        assertSilentOn(
            VIOLATIONS,
            "MissingFieldDescriptions#nested is missing a @Schema example",
            "MissingFieldDescriptions#attributes is missing a @Schema example",
            "MissingFieldDescriptions#flag is missing a @Schema example",
            "LegacyDto#state",
            "Study#",
            "Envelope#"
        );
    }

    @Test
    void flagsAnEnumWithNoDescriptionOnTheTypeOrOnAConstant() {
        assertReports(
            VIOLATIONS,
            "services/fixture :: UndocumentedEnum is missing a class-level @Schema description",
            "services/fixture :: UndocumentedEnum#BARE is missing a @Schema description",
            "services/fixture :: UndocumentedEnum#BLANK is missing a @Schema description"
        );
        assertSilentOn(VIOLATIONS, "UndocumentedEnum#DOCUMENTED", "UndocumentedEnum#$VALUES");
    }

    @Test
    void followsSuperclassesAndTheSubtypesJacksonIsTold() {
        assertReports(
            VIOLATIONS,
            "services/fixture :: LegacyDto#label is missing a @Schema description",
            "services/fixture :: LegacyDto#label is missing a @Schema example",
            "services/fixture :: UndocumentedBase is missing a class-level @Schema description",
            "services/fixture :: UndocumentedBase#inherited is missing a @Schema description",
            "services/fixture :: UndocumentedBase#inherited is missing a @Schema example",
            "services/fixture :: Blob is missing a class-level @Schema description",
            "services/fixture :: Blob#outline is missing a @Schema description",
            "services/fixture :: Blob#outline is missing a @Schema example"
        );
        assertSilentOn(VIOLATIONS, ":: Sketch", ":: Shape", ":: Circle");
    }

    @Test
    void startsFromRequestBodiesAndFromHiddenControllers() {
        assertReports(
            VIOLATIONS,
            "services/fixture :: UndocumentedRequest is missing a class-level @Schema description",
            "services/fixture :: UndocumentedRequest#accession is missing a @Schema description",
            "services/fixture :: UndocumentedRequest#accession is missing a @Schema example",
            "services/fixture :: HiddenResult is missing a class-level @Schema description",
            "services/fixture :: HiddenResult#count is missing a @Schema description",
            "services/fixture :: HiddenResult#count is missing a @Schema example"
        );
        assertSilentOn(VIOLATIONS, ":: Unreached");
    }

    @Test
    void namesTheLibraryThatCompilesASharedModel() {
        assertReports(VIOLATIONS, "libs/fixture-models :: SharedBroken#state is missing a @Schema example");
        assertSilentOn(VIOLATIONS, "services/fixture :: SharedBroken", ":: SharedStatus");
    }

    @Test
    void reportsAReachedTypeNoCheckedModuleCompiles() {
        assertReports(
            VIOLATIONS,
            "services/fixture :: ForeignTypeHolder#location reaches io.swagger.v3.oas.annotations.enums.ParameterIn, which no documented"
                + " module or shared model library compiles, so its @Schema cannot be read"
        );
    }

    @Test
    void skipsStaticSyntheticTransientAndIgnoredMembers() {
        assertSilentOn(
            VIOLATIONS,
            "#DEFAULT_ACCESSION",
            "#loaderNote",
            "#DEFAULT_CODE",
            "#code",
            "#serialVersionUID",
            "#cachedDisplayName",
            "#passwordHash",
            "#this$0"
        );
    }

    @Test
    void acceptsEveryDocumentedModel() {
        assertSilentOn(
            VIOLATIONS, ":: Study", ":: StudyKind", ":: Investigator", ":: Person", ":: Envelope", ":: Shelf", ":: Slot", ":: Circle",
            ":: SharedStatus"
        );
        assertEquals(42, VIOLATIONS.size(), String.join("\n", VIOLATIONS));
    }

    @Test
    void readsTheSameWhenEveryOutsideTypeIsAnUnresolvedStub() {
        ArchConfiguration configuration = ArchConfiguration.get();
        boolean resolve = configuration.resolveMissingDependenciesFromClassPath();
        configuration.setResolveMissingDependenciesFromClassPath(false);
        try {
            assertEquals(VIOLATIONS, SchemaRules.documentedModels(REGISTRY, modules(), List.of(LIBRARY)));
        } finally {
            configuration.setResolveMissingDependenciesFromClassPath(resolve);
        }
    }

    @Test
    void flagsASharedLibraryThatWasNotCompiled() {
        Map<String, JavaClasses> serviceOnly = modules();
        serviceOnly.remove(LIBRARY);

        List<String> violations = SchemaRules.documentedModels(REGISTRY, serviceOnly, List.of(LIBRARY));

        assertReports(violations, "libs/fixture-models holds shared models but was not compiled (run make build)");
        assertTrue(
            violations.stream().anyMatch(v -> v.contains("reaches " + FIXTURES_PACKAGE + ".library.SharedBroken")),
            violations.toString()
        );
    }
}

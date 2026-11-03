package edu.harvard.hms.dbmi.avillach.ai;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Mechanically enforces Story 3's "one isolated module" AC: nothing outside {@code ai.model} may reference a Bedrock- or
 * Spring-AI-model-specific type, so swapping Bedrock Converse for another provider later (per {@code PLAN.md}'s portability table) stays a
 * change to that one package.
 */
class ArchitectureTest {

    private static final String ROOT = "edu.harvard.hms.dbmi.avillach.ai";
    private static final String MODEL = ROOT + ".model..";

    private static JavaClasses mainClasses;

    @BeforeAll
    static void importMainClasses() {
        mainClasses = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT);
    }

    @Test
    void onlyModelPackageTouchesBedrockOrSpringAi() {
        noClasses().that().resideOutsideOfPackages(MODEL).should()
            .dependOnClassesThat(resideInAnyPackage("org.springframework.ai..", "software.amazon.awssdk.."))
            .because(
                "Bedrock/Spring-AI-model types must stay confined to ai.model, so the rest of the service never "
                    + "references the model provider directly and a future provider swap touches one package"
            ).check(mainClasses);
    }
}

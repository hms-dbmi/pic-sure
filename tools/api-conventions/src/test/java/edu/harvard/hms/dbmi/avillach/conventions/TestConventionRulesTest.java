package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import edu.harvard.hms.dbmi.avillach.conventions.testconventionfixtures.annotated.LegacyStyleTest;
import edu.harvard.hms.dbmi.avillach.conventions.testconventionfixtures.annotated.OverridingTest;
import edu.harvard.hms.dbmi.avillach.conventions.testconventionfixtures.annotated.SharedSetup;
import edu.harvard.hms.dbmi.avillach.conventions.testconventionfixtures.mocks.FieldMockTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestConventionRulesTest {

    private static final String FIXTURES = "edu.harvard.hms.dbmi.avillach.conventions.testconventionfixtures";
    private static final String CONSTRUCTOR_MOCK =
        " takes a @Mock constructor parameter, created before the Mockito session opens and outside strict stubbing";
    private static final String UNANNOTATED = " is a void method with no parameters and no JUnit annotation, so no runner ever executes it";

    private static JavaClasses fixtures(String subPackage) {
        return new ClassFileImporter().importPackages(FIXTURES + "." + subPackage);
    }

    private static JavaClasses only(Class<?>... types) {
        return new ClassFileImporter().importClasses(types);
    }

    @Test
    void flagsAConstructorThatTakesAMock() {
        assertEquals(
            List.of("mocks :: ConstructorMockTest#<init>" + CONSTRUCTOR_MOCK),
            TestConventionRules.noConstructorParameterMocks("mocks", fixtures("mocks"))
        );
    }

    @Test
    void passesMockFieldsAndLifecycleParameters() {
        assertEquals(List.of(), TestConventionRules.noConstructorParameterMocks("mocks", only(FieldMockTest.class)));
    }

    @Test
    void flagsUnannotatedTestShapedMethodsInTopLevelAndNestedClasses() {
        List<String> violations = TestConventionRules.testMethodsAreAnnotated("annotated", fixtures("annotated"));

        assertEquals(
            List.of(
                "annotated :: NestedTest.Inner#innerForgotten" + UNANNOTATED,
                "annotated :: SilentMethodTest#alsoForgotten" + UNANNOTATED,
                "annotated :: SilentMethodTest#forgotten" + UNANNOTATED
            ),
            violations
        );
    }

    @Test
    void passesJunitFourAnnotations() {
        assertEquals(List.of(), TestConventionRules.testMethodsAreAnnotated("annotated", only(LegacyStyleTest.class)));
    }

    @Test
    void passesMethodsThatOverrideASupertype() {
        assertEquals(List.of(), TestConventionRules.testMethodsAreAnnotated("annotated", only(OverridingTest.class, SharedSetup.class)));
    }

    @Test
    void eachRuleIgnoresTheOtherFixtures() {
        assertEquals(List.of(), TestConventionRules.noConstructorParameterMocks("annotated", fixtures("annotated")));
        assertEquals(List.of(), TestConventionRules.testMethodsAreAnnotated("mocks", fixtures("mocks")));
    }
}

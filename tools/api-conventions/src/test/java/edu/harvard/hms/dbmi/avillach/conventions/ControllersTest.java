package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import edu.harvard.hms.dbmi.avillach.conventions.fixtures.GoodController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllersTest {

    private static final JavaClasses FIXTURES =
        new ClassFileImporter().importPackages("edu.harvard.hms.dbmi.avillach.conventions.fixtures");

    @Test
    void findsEveryFixtureController() {
        List<String> names = Controllers.of(FIXTURES).stream().map(JavaClass::getSimpleName).sorted().toList();

        assertEquals(
            List.of(
                "BadResponsesController", "BlankTagController", "GoodController",
                "HiddenController", "NoOperationController", "UntaggedController"
            ),
            names
        );
    }

    @Test
    void handlerMethodsExcludeHelpers() {
        JavaClass good = FIXTURES.get(GoodController.class);

        List<String> handlers = Controllers.handlerMethods(good).stream().map(JavaMethod::getName).sorted().toList();

        assertEquals(List.of("create", "read"), handlers);
    }

    @Test
    void readsTagProperties() {
        JavaClass good = FIXTURES.get(GoodController.class);

        assertTrue(Annotations.has(good, Annotations.TAG));
        assertEquals(
            Optional.of("Good"),
            Annotations.get(good, Annotations.TAG).flatMap(tag -> Annotations.string(tag, "name"))
        );
    }
}

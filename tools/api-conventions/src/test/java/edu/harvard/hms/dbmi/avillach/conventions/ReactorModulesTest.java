package edu.harvard.hms.dbmi.avillach.conventions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tngtech.archunit.core.domain.JavaClasses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactorModulesTest {

    @Test
    void findsNestedModulesSeparately(@TempDir Path root) throws IOException {
        makeModule(root, "services/parent");
        makeModule(root, "services/parent/child");
        makeModule(root, "libs/lib-one");
        Files.createDirectories(root.resolve("services/no-pom/target/classes"));

        Map<String, JavaClasses> modules = ReactorModules.discover(root);

        assertEquals(Set.of("services/parent", "services/parent/child", "libs/lib-one"), modules.keySet());
    }

    @Test
    void skipsAModuleThatWasNeverCompiled(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("services/uncompiled"));
        Files.writeString(root.resolve("services/uncompiled/pom.xml"), "<project/>");

        assertTrue(ReactorModules.discover(root).isEmpty());
    }

    @Test
    void importsRealClassesFromTheReactor() {
        Path root = Path.of(System.getProperty("reactor.root"));

        Map<String, JavaClasses> modules = ReactorModules.discover(root);

        assertTrue(modules.containsKey("services/pic-sure-visualization-service"), modules.keySet().toString());
        assertTrue(
            modules.get("services/pic-sure-visualization-service").stream()
                .anyMatch(type -> type.getSimpleName().equals("DistributionController"))
        );
    }

    private static void makeModule(Path root, String modulePath) throws IOException {
        Files.createDirectories(root.resolve(modulePath).resolve("target/classes"));
        Files.writeString(root.resolve(modulePath).resolve("pom.xml"), "<project/>");
    }
}

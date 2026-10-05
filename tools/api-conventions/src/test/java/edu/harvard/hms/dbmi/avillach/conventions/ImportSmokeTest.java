package edu.harvard.hms.dbmi.avillach.conventions;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves ArchUnit can read this reactor's bytecode at all. JDK 25 emits class file major version 69,
 * which is newer than the ArchUnit release, so this failing would invalidate every other rule here.
 */
class ImportSmokeTest {

    @Test
    void readsReactorBytecode() {
        Path classes = Paths.get(System.getProperty("reactor.root"), "services/pic-sure-visualization-service/target/classes");
        assertTrue(classes.toFile().isDirectory(), "not built: " + classes + " (run mvn -T1C install -DskipTests first)");

        JavaClasses imported = new ClassFileImporter().importPaths(classes);

        assertFalse(imported.isEmpty(), "imported nothing from " + classes);
        assertTrue(
            imported.stream().anyMatch(type -> type.getSimpleName().equals("DistributionController")),
            "DistributionController missing from the import"
        );
    }
}

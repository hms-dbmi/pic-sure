package edu.harvard.hms.dbmi.avillach.conventions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/**
 * Finds the reactor's compiled modules on disk and imports each one's classes on its own.
 *
 * <p>Reading the filesystem rather than resolving dependencies is forced: every pic-sure service runs
 * the Boot repackage goal with no classifier, so its installed jar holds the module's classes under
 * BOOT-INF/classes where no dependent can see them.
 *
 * <p>Each module is imported separately and kept under its own key. Attributing classes to modules by
 * path prefix afterwards would fold a nested module such as services/picsure-dictionary/aggregate into
 * its parent and give it the wrong scope.
 */
public final class ReactorModules {

    private ReactorModules() {}

    /**
     * Imports every compiled module under {@code reactorRoot}.
     *
     * @param reactorRoot the repository root
     * @return module path relative to the root, mapped to that module's imported classes. A module with
     *     no {@code target/classes} directory is absent.
     */
    public static Map<String, JavaClasses> discover(Path reactorRoot) {
        Map<String, JavaClasses> modules = new LinkedHashMap<>();
        for (Path pom : pomFiles(reactorRoot)) {
            Path moduleDir = pom.getParent();
            Path classes = moduleDir.resolve("target/classes");
            if (!Files.isDirectory(classes)) {
                continue;
            }
            String key = reactorRoot.relativize(moduleDir).toString().replace('\\', '/');
            if (key.isEmpty()) {
                continue;
            }
            modules.put(key, new ClassFileImporter().importPaths(classes));
        }
        return modules;
    }

    private static List<Path> pomFiles(Path reactorRoot) {
        try (Stream<Path> walk = Files.walk(reactorRoot)) {
            return walk
                .filter(path -> path.getFileName() != null && path.getFileName().toString().equals("pom.xml"))
                .filter(path -> !reactorRoot.relativize(path).toString().contains("target/"))
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

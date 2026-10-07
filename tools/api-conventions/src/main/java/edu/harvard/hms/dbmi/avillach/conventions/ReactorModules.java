package edu.harvard.hms.dbmi.avillach.conventions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
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
 *
 * <p>A supertype declared in another reactor module, such as a library's shared base class, is still
 * resolved: every module's {@code target/classes} sits on the context class loader ArchUnit resolves
 * missing types from during the import, so a hierarchy check sees the whole chain rather than a stub.
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
        Map<String, Path> classDirectories = new LinkedHashMap<>();
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
            classDirectories.put(key, classes);
        }
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (URLClassLoader reactor = new URLClassLoader(urls(classDirectories.values()), previous)) {
            thread.setContextClassLoader(reactor);
            Map<String, JavaClasses> modules = new LinkedHashMap<>();
            classDirectories.forEach((key, classes) -> modules.put(key, new ClassFileImporter().importPaths(classes)));
            return modules;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static URL[] urls(Collection<Path> directories) {
        try {
            URL[] urls = new URL[directories.size()];
            int i = 0;
            for (Path directory : directories) {
                urls[i++] = directory.toUri().toURL();
            }
            return urls;
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
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

package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.tngtech.archunit.core.domain.JavaClasses;

/**
 * The two rules that keep the registry honest.
 *
 * <p>Together they make it self-maintaining. An exclusion list nobody is forced to update is how a check
 * like this rots: a new service arrives, nobody adds it, and the rules silently cover less than anyone
 * believes.
 */
public final class RegistryRules {

    private RegistryRules() {}

    /**
     * R0a: every module the registry marks documented actually yields a controller. Catches a stale entry,
     * and catches running against a tree that was never compiled, which would otherwise pass vacuously.
     *
     * @param registry the parsed registry
     * @param modules module path mapped to that module's imported classes
     * @return one violation per documented module that yielded nothing
     */
    public static List<String> documentedModulesYieldControllers(ModuleRegistry registry, Map<String, JavaClasses> modules) {
        List<String> violations = new ArrayList<>();
        for (String module : registry.documentedModules()) {
            JavaClasses classes = modules.get(module);
            if (classes == null) {
                violations.add(module + " is documented but was not compiled (run make build)");
            } else if (Controllers.of(classes).isEmpty()) {
                violations.add(module + " is documented but declares no controller (stale registry entry?)");
            }
        }
        return violations;
    }

    /**
     * R0b: every module that declares a controller appears in the registry. A new service fails the build
     * until somebody classifies it deliberately.
     *
     * @param registry the parsed registry
     * @param modules module path mapped to that module's imported classes
     * @return one violation per unregistered module that declares a controller
     */
    public static List<String> controllerModulesAreRegistered(ModuleRegistry registry, Map<String, JavaClasses> modules) {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, JavaClasses> module : modules.entrySet()) {
            if (registry.contains(module.getKey()) || Controllers.of(module.getValue()).isEmpty()) {
                continue;
            }
            violations.add(
                module.getKey() + " declares a controller but is not in api-modules.properties"
                    + " (add it as documented, or as internal with a reason)"
            );
        }
        return violations;
    }
}

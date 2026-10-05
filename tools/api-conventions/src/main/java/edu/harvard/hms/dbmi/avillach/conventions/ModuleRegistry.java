package edu.harvard.hms.dbmi.avillach.conventions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The committed decision about which reactor modules carry a documented client API. Scope belongs to a
 * module rather than to each controller inside it, so a service that is never exposed to clients is
 * excluded once. An internal entry must state why, because an unexplained exclusion outlives the person
 * who added it.
 */
public final class ModuleRegistry {

    private static final String RESOURCE = "api-modules.properties";

    private final Map<String, ModuleScope> scopes;

    private ModuleRegistry(Map<String, ModuleScope> scopes) {
        this.scopes = scopes;
    }

    /**
     * Reads the registry shipped on the classpath.
     *
     * @return the parsed registry
     * @throws IllegalStateException if the resource is absent
     */
    public static ModuleRegistry load() {
        InputStream stream = ModuleRegistry.class.getClassLoader().getResourceAsStream(RESOURCE);
        if (stream == null) {
            throw new IllegalStateException(RESOURCE + " is not on the classpath");
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return parse(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Parses registry text.
     *
     * @param source registry text, one {@code module = scope} entry per line
     * @return the parsed registry
     * @throws IllegalArgumentException on an unknown scope, a bare internal with no reason, or a duplicate module
     */
    public static ModuleRegistry parse(Reader source) {
        Map<String, ModuleScope> scopes = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(source)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator < 0) {
                    throw new IllegalArgumentException("registry line has no '=': " + trimmed);
                }
                String module = trimmed.substring(0, separator).trim();
                String value = trimmed.substring(separator + 1).trim();
                if (scopes.containsKey(module)) {
                    throw new IllegalArgumentException("duplicate registry entry for " + module);
                }
                scopes.put(module, parseScope(module, value));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new ModuleRegistry(scopes);
    }

    private static ModuleScope parseScope(String module, String value) {
        int colon = value.indexOf(':');
        String keyword = colon < 0 ? value : value.substring(0, colon).trim();
        String reason = colon < 0 ? "" : value.substring(colon + 1).trim();
        return switch (keyword.toLowerCase(Locale.ROOT)) {
            case "documented" -> ModuleScope.DOCUMENTED;
            case "internal" -> requireReason(module, reason);
            default -> throw new IllegalArgumentException("unknown scope '" + value + "' for " + module);
        };
    }

    private static ModuleScope requireReason(String module, String reason) {
        if (reason.isBlank()) {
            throw new IllegalArgumentException("internal entry for " + module + " must state a reason after a colon");
        }
        return ModuleScope.INTERNAL;
    }

    /**
     * @param modulePath a module path relative to the reactor root
     * @return that module's scope, empty when it is not listed
     */
    public Optional<ModuleScope> scopeOf(String modulePath) {
        return Optional.ofNullable(scopes.get(modulePath));
    }

    /**
     * @param modulePath a module path relative to the reactor root
     * @return whether the registry lists it at all
     */
    public boolean contains(String modulePath) {
        return scopes.containsKey(modulePath);
    }

    /** @return every module path marked documented, in registry order */
    public Set<String> documentedModules() {
        return scopes.entrySet().stream()
            .filter(entry -> entry.getValue() == ModuleScope.DOCUMENTED)
            .map(Map.Entry::getKey)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}

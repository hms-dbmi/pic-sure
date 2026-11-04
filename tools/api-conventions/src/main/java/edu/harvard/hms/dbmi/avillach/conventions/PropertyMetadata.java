package edu.harvard.hms.dbmi.avillach.conventions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A module's Spring Boot configuration metadata: the property names it declares and what is wrong with the
 * hand-written part. Both files are read from the compiled classes directory, where the build copies them.
 *
 * @param declared every property name declared by either metadata file
 * @param problems one sentence fragment per defect in the hand-written file, empty when it is complete or absent
 */
public record PropertyMetadata(Set<String> declared, List<String> problems) {

    /** The hand-written file, kept in each module's {@code src/main/resources}. */
    public static final String ADDITIONAL = "META-INF/additional-spring-configuration-metadata.json";

    /** The file {@code spring-boot-configuration-processor} generates, when a module runs it. */
    public static final String GENERATED = "META-INF/spring-configuration-metadata.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param classesDir a module's {@code target/classes} directory
     * @return the declared names from both files, and the problems found in {@link #ADDITIONAL}. A file that is
     *     absent declares nothing and has no problems.
     */
    public static PropertyMetadata load(Path classesDir) {
        Set<String> declared = new HashSet<>();
        List<String> problems = new ArrayList<>();
        read(classesDir.resolve(GENERATED), declared, new ArrayList<>());
        read(classesDir.resolve(ADDITIONAL), declared, problems);
        return new PropertyMetadata(Set.copyOf(declared), List.copyOf(problems));
    }

    private static void read(Path file, Set<String> declared, List<String> problems) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        JsonNode root;
        try {
            root = JSON.readTree(file.toFile());
        } catch (JsonProcessingException e) {
            problems.add("is not valid JSON: " + e.getOriginalMessage());
            return;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        JsonNode properties = root == null ? null : root.get("properties");
        if (properties == null || !properties.isArray()) {
            problems.add("has no \"properties\" array");
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < properties.size(); index++) {
            JsonNode property = properties.get(index);
            String name = text(property, "name");
            String label = name.isBlank() ? "property " + index : "'" + name + "'";
            if (name.isBlank()) {
                problems.add("has property " + index + " with no name");
            } else if (!seen.add(name)) {
                problems.add("declares '" + name + "' more than once");
            }
            if (text(property, "type").isBlank()) {
                problems.add("gives " + label + " no type");
            }
            if (text(property, "description").isBlank()) {
                problems.add("gives " + label + " no description");
            }
            if (!name.isBlank()) {
                declared.add(name);
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : "";
    }
}

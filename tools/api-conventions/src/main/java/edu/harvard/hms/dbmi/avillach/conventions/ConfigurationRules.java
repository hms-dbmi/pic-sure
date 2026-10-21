package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaParameter;

/**
 * The configuration rules, as pure functions over imported classes. They check how code asks Spring for
 * configuration values, which the compiler cannot. An {@code @Value} string is only text until Spring
 * resolves it at startup.
 */
public final class ConfigurationRules {

    public static final String VALUE = "org.springframework.beans.factory.annotation.Value";

    private ConfigurationRules() {}

    /**
     * One {@code @Value} annotation and where it sits.
     *
     * @param location the module, class and member, with a parameter's zero-based index when it is on one
     * @param value the raw annotation value
     */
    public record ValueSite(String location, String value) {

        String describe() {
            return location + " @Value(\"" + value + "\")";
        }
    }

    /**
     * {@code value-strings-well-formed}: every {@code @Value} string parses the way {@link ValueString} reads
     * it. Each placeholder and expression is closed, no closing brace is left over, no expression is empty, and
     * every placeholder names one plain key made of letters, digits, {@code .}, {@code -}, {@code _} and
     * {@code []}. Spring treats a malformed placeholder as literal text, so <code>@Value("${key")</code> starts
     * cleanly and injects the string <code>${key</code> instead of the configured value.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported classes
     * @return one violation per malformed {@code @Value} string, listing each of its problems
     */
    public static List<String> valueStringsAreWellFormed(String module, JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (ValueSite site : valueSites(module, classes)) {
            ValueString parsed = ValueString.parse(site.value());
            if (!parsed.wellFormed()) {
                violations.add(site.describe() + " " + String.join("; ", parsed.problems()));
            }
        }
        return violations;
    }

    /**
     * {@code value-keys-declared}: every key a module's {@code @Value} strings read, defaults and nested keys
     * included, is declared in that module's configuration metadata. Spring resolves an undeclared key just the
     * same, so the check exists to keep one list per module of every setting it reads.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported classes
     * @param declared the property names the module's metadata declares
     * @return one violation per undeclared key at each site that reads it
     */
    public static List<String> valueKeysAreDeclared(String module, JavaClasses classes, Set<String> declared) {
        List<String> violations = new ArrayList<>();
        for (ValueSite site : valueSites(module, classes)) {
            for (String key : ValueString.parse(site.value()).keys()) {
                if (!declared.contains(key)) {
                    violations.add(site.describe() + " reads '" + key + "', which " + PropertyMetadata.ADDITIONAL + " does not declare");
                }
            }
        }
        return violations;
    }

    /**
     * {@code property-metadata-complete}: every entry in a module's hand-written metadata file has a name, a
     * type and a description, and no name appears twice. A declaration with no description documents nothing.
     *
     * @param module the module path, used in the violation text
     * @param metadata that module's loaded metadata
     * @return one violation per problem {@link PropertyMetadata#load} found
     */
    public static List<String> metadataIsComplete(String module, PropertyMetadata metadata) {
        return metadata.problems().stream().map(problem -> module + " :: " + PropertyMetadata.ADDITIONAL + " " + problem).toList();
    }

    /**
     * @param module the module path, used in each site's location
     * @param classes that module's imported classes
     * @return every {@code @Value} on a field, method, constructor parameter or method parameter, in a stable
     *     order
     */
    public static List<ValueSite> valueSites(String module, JavaClasses classes) {
        List<ValueSite> sites = new ArrayList<>();
        for (JavaClass type : classes.stream().sorted(Comparator.comparing(JavaClass::getName)).toList()) {
            for (JavaField field : type.getFields().stream().sorted(Comparator.comparing(JavaField::getName)).toList()) {
                value(field.getAnnotations()).ifPresent(value -> sites.add(new ValueSite(SwaggerRules.at(module, type, field.getName()), value)));
            }
            for (JavaCodeUnit unit : type.getCodeUnits().stream().sorted(Comparator.comparing(JavaCodeUnit::getFullName)).toList()) {
                value(unit.getAnnotations()).ifPresent(value -> sites.add(new ValueSite(SwaggerRules.at(module, type, unit.getName()), value)));
                for (JavaParameter parameter : unit.getParameters()) {
                    value(parameter.getAnnotations()).ifPresent(
                        value -> sites.add(new ValueSite(SwaggerRules.at(module, type, unit.getName()) + " parameter " + parameter.getIndex(), value))
                    );
                }
            }
        }
        return sites;
    }

    private static Optional<String> value(Set<? extends JavaAnnotation<?>> annotations) {
        for (JavaAnnotation<?> annotation : annotations) {
            if (annotation.getRawType().getName().equals(VALUE)) {
                return Optional.of(Annotations.string(annotation, "value").orElse(""));
            }
        }
        return Optional.empty();
    }
}

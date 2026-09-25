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

    private static final List<String> OPENERS = List.of("${", "#{");
    private static final String CLOSE = "}";

    private ConfigurationRules() {}

    /**
     * R18: every {@code @Value} string on a field, constructor parameter or method parameter closes each
     * placeholder and expression it opens, meaning it has as many closing braces as <code>${</code> and
     * <code>#{</code> openings together. Expressions count because they close with the same brace, as in
     * <code>${DEST_IP:#{null}}</code>. Spring treats an unterminated placeholder as literal text, so
     * <code>@Value("${key")</code> starts cleanly and injects the string <code>${key</code> instead of the
     * configured value.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported classes
     * @return one violation per unbalanced {@code @Value} string, naming the class, the member and, for a
     *     parameter, its zero-based index
     */
    public static List<String> valuePlaceholdersAreClosed(String module, JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass type : classes.stream().sorted(Comparator.comparing(JavaClass::getName)).toList()) {
            for (JavaField field : type.getFields().stream().sorted(Comparator.comparing(JavaField::getName)).toList()) {
                unbalanced(field.getAnnotations()).ifPresent(
                    value -> violations.add(SwaggerRules.at(module, type, field.getName()) + describe(value))
                );
            }
            for (JavaCodeUnit unit : sortedCodeUnits(type)) {
                for (JavaParameter parameter : unit.getParameters()) {
                    unbalanced(parameter.getAnnotations()).ifPresent(
                        value -> violations.add(
                            SwaggerRules.at(module, type, unit.getName()) + " parameter " + parameter.getIndex() + describe(value)
                        )
                    );
                }
            }
        }
        return violations;
    }

    private static List<JavaCodeUnit> sortedCodeUnits(JavaClass type) {
        return type.getCodeUnits().stream().sorted(Comparator.comparing(JavaCodeUnit::getFullName)).toList();
    }

    private static Optional<String> unbalanced(Set<? extends JavaAnnotation<?>> annotations) {
        for (JavaAnnotation<?> annotation : annotations) {
            if (!annotation.getRawType().getName().equals(VALUE)) {
                continue;
            }
            String value = Annotations.string(annotation, "value").orElse("");
            if (openings(value) != occurrences(value, CLOSE)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private static String describe(String value) {
        return " @Value(\"" + value + "\") opens " + openings(value) + " placeholder(s) or expression(s) but has "
            + occurrences(value, CLOSE) + " closing brace(s)";
    }

    private static int openings(String text) {
        return OPENERS.stream().mapToInt(opener -> occurrences(text, opener)).sum();
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        for (int at = text.indexOf(token); at >= 0; at = text.indexOf(token, at + token.length())) {
            count++;
        }
        return count;
    }
}

package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;

/**
 * The JPA mapping rules, as pure functions over imported classes. They hold persisted columns to a form
 * that survives the Java code changing underneath the data.
 */
public final class PersistenceRules {

    public static final String ENTITY = "jakarta.persistence.Entity";
    public static final String MAPPED_SUPERCLASS = "jakarta.persistence.MappedSuperclass";
    public static final String EMBEDDABLE = "jakarta.persistence.Embeddable";
    public static final String TRANSIENT = "jakarta.persistence.Transient";
    public static final String ENUMERATED = "jakarta.persistence.Enumerated";
    public static final String CONVERT = "jakarta.persistence.Convert";

    private static final List<String> PERSISTENT_TYPES = List.of(ENTITY, MAPPED_SUPERCLASS, EMBEDDABLE);

    private PersistenceRules() {}

    /**
     * R19: every persisted enum field is stored by name or through a converter. A field of enum type in
     * an {@code @Entity}, {@code @MappedSuperclass} or {@code @Embeddable} class carries
     * {@code @Enumerated(EnumType.STRING)} or {@code @Convert}. A bare field and a bare {@code @Enumerated}
     * both default to the ordinal, so reordering or inserting a constant silently remaps stored rows, and a
     * stored ordinal past the end of the constant list fails every read of that row.
     *
     * <p>Mapped superclasses and embeddables are included because their fields are persisted in the
     * owning entity's table under the same default. Static fields, Java {@code transient} fields and
     * {@code @Transient} fields are not persisted and are skipped. The rule reads field annotations only,
     * which matches the field access every entity in this reactor uses. A converter registered with
     * {@code autoApply = true} is not recognised; name it with {@code @Convert} on the field.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported classes
     * @param reactorEnums the names of enum types compiled anywhere in the reactor, because a field whose
     *     enum lives in another module arrives here as an unresolved type that does not report itself as
     *     an enum
     * @return one violation per enum field stored by ordinal
     */
    public static List<String> enumsStoredByName(String module, JavaClasses classes, Set<String> reactorEnums) {
        List<String> violations = new ArrayList<>();
        for (JavaClass type : sorted(classes)) {
            if (PERSISTENT_TYPES.stream().noneMatch(annotation -> Annotations.has(type, annotation))) {
                continue;
            }
            for (JavaField field : sortedFields(type)) {
                if (isPersisted(field) && isEnum(field, reactorEnums) && !storedByName(field)) {
                    String at = SwaggerRules.at(module, type, field.getName());
                    violations.add(at + " stores enum " + field.getRawType().getSimpleName()
                        + " by ordinal; add @Enumerated(EnumType.STRING) or @Convert");
                }
            }
        }
        return violations;
    }

    /**
     * Collects the names of every enum type compiled in the reactor.
     *
     * @param modules every imported module
     * @return the fully qualified names of the enums
     */
    public static Set<String> enumTypes(Map<String, JavaClasses> modules) {
        Set<String> names = new TreeSet<>();
        for (JavaClasses classes : modules.values()) {
            classes.stream().filter(JavaClass::isEnum).map(JavaClass::getName).forEach(names::add);
        }
        return names;
    }

    private static boolean isPersisted(JavaField field) {
        Set<JavaModifier> modifiers = field.getModifiers();
        return !modifiers.contains(JavaModifier.STATIC)
            && !modifiers.contains(JavaModifier.TRANSIENT)
            && !Annotations.has(field, TRANSIENT);
    }

    private static boolean isEnum(JavaField field, Set<String> reactorEnums) {
        return field.getRawType().isEnum() || reactorEnums.contains(field.getRawType().getName());
    }

    private static boolean storedByName(JavaField field) {
        Optional<JavaAnnotation<?>> convert = Annotations.get(field, CONVERT);
        if (convert.isPresent() && !Boolean.TRUE.equals(convert.get().getProperties().get("disableConversion"))) {
            return true;
        }
        return Annotations.get(field, ENUMERATED)
            .map(annotation -> annotation.getProperties().get("value"))
            .filter(JavaEnumConstant.class::isInstance)
            .map(value -> ((JavaEnumConstant) value).name().equals("STRING"))
            .orElse(false);
    }

    private static List<JavaClass> sorted(JavaClasses classes) {
        return classes.stream().sorted(Comparator.comparing(JavaClass::getName)).toList();
    }

    private static List<JavaField> sortedFields(JavaClass type) {
        return type.getFields().stream().sorted(Comparator.comparing(JavaField::getName)).toList();
    }
}

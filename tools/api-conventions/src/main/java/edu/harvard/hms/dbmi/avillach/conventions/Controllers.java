package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.Comparator;
import java.util.List;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;

/**
 * Picks controllers and their handler methods out of imported classes.
 *
 * <p>Spring's ControllerAdvice is meta-annotated with Component rather than Controller, so exception
 * handler classes fall outside this definition without needing an exemption list.
 */
public final class Controllers {

    private static final List<String> MAPPING_ANNOTATIONS = List.of(
        "org.springframework.web.bind.annotation.RequestMapping",
        "org.springframework.web.bind.annotation.GetMapping",
        "org.springframework.web.bind.annotation.PostMapping",
        "org.springframework.web.bind.annotation.PutMapping",
        "org.springframework.web.bind.annotation.DeleteMapping",
        "org.springframework.web.bind.annotation.PatchMapping"
    );

    private Controllers() {}

    /**
     * @param classes imported classes for one module
     * @return every controller among them, ordered by fully qualified name
     */
    public static List<JavaClass> of(JavaClasses classes) {
        return classes.stream()
            .filter(type -> Annotations.has(type, Annotations.CONTROLLER) || Annotations.has(type, Annotations.REST_CONTROLLER))
            .sorted(Comparator.comparing(JavaClass::getName))
            .toList();
    }

    /**
     * @param controller a controller class
     * @return its declared public methods that carry a request mapping, ordered by name. Inherited
     *     methods are out of scope: no controller in this reactor extends a base class.
     */
    public static List<JavaMethod> handlerMethods(JavaClass controller) {
        return controller.getMethods().stream()
            .filter(method -> method.getModifiers().contains(JavaModifier.PUBLIC))
            .filter(Controllers::isMapped)
            .sorted(Comparator.comparing(JavaMethod::getName))
            .toList();
    }

    private static boolean isMapped(JavaMethod method) {
        return MAPPING_ANNOTATIONS.stream().anyMatch(name -> Annotations.has(method, name));
    }
}

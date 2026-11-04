package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameter;

/**
 * The test code rules, as pure functions over a module's imported test classes. A test class is any
 * class whose outermost declaring class has a simple name ending in {@code Test}, {@code Tests} or
 * {@code IT}. Mockito and JUnit types are matched by fully qualified name, so the module needs neither
 * on its classpath and ArchUnit resolves them as stubs.
 */
public final class TestConventionRules {

    public static final String MOCK = "org.mockito.Mock";
    public static final String NESTED = "org.junit.jupiter.api.Nested";
    static final String JUNIT_PACKAGE_PREFIX = "org.junit.";

    private static final List<String> TEST_CLASS_SUFFIXES = List.of("Test", "Tests", "IT");
    private static final Set<JavaModifier> OUT_OF_SCOPE_METHOD_MODIFIERS = Set.of(
        JavaModifier.PRIVATE, JavaModifier.STATIC, JavaModifier.SYNTHETIC, JavaModifier.BRIDGE, JavaModifier.ABSTRACT
    );

    private TestConventionRules() {}

    /**
     * {@code no-constructor-parameter-mocks}: no constructor of a test class takes a parameter annotated
     * {@code @Mock}. JUnit instantiates the class before {@code MockitoExtension} opens its session for the
     * test, so a mock handed to the constructor is created outside strict stubbing: a stub on it that the
     * test never uses, or that it calls with other arguments, is not reported. A {@code @Mock} field or a
     * {@code @Mock} parameter on a lifecycle or test method is created inside the session and passes.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported test classes
     * @return one violation per constructor with a mock parameter, empty when none has one
     */
    public static List<String> noConstructorParameterMocks(String module, JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass type : classes) {
            if (!isTestClass(type)) {
                continue;
            }
            for (JavaConstructor constructor : type.getConstructors()) {
                if (constructor.getParameters().stream().anyMatch(TestConventionRules::isMock)) {
                    violations.add(
                        at(module, type, JavaConstructor.CONSTRUCTOR_NAME)
                            + " takes a @Mock constructor parameter, created before the Mockito session opens and outside strict stubbing"
                    );
                }
            }
        }
        Collections.sort(violations);
        return violations;
    }

    /**
     * {@code test-methods-are-annotated}: in a top-level test class, and in any {@code @Nested} inner class
     * of one, every non-private, non-static, non-abstract instance method that returns {@code void} and takes
     * no parameters carries an annotation from the {@code org.junit} package tree, directly or one
     * meta-level up. That shape is a test method in every JUnit version, so one without an annotation is a
     * test no runner executes: it compiles, it reads as covered, and it never fails. A method that overrides
     * a supertype method is exempt, since its caller is the supertype. Inner classes that are not
     * {@code @Nested} are fixtures and are out of scope.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported test classes
     * @return one violation per unannotated test-shaped method, empty when every one is annotated
     */
    public static List<String> testMethodsAreAnnotated(String module, JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass type : classes) {
            if (!holdsTestMethods(type)) {
                continue;
            }
            for (JavaMethod method : type.getMethods()) {
                if (isTestShaped(method) && !carriesJunitAnnotation(method) && !overridesSupertypeMethod(method)) {
                    violations.add(
                        at(module, type, method.getName())
                            + " is a void method with no parameters and no JUnit annotation, so no runner ever executes it"
                    );
                }
            }
        }
        Collections.sort(violations);
        return violations;
    }

    static boolean isTestClass(JavaClass type) {
        if (type.isAnonymousClass() || type.isInterface() || type.isAnnotation()) {
            return false;
        }
        String outermostName = outermost(type).getSimpleName();
        return TEST_CLASS_SUFFIXES.stream().anyMatch(outermostName::endsWith);
    }

    private static boolean holdsTestMethods(JavaClass type) {
        if (!isTestClass(type)) {
            return false;
        }
        return type.getEnclosingClass().isEmpty() || type.isAnnotatedWith(NESTED);
    }

    private static boolean isTestShaped(JavaMethod method) {
        if (method.getModifiers().stream().anyMatch(OUT_OF_SCOPE_METHOD_MODIFIERS::contains)) {
            return false;
        }
        return method.getRawReturnType().getName().equals("void") && method.getRawParameterTypes().isEmpty();
    }

    private static boolean carriesJunitAnnotation(JavaMethod method) {
        for (JavaAnnotation<?> annotation : method.getAnnotations()) {
            if (isJunit(annotation)) {
                return true;
            }
            for (JavaAnnotation<?> meta : annotation.getRawType().getAnnotations()) {
                if (isJunit(meta)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isJunit(JavaAnnotation<?> annotation) {
        return annotation.getRawType().getName().startsWith(JUNIT_PACKAGE_PREFIX);
    }

    private static boolean overridesSupertypeMethod(JavaMethod method) {
        JavaClass owner = method.getOwner();
        String[] parameterTypes = method.getRawParameterTypes().stream().map(JavaClass::getName).toArray(String[]::new);
        List<JavaClass> supertypes = new ArrayList<>(owner.getAllRawSuperclasses());
        supertypes.addAll(owner.getAllRawInterfaces());
        return supertypes.stream().anyMatch(supertype -> supertype.tryGetMethod(method.getName(), parameterTypes).isPresent());
    }

    private static boolean isMock(JavaParameter parameter) {
        return parameter.isAnnotatedWith(MOCK);
    }

    private static JavaClass outermost(JavaClass type) {
        JavaClass current = type;
        while (current.getEnclosingClass().isPresent()) {
            current = current.getEnclosingClass().get();
        }
        return current;
    }

    private static String at(String module, JavaClass type, String member) {
        String packagePrefix = type.getPackageName().isEmpty() ? "" : type.getPackageName() + ".";
        String nestedName = type.getName().substring(packagePrefix.length()).replace('$', '.');
        return module + " :: " + nestedName + "#" + member;
    }
}

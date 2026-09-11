package edu.harvard.hms.dbmi.avillach.auth.rest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the mass-assignment fix for the whole {@code rest} package at once: no controller method may take a JPA entity as a parameter.
 * Binding an entity lets a caller set any column it exposes a setter for, including the inherited {@code uuid} and server-owned values such
 * as {@code Application.token} or {@code User.subject}/{@code passport}/{@code acceptedTOS}. Controllers must accept an allowlisted request
 * record from {@code auth.model.request} instead.
 *
 * <p>Every parameter is checked, not only those annotated {@code @RequestBody}. Spring will model-bind a parameter carrying
 * {@code @ModelAttribute} or no annotation at all, which reaches the same setters by a different route, so restricting the scan to
 * {@code @RequestBody} would leave a new endpoint free to reintroduce the finding in a shape the test never looks at.
 */
class RequestBodyEntityBindingTest {

    private static final String CONTROLLER_PACKAGE = "edu.harvard.hms.dbmi.avillach.auth.rest";
    private static final String ENTITY_PACKAGE = "edu.harvard.hms.dbmi.avillach.auth.entity";

    /**
     * Endpoints still awaiting conversion, while the fix lands one domain at a time. Each domain branch deletes its own two lines, so this
     * set only ever shrinks; the final branch removes it along with the two assertions that reference it. Entries are one per line, and the
     * array initializer permits a trailing comma on every one, so sibling branches deleting different domains always merge and always leave
     * syntactically valid code, whichever domain lands last.
     */
    private static final Set<String> AWAITING_REMEDIATION = Set.of(new String[] {
        "AccessRuleController#addAccessRule binds AccessRule",
        "AccessRuleController#updateAccessRule binds AccessRule",
        "ApplicationController#addApplication binds Application",
        "ApplicationController#updateApplication binds Application",
        "ConnectionWebController#addConnection binds Connection",
        "ConnectionWebController#updateConnection binds Connection",
        "PrivilegeController#addPrivilege binds Privilege",
        "PrivilegeController#updatePrivilege binds Privilege",
        "RoleController#addRole binds Role",
        "RoleController#updateRole binds Role",
        "UserController#addUser binds User",
        "UserController#updateUser binds User",
        "UserMetadataMappingWebController#addMapping binds UserMetadataMapping",
        "UserMetadataMappingWebController#updateMapping binds UserMetadataMapping",
    });

    @Test
    void onlyEndpointsAwaitingRemediationBindAPersistenceEntity() {
        Set<String> found = new TreeSet<>(entityBindingEndpoints());

        Set<String> unlisted = new TreeSet<>(found);
        unlisted.removeAll(AWAITING_REMEDIATION);
        assertTrue(unlisted.isEmpty(), "Request bodies must not bind JPA entities: " + unlisted);

        Set<String> alreadyFixed = new TreeSet<>(AWAITING_REMEDIATION);
        alreadyFixed.removeAll(found);
        assertTrue(alreadyFixed.isEmpty(), "These no longer bind an entity; delete them from AWAITING_REMEDIATION: " + alreadyFixed);
    }

    private static List<String> entityBindingEndpoints() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    for (Class<?> bound : boundTypes(parameter)) {
                        if (bound.getName().startsWith(ENTITY_PACKAGE)) {
                            offenders.add(controller.getSimpleName() + "#" + method.getName() + " binds " + bound.getSimpleName());
                        }
                    }
                }
            }
        }
        return offenders;
    }

    private static List<Class<?>> boundTypes(Parameter parameter) {
        List<Class<?>> types = new ArrayList<>();
        types.add(parameter.getType());
        if (parameter.getParameterizedType() instanceof ParameterizedType parameterized) {
            for (Type argument : parameterized.getActualTypeArguments()) {
                if (argument instanceof Class<?> argumentClass) {
                    types.add(argumentClass);
                }
            }
        }
        return types;
    }

    private static List<Class<?>> controllers() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<BeanDefinition> candidates = scanner.findCandidateComponents(CONTROLLER_PACKAGE);
        assertTrue(candidates.size() >= 7, "expected to scan the auth controllers, found " + candidates.size());

        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition candidate : candidates) {
            try {
                classes.add(Class.forName(candidate.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new AssertionError("scanned a controller that will not load: " + candidate.getBeanClassName(), e);
            }
        }
        return classes;
    }
}

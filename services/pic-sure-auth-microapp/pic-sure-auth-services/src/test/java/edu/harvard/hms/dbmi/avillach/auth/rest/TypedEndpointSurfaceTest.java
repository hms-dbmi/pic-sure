package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.entity.UserMetadataMapping;

/**
 * A generated OpenAPI document is only as honest as the signatures it is generated from, and {@code ResponseEntity<?>} describes nothing at
 * all. This pins the whole PSAMA surface: no handler may return a wildcard, and the admin CRUD handlers must name the concrete entity type
 * they answer with.
 */
class TypedEndpointSurfaceTest {

    private static final List<Class<?>> CONTROLLERS = List.of(
        AccessRuleController.class, ApplicationController.class, AuthenticationController.class, CacheController.class,
        ConnectionWebController.class, OpenAccessController.class, PrivilegeController.class, RoleController.class,
        StudyAccessController.class, TermsOfServiceController.class, TokenController.class, UserController.class,
        UserMetadataMappingWebController.class
    );

    /** Bare bodies need {@code @ResponseBody} semantics; a plain {@code @Controller} would treat a returned String as a view name. */
    @Test
    void everyControllerWritesItsReturnValueAsTheBody() {
        for (Class<?> controller : CONTROLLERS) {
            assertNotNull(controller.getAnnotation(RestController.class), controller.getSimpleName() + " must be a @RestController");
        }
    }

    @Test
    void noHandlerReturnsAWildcard() {
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                if (method.getAnnotationsByType(RequestMapping.class).length == 0 && !isMapped(method)) {
                    continue;
                }
                assertNoWildcard(controller, method, method.getGenericReturnType());
            }
        }
    }

    private static boolean isMapped(Method method) {
        return java.util.Arrays.stream(method.getAnnotations())
            .anyMatch(annotation -> annotation.annotationType().getAnnotation(RequestMapping.class) != null);
    }

    private static void assertNoWildcard(Class<?> controller, Method method, Type type) {
        if (type instanceof WildcardType) {
            fail(controller.getSimpleName() + "." + method.getName() + " returns a wildcard type: " + method.getGenericReturnType());
        }
        if (type instanceof ParameterizedType parameterized) {
            for (Type argument : parameterized.getActualTypeArguments()) {
                assertNoWildcard(controller, method, argument);
            }
        }
    }

    /** The entity types stay on the wire for now; extracting admin DTOs is a recorded follow-up. What must not stay is the wildcard. */
    @Test
    void adminCrudNamesTheEntityItAnswersWith() throws Exception {
        assertListOf(User.class, UserController.class.getMethod("getUserAll"));
        assertListOf(Role.class, RoleController.class.getMethod("getRoleAll"));
        assertListOf(Privilege.class, PrivilegeController.class.getMethod("getPrivilegeAll"));
        assertListOf(AccessRule.class, AccessRuleController.class.getMethod("getAccessRuleAll"));
        assertListOf(Application.class, ApplicationController.class.getMethod("getApplicationAll"));
        assertListOf(Connection.class, ConnectionWebController.class.getMethod("getAllConnections"));
        assertListOf(UserMetadataMapping.class, UserMetadataMappingWebController.class.getMethod("getAllMappings"));

        assertEquals(
            User.class,
            UserController.class.getMethod("getUserById", String.class, jakarta.servlet.http.HttpServletRequest.class).getReturnType()
        );
        assertEquals(Role.class, RoleController.class.getMethod("getRoleById", String.class).getReturnType());
    }

    private static void assertListOf(Class<?> element, Method method) {
        assertEquals(List.class, method.getReturnType(), method + " must answer with a List");
        ParameterizedType type = (ParameterizedType) method.getGenericReturnType();
        assertEquals(element, type.getActualTypeArguments()[0], method + " must name its element type");
    }

    /** {@code GET /accessRule/allTypes} takes no body; demanding a JSON content type on it made it unreachable from a plain GET. */
    @Test
    void allTypesDoesNotDemandARequestContentType() throws Exception {
        org.springframework.web.bind.annotation.GetMapping mapping =
            AccessRuleController.class.getMethod("getAllTypes").getAnnotation(org.springframework.web.bind.annotation.GetMapping.class);
        assertEquals(0, mapping.consumes().length, "a GET with no body must not declare consumes");
    }

    /** The envelope is gone from the codebase, not merely unused. */
    @Test
    void theResponseEnvelopeIsDeleted() {
        for (
            String name : List.of(
                "edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse",
                "edu.harvard.hms.dbmi.avillach.auth.model.response.PicSureResponseBody"
            )
        ) {
            try {
                Class.forName(name);
                fail(name + " must be deleted");
            } catch (ClassNotFoundException expected) {
                assertTrue(true);
            }
        }
    }

    /** Nothing on the surface may answer with a raw {@code Object}. */
    @Test
    void cacheInspectionIsTyped() throws Exception {
        assertEquals(
            edu.harvard.hms.dbmi.avillach.auth.model.response.CacheContents.class,
            CacheController.class.getMethod("getCache", String.class).getReturnType()
        );
    }

    /** ResponseEntity is still allowed where a status varies, but it must name its body type. */
    @Test
    void responseEntityUsesAreParameterized() {
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                if (method.getReturnType() == ResponseEntity.class) {
                    assertTrue(
                        method.getGenericReturnType() instanceof ParameterizedType,
                        controller.getSimpleName() + "." + method.getName() + " returns a raw ResponseEntity"
                    );
                }
            }
        }
    }
}

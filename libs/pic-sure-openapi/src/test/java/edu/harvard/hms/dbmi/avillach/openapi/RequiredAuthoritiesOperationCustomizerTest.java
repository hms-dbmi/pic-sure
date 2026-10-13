package edu.harvard.hms.dbmi.avillach.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;

import io.swagger.v3.oas.models.Operation;

/**
 * The sentence the customizer appends, for every guard form the api-conventions rules accept, some they reject, and each
 * {@link PublicEndpoint} level.
 */
class RequiredAuthoritiesOperationCustomizerTest {

    private final RequiredAuthoritiesOperationCustomizer customizer = new RequiredAuthoritiesOperationCustomizer();

    static class GuardedController {
        @PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")
        public String adminOrSuperAdmin() {
            return "";
        }

        @PreAuthorize("hasAnyAuthority('SUPER_ADMIN','ADMIN')")
        public String superAdminFirst() {
            return "";
        }

        @PreAuthorize("hasAnyAuthority('ADMIN')")
        public String adminOnly() {
            return "";
        }

        @PreAuthorize("hasAuthority('PRIV_DATA_ADMIN')")
        public String singlePrivilege() {
            return "";
        }

        @PreAuthorize("hasRole('ADMIN')")
        public String roleExpression() {
            return "";
        }

        public String unguarded() {
            return "";
        }

        @PublicEndpoint(PublicEndpoint.Access.ANONYMOUS)
        public String anonymous() {
            return "";
        }

        @PublicEndpoint(PublicEndpoint.Access.AUTHENTICATED)
        public String authenticated() {
            return "";
        }

        @PreAuthorize("hasAuthority('SUPER_ADMIN')")
        @PublicEndpoint(PublicEndpoint.Access.ANONYMOUS)
        public String guardedAndMarked() {
            return "";
        }
    }

    private Operation customize(Operation operation, String handler) throws NoSuchMethodException {
        GuardedController controller = new GuardedController();
        return customizer.customize(operation, new HandlerMethod(controller, GuardedController.class.getMethod(handler)));
    }

    @Test
    void authoritiesFollowTheExistingDescription() throws Exception {
        Operation operation = customize(new Operation().description("GET a list of things"), "adminOrSuperAdmin");

        assertThat(operation.getDescription()).isEqualTo("GET a list of things\n\nRequired authorities: ADMIN, SUPER_ADMIN.");
    }

    @Test
    void authoritiesBecomeTheWholeDescriptionWhenThereIsNone() throws Exception {
        Operation operation = customize(new Operation(), "adminOnly");

        assertThat(operation.getDescription()).isEqualTo("Required authorities: ADMIN.");
    }

    @Test
    void blankDescriptionIsReplacedNotPrefixed() throws Exception {
        Operation operation = customize(new Operation().description("  "), "adminOnly");

        assertThat(operation.getDescription()).isEqualTo("Required authorities: ADMIN.");
    }

    @Test
    void declaredOrderIsKept() throws Exception {
        Operation operation = customize(new Operation(), "superAdminFirst");

        assertThat(operation.getDescription()).isEqualTo("Required authorities: SUPER_ADMIN, ADMIN.");
    }

    @Test
    void hasAuthorityPublishesItsSingleValue() throws Exception {
        Operation operation = customize(new Operation(), "singlePrivilege");

        assertThat(operation.getDescription()).isEqualTo("Required authorities: PRIV_DATA_ADMIN.");
    }

    @Test
    void expressionOutsideTheStandardFormPublishesNothing() throws Exception {
        Operation operation = customize(new Operation().description("Reads the thing"), "roleExpression");

        assertThat(operation.getDescription()).isEqualTo("Reads the thing");
    }

    @Test
    void unguardedHandlerIsLeftAlone() throws Exception {
        Operation operation = customize(new Operation().description("GET the caller"), "unguarded");

        assertThat(operation.getDescription()).isEqualTo("GET the caller");
    }

    @Test
    void unguardedHandlerWithoutDescriptionGetsNone() throws Exception {
        Operation operation = customize(new Operation(), "unguarded");

        assertThat(operation.getDescription()).isNull();
    }

    @Test
    void anonymousEndpointSaysNoTokenIsNeeded() throws Exception {
        Operation operation = customize(new Operation().description("Read the latest terms"), "anonymous");

        assertThat(operation.getDescription()).isEqualTo("Read the latest terms\n\nPublic, no token needed.");
    }

    @Test
    void authenticatedEndpointSaysAnyAuthenticatedUser() throws Exception {
        Operation operation = customize(new Operation(), "authenticated");

        assertThat(operation.getDescription()).isEqualTo("Any authenticated user.");
    }

    @Test
    void guardSentenceWinsWhenAHandlerCarriesBoth() throws Exception {
        Operation operation = customize(new Operation(), "guardedAndMarked");

        assertThat(operation.getDescription()).isEqualTo("Required authorities: SUPER_ADMIN.");
    }
}

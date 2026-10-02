package edu.harvard.hms.dbmi.avillach.openapi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records that a request handler deliberately carries no {@code @PreAuthorize}, and says who may call it. It grants nothing by itself: the
 * service's security filter chain enforces the decision, and this annotation only states it next to the code so a reviewer sees it and
 * {@link RequiredAuthoritiesOperationCustomizer} can publish it in the API document.
 *
 * <p>A handler carries either this or {@code @PreAuthorize}, never both, and only on the method.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PublicEndpoint {

    /**
     * @return who the filter chain lets reach the handler
     */
    Access value();

    /** Who may reach a handler that requires no authority. */
    enum Access {

        /**
         * No token is required. A caller that sends one is not refused; the handler simply does not depend on it. The route must be listed
         * among the service's public routes, or the filter chain will demand authentication anyway.
         */
        ANONYMOUS,

        /** Any caller the filter chain authenticates may reach the handler; no particular authority is required. */
        AUTHENTICATED
    }
}

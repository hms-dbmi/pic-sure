package edu.harvard.hms.dbmi.avillach.openapi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A stand-in for the reactor's {@code @PublicEndpoint}, under the same fully qualified name, so the R21 fixtures compile without this
 * module depending on an unpublished library. It also allows the type target, which the real annotation forbids, so a fixture can show
 * the rule rejecting a class-level declaration.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface PublicEndpoint {

    /**
     * @return who may reach the handler
     */
    Access value();

    /** The two levels the real annotation declares. */
    enum Access {
        ANONYMOUS,
        AUTHENTICATED
    }
}

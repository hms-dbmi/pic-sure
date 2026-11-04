package edu.harvard.dbmi.avillach.logging;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test copy of the logging client's annotation under the same fully qualified name, so audit fixtures
 * compile without this module depending on an unpublished reactor library.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditEvent {
    String type();

    String action();
}

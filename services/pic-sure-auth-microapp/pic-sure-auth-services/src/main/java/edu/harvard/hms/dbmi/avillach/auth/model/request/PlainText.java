package edu.harvard.hms.dbmi.avillach.auth.model.request;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a free-text request member that is stored and later shown to administrators. The value must not contain {@code <} or {@code >}, so
 * a stored name or description can never carry markup into a screen that renders it. A {@code null} value passes; pair the member with
 * {@code @NotBlank} when it is required.
 *
 * <p>Use it on labels and descriptions only. Members that hold syntax, such as JSON paths and JSON documents, may legitimately contain
 * these characters and are not marked.</p>
 */
@Documented
@Constraint(validatedBy = PlainTextValidator.class)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface PlainText {

    String message() default "must not contain '<' or '>'";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

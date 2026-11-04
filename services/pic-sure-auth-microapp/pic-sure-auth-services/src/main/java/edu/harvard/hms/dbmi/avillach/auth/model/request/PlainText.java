package edu.harvard.hms.dbmi.avillach.auth.model.request;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a free-text request member that is stored and later shown to administrators. The value must not contain {@code <} or {@code >},
 * which keeps markup out of the common fields as a second line of defence behind output escaping in the screens. It does not cover members
 * that hold JSON or rule expressions, and it rejects ordinary text that uses those two characters. A {@code null} value passes; pair the
 * member with {@code @NotBlank} when it is required.
 *
 * <p>Use it on labels, descriptions and short path expressions. Members that hold a JSON document, such as {@code requiredFields}, are not
 * marked: the raw text check misses a bracket hidden behind a JSON escape.</p>
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

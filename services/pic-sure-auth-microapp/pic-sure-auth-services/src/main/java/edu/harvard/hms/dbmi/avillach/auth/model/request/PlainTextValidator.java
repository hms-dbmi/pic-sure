package edu.harvard.hms.dbmi.avillach.auth.model.request;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Checks a {@link PlainText} member: a value passes unless it contains {@code <} or {@code >}.
 */
public class PlainTextValidator implements ConstraintValidator<PlainText, String> {

    /**
     * @param value the member's value
     * @param context the validation context
     * @return true when the value is {@code null} or holds neither angle bracket
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return isPlain(value);
    }

    /**
     * Checks a value outside bean validation, for text a service derives after the request was validated, such as an email decoded out of a
     * JSON document whose raw text can hide an angle bracket behind an escape.
     *
     * @param value the text to check
     * @return true when the value is {@code null} or holds neither angle bracket
     */
    public static boolean isPlain(String value) {
        return value == null || (value.indexOf('<') < 0 && value.indexOf('>') < 0);
    }
}

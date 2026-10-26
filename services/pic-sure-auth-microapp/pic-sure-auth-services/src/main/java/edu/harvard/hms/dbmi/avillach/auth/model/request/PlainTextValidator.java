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
        return value == null || (value.indexOf('<') < 0 && value.indexOf('>') < 0);
    }
}

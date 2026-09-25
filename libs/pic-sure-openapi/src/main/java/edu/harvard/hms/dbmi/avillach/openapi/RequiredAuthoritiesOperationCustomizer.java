package edu.harvard.hms.dbmi.avillach.openapi;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.web.method.HandlerMethod;

import io.swagger.v3.oas.models.Operation;

/**
 * Publishes the authorities a handler's {@code @PreAuthorize} requires as a closing paragraph of its operation description, for example
 * {@code Required authorities: ADMIN, SUPER_ADMIN.}, so the document states what the guard enforces instead of relying on hand-kept prose.
 * It reads the two forms the api-conventions rules allow, {@code hasAnyAuthority('A', ...)} and {@code hasAuthority('A')}, and publishes
 * the values exactly as written and in that order. A handler without {@code @PreAuthorize}, or with an expression outside those forms, is
 * left untouched, because a missing guard cannot distinguish a permit-listed route from one that only requires authentication.
 *
 * <p>The annotation is looked up by name, so services that do not have Spring Security on the classpath need nothing extra.
 */
public class RequiredAuthoritiesOperationCustomizer implements OperationCustomizer {

    static final String PRE_AUTHORIZE = "org.springframework.security.access.prepost.PreAuthorize";
    static final String PREFIX = "Required authorities: ";

    private static final String AUTHORITY = "'([A-Za-z0-9_.:-]+)'";
    private static final Pattern ANY_AUTHORITY =
        Pattern.compile("hasAnyAuthority\\(\\s*(" + AUTHORITY + "(?:\\s*,\\s*" + AUTHORITY + ")*)\\s*\\)");
    private static final Pattern ONE_AUTHORITY = Pattern.compile("hasAuthority\\(\\s*" + AUTHORITY + "\\s*\\)");
    private static final Pattern QUOTED = Pattern.compile(AUTHORITY);

    /**
     * Appends the required-authorities sentence to the operation's description when the handler's guard names authorities.
     *
     * @param operation the operation springdoc built for the handler
     * @param handlerMethod the handler the operation documents
     * @return the same operation, with the sentence appended when authorities apply
     */
    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        MergedAnnotation<?> preAuthorize = MergedAnnotations.from(handlerMethod.getMethod()).get(PRE_AUTHORIZE);
        if (!preAuthorize.isPresent()) {
            return operation;
        }
        List<String> authorities = authorities(preAuthorize.getString("value"));
        if (authorities.isEmpty()) {
            return operation;
        }
        String sentence = PREFIX + String.join(", ", authorities) + ".";
        String description = operation.getDescription();
        operation.setDescription(description == null || description.isBlank() ? sentence : description.stripTrailing() + "\n\n" + sentence);
        return operation;
    }

    /**
     * Reads the authorities a {@code hasAnyAuthority} or {@code hasAuthority} expression names.
     *
     * @param expression the {@code @PreAuthorize} value
     * @return the authorities in declared order, or an empty list when the expression is in neither form
     */
    static List<String> authorities(String expression) {
        String trimmed = expression.strip();
        Matcher any = ANY_AUTHORITY.matcher(trimmed);
        if (any.matches()) {
            List<String> values = new ArrayList<>();
            Matcher quoted = QUOTED.matcher(any.group(1));
            while (quoted.find()) {
                values.add(quoted.group(1));
            }
            return values;
        }
        Matcher one = ONE_AUTHORITY.matcher(trimmed);
        return one.matches() ? List.of(one.group(1)) : List.of();
    }
}

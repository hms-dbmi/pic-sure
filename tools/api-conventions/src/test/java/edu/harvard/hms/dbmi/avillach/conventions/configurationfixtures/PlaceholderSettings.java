package edu.harvard.hms.dbmi.avillach.conventions.configurationfixtures;

import org.springframework.beans.factory.annotation.Value;

/** {@code @Value} strings on fields, constructor parameters and method parameters, closed and unclosed, alone and inside expressions. */
public class PlaceholderSettings {

    @Value("${closed.field}")
    private String closedField;

    @Value("${open.field")
    private String openField;

    @Value("${outer:${inner}}")
    private String nestedDefault;

    @Value("${first}-${second")
    private String secondOpen;

    @Value("${closed.default:#{null}}")
    private String closedDefault;

    @Value("#{'${open.expression'}")
    private String openInsideExpression;

    @Value("literal text")
    private String literal;

    public PlaceholderSettings(@Value("${closed.constructor}") String closed, @Value("${open.constructor") String open) {}

    public void configure(String unannotated, @Value("${open.method") String open) {}

    public void configureClosed(@Value("#{${closed.method}}") String closed) {}
}

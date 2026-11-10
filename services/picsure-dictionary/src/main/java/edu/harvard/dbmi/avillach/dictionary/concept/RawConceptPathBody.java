package edu.harvard.dbmi.avillach.dictionary.concept;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a request body that is one concept path sent as a raw string. {@link RawConceptPathBodyCustomizer} documents it as both
 * {@code text/plain} and {@code application/json}, so Swagger UI's Try it out sends the bare path the endpoint matches on.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface RawConceptPathBody {

    /**
     * @return the concept path shown as the body example
     */
    String example();
}

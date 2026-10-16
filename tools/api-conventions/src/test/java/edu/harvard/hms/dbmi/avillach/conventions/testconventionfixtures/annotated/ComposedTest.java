package edu.harvard.hms.dbmi.avillach.conventions.testconventionfixtures.annotated;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;

/** A composed annotation that carries {@code @Test} one meta-level up. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Test
public @interface ComposedTest {
}

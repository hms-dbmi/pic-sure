package edu.harvard.hms.dbmi.avillach.conventions.testconventionfixtures.annotated;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Annotates two test methods and a lifecycle method, forgets the annotation on two more, and declares
 * helpers that are not test-shaped: private, static, with a parameter, or with a return value.
 */
public class SilentMethodTest {

    @BeforeEach
    void setUp() {
        helper();
        staticHelper();
    }

    @Test
    void covered() {
        withArgument(returnsValue());
    }

    @ComposedTest
    void composed() {
    }

    public void forgotten() {
    }

    void alsoForgotten() {
    }

    private void helper() {
    }

    static void staticHelper() {
    }

    void withArgument(String argument) {
    }

    String returnsValue() {
        return "";
    }
}

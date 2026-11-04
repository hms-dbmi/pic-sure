package edu.harvard.hms.dbmi.avillach.conventions.testconventionfixtures.annotated;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Holds a {@code @Nested} inner class that forgets an annotation and a static fixture that is out of scope. */
public class NestedTest {

    @Test
    void outerCovered() {
    }

    @Nested
    class Inner {

        @Test
        void innerCovered() {
        }

        public void innerForgotten() {
        }
    }

    static class Fixture {

        public void run() {
        }
    }
}

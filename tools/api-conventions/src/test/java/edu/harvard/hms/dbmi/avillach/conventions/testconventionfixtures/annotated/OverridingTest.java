package edu.harvard.hms.dbmi.avillach.conventions.testconventionfixtures.annotated;

import org.junit.jupiter.api.Test;

/** Overrides a superclass method and an interface method, neither of which is a test. */
public class OverridingTest extends SharedSetup implements Runnable {

    @Override
    public void reset() {
    }

    @Override
    public void run() {
    }

    @Test
    void covered() {
        reset();
        run();
    }
}

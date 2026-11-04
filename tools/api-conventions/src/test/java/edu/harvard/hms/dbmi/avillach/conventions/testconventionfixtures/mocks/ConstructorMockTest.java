package edu.harvard.hms.dbmi.avillach.conventions.testconventionfixtures.mocks;

import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/** Takes its mock through the constructor, before any Mockito session is open. */
public class ConstructorMockTest {

    private final Runnable collaborator;

    ConstructorMockTest(@Mock Runnable collaborator) {
        this.collaborator = collaborator;
    }

    @Test
    void runs() {
        collaborator.run();
    }
}

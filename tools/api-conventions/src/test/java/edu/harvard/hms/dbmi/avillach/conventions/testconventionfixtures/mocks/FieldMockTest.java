package edu.harvard.hms.dbmi.avillach.conventions.testconventionfixtures.mocks;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/** Declares its mocks as a field and as a lifecycle method parameter, both created inside the session. */
public class FieldMockTest {

    @Mock
    Runnable collaborator;

    @BeforeEach
    void setUp(@Mock Runnable fresh) {
        fresh.run();
    }

    @Test
    void runs() {
        collaborator.run();
    }
}

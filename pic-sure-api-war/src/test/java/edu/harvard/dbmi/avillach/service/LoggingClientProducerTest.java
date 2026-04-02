package edu.harvard.dbmi.avillach.service;

import static org.junit.Assert.*;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import org.junit.Test;

public class LoggingClientProducerTest {

    @Test
    public void producesNoOpWhenJndiBindingsAreMissing() {
        // JNDI lookups will fail in a plain JUnit context (no container),
        // so the producer should gracefully return a no-op client
        LoggingClientProducer producer = new LoggingClientProducer();
        LoggingClient client = producer.loggingClient();

        assertNotNull(client);
        assertFalse("Should be no-op when JNDI bindings are missing", client.isEnabled());
    }

    @Test
    public void cleanupDoesNotThrowWhenNoOp() {
        LoggingClientProducer producer = new LoggingClientProducer();
        producer.loggingClient(); // initialize
        // Should not throw
        producer.cleanup();
    }

    @Test
    public void cleanupDoesNotThrowBeforeInit() {
        LoggingClientProducer producer = new LoggingClientProducer();
        // loggingClient() not called — client is null
        // Should not throw
        producer.cleanup();
    }
}

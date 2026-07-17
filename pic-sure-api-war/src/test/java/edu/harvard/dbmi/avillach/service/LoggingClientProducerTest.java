package edu.harvard.dbmi.avillach.service;

import org.junit.jupiter.api.Test;

import edu.harvard.dbmi.avillach.logging.LoggingClient;

import static org.junit.jupiter.api.Assertions.*;

public class LoggingClientProducerTest {

    @Test
    public void producesNoOpWhenJndiBindingsAreMissing() {
        // JNDI lookups will fail in a plain JUnit context (no container),
        // so the producer should gracefully return a no-op client
        LoggingClientProducer producer = new LoggingClientProducer();
        LoggingClient client = producer.loggingClient();

        assertNotNull(client);
        assertFalse(client.isEnabled(), "Should be no-op when JNDI bindings are missing");
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

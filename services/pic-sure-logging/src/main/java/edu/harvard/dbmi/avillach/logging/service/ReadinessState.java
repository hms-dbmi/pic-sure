package edu.harvard.dbmi.avillach.logging.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** Replaces the AtomicBoolean that App.main toggled around Javalin start/stop. */
@Component
public class ReadinessState {

    private final AtomicBoolean ready = new AtomicBoolean(false);

    public boolean isReady() {
        return ready.get();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void markReady() {
        ready.set(true);
    }

    @EventListener(ContextClosedEvent.class)
    public void markNotReady() {
        ready.set(false);
    }
}

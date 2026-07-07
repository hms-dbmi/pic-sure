package edu.harvard.dbmi.avillach.logging.handler;

import io.javalin.http.Context;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class HealthHandler {

    private final AtomicBoolean ready;

    public HealthHandler(AtomicBoolean ready) {
        this.ready = ready;
    }

    public void handle(Context ctx) {
        if (ready.get()) {
            ctx.status(200);
            ctx.json(Map.of("status", "healthy"));
        } else {
            ctx.status(503);
            ctx.json(Map.of("status", "starting"));
        }
    }
}

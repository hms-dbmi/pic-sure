package edu.harvard.dbmi.avillach.logging.handler;

import edu.harvard.dbmi.avillach.logging.model.InfoResponse;
import io.javalin.http.Context;

import java.util.List;
import java.util.UUID;

public class InfoHandler {

    private final InfoResponse response;

    public InfoHandler() {
        this.response = new InfoResponse(
            UUID.nameUUIDFromBytes(":)".getBytes()),
            "Logging Service",
            List.of()
        );
    }

    public void handle(Context ctx) {
        ctx.json(response);
    }
}

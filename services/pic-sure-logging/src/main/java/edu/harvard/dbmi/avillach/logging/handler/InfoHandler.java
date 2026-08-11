package edu.harvard.dbmi.avillach.logging.handler;

import edu.harvard.dbmi.avillach.contracts.info.QueryFormat;
import edu.harvard.dbmi.avillach.contracts.info.ResourceInfo;
import io.javalin.http.Context;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class InfoHandler {

    /**
     * The logging service is not a queryable resource -- it advertises no query formats. The names below are the ones this service claims
     * to accept; each is widened into the shared {@link QueryFormat} record so every PIC-SURE /info speaks one shape.
     */
    private static final List<String> QUERY_FORMAT_NAMES = List.of();

    private final ResourceInfo response;

    public InfoHandler() {
        this.response = new ResourceInfo(
            UUID.nameUUIDFromBytes(":)".getBytes()), "Logging Service",
            QUERY_FORMAT_NAMES.stream().map(name -> new QueryFormat(name, "", Map.of(), List.of())).toList()
        );
    }

    public void handle(Context ctx) {
        ctx.json(response);
    }
}

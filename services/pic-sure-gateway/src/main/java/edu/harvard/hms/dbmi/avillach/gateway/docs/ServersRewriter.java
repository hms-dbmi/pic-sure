package edu.harvard.hms.dbmi.avillach.gateway.docs;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Replaces a document's {@code servers} array with the single public ingress prefix the gateway knows for that service. */
public final class ServersRewriter {

    private ServersRewriter() {}

    /**
     * Sets {@code servers} to {@code [{"url": publicPrefix}]} in place.
     *
     * @param document the upstream document; mutated
     * @param publicPrefix the value of {@code servers[0].url}
     * @return the same document, for chaining
     */
    public static ObjectNode rewrite(ObjectNode document, String publicPrefix) {
        document.putArray("servers").addObject().put("url", publicPrefix);
        return document;
    }
}

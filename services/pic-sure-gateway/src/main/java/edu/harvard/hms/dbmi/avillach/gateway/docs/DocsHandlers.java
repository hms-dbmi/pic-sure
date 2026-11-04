package edu.harvard.hms.dbmi.avillach.gateway.docs;

import java.util.Optional;

import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The docs console's two document handler functions: the index and one live document. Both are {@code no-cache} because they are generated
 * on every call.
 */
public class DocsHandlers {

    private final DocsProperties props;
    private final OpenApiDocumentFetcher fetcher;
    private final ObjectMapper json;

    public DocsHandlers(DocsProperties props, OpenApiDocumentFetcher fetcher, ObjectMapper json) {
        this.props = props;
        this.fetcher = fetcher;
        this.json = json;
    }

    /** {@code GET /openapi}: {@code [{name, title, url}]} in registry order, {@code url} relative to the page. */
    public ServerResponse index(ServerRequest request) {
        ArrayNode index = json.createArrayNode();
        for (DocumentedService service : props.services()) {
            index.addObject().put("name", service.name()).put("title", service.title()).put("url", "openapi/" + service.name());
        }
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noCache()).body(index.toString());
    }

    /**
     * {@code GET /openapi/{name}}: the upstream document with {@code servers} rewritten, 404 for an unknown name, 502 when the upstream
     * fails.
     */
    public ServerResponse document(ServerRequest request) {
        String name = request.pathVariable("name");
        Optional<DocumentedService> service = props.services().stream().filter(s -> s.name().equals(name)).findFirst();
        if (service.isEmpty()) {
            return notFound("No such API document: " + name);
        }
        ObjectNode document;
        try {
            document = fetcher.fetch(service.get());
        } catch (UpstreamUnavailable ex) {
            ObjectNode body = json.createObjectNode().put("error", "upstream_unavailable").put("service", name);
            return ServerResponse.status(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noCache()).body(body.toString());
        }
        ServersRewriter.rewrite(document, service.get().publicPrefix());
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noCache()).body(document.toString());
    }

    private ServerResponse notFound(String message) {
        ObjectNode body = json.createObjectNode().put("errorType", "not_found").put("message", message);
        body.put("requestId", MDC.get("requestId"));
        return ServerResponse.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(body.toString());
    }
}

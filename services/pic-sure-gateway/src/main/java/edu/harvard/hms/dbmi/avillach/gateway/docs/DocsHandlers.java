package edu.harvard.hms.dbmi.avillach.gateway.docs;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.MDC;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The docs console's five handler functions: the index, one live document, the initializer page, its trailing-slash redirect, and the
 * webjar assets. The index and documents are {@code no-cache} because they are generated on every call; assets get one day because the
 * public URL carries no version while the bytes behind it change on a webjar bump. The redirect is relative because httpd strips
 * {@code /picsure} before the gateway sees a request, so an absolute {@code Location} would drop the prefix.
 */
public class DocsHandlers {

    private static final CacheControl ONE_DAY = CacheControl.maxAge(Duration.ofDays(1)).cachePublic();

    private final DocsProperties props;
    private final OpenApiDocumentFetcher fetcher;
    private final SwaggerUiAssets assets;
    private final ObjectMapper json;
    private final Resource viewerPage = new ClassPathResource("swagger-ui/index.html");

    public DocsHandlers(DocsProperties props, OpenApiDocumentFetcher fetcher, SwaggerUiAssets assets, ObjectMapper json) {
        this.props = props;
        this.fetcher = fetcher;
        this.assets = assets;
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

    /** {@code GET /swagger-ui}: the initializer page. */
    public ServerResponse viewer(ServerRequest request) {
        return ServerResponse.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noCache()).body(viewerPage);
    }

    /** {@code GET /swagger-ui/}: back to the canonical slash-less URL, relatively. */
    public ServerResponse viewerTrailingSlash(ServerRequest request) {
        return ServerResponse.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "../swagger-ui").build();
    }

    /** {@code GET /swagger-ui/{asset}}: one of the six webjar files, or 404. */
    public ServerResponse asset(ServerRequest request) {
        String name = request.pathVariable("asset");
        return assets.asset(name)
            .map(asset -> ServerResponse.ok().contentType(asset.contentType()).cacheControl(ONE_DAY).body(asset.resource()))
            .orElseGet(() -> notFound("No such asset: " + name));
    }

    private ServerResponse notFound(String message) {
        ObjectNode body = json.createObjectNode().put("errorType", "not_found").put("message", message);
        body.put("requestId", MDC.get("requestId"));
        return ServerResponse.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(body.toString());
    }
}

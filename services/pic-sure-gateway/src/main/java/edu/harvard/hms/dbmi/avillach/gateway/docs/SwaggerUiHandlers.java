package edu.harvard.hms.dbmi.avillach.gateway.docs;

import java.time.Duration;

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
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The built-in Swagger UI: the initializer page, its trailing-slash redirect, and the six webjar assets. Separate from {@link DocsHandlers}
 * so {@code GATEWAY_DOCS_UI_ENABLED=false} can remove the console while the documents keep serving for an interface of the deployment's
 * own. Assets get one day of caching because the public URL carries no version while the bytes behind it change on a webjar bump; the
 * redirect is relative because httpd strips {@code /picsure} before the gateway sees a request.
 */
public class SwaggerUiHandlers {

    private static final CacheControl ONE_DAY = CacheControl.maxAge(Duration.ofDays(1)).cachePublic();

    private final SwaggerUiAssets assets;
    private final ObjectMapper json;
    private final Resource viewerPage = new ClassPathResource("swagger-ui/index.html");

    public SwaggerUiHandlers(SwaggerUiAssets assets, ObjectMapper json) {
        this.assets = assets;
        this.json = json;
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

package edu.harvard.hms.dbmi.avillach.gateway.docs;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Reads the six committed contract documents off the classpath (baked into {@code /openapi} by the module's {@code copy-contract-documents}
 * resources execution) and hands out serving copies whose {@code servers} array points at the real public ingress for that service -- see
 * {@link ContractDocument} for how each prefix is derived.
 *
 * <p><b>Nothing but {@code servers} changes.</b> The document is parsed, its {@code servers} value is replaced in place (Jackson's
 * {@code ObjectNode} keeps key order, so the field does not move), and the tree is re-serialized. Every other key, value and ordering is
 * the committed document's. {@code OpenApiDocumentsTest} diffs served-minus-{@code servers} against committed-minus-{@code servers} for all
 * six to keep it that way.
 *
 * <p>All six are read and rewritten ONCE, at construction. That makes a missing or unparseable document a startup failure rather than a
 * per-request surprise -- the failure mode of a broken build that shipped no documents is then loud and immediate -- and leaves the route
 * itself doing nothing but a map lookup.
 */
public class OpenApiDocuments {

    /** Classpath directory the build copies {@code docs/api/*.openapi.json} into. */
    static final String CLASSPATH_DIRECTORY = "openapi/";

    private final Map<String, String> documentsByFileName;
    private final String index;

    public OpenApiDocuments(ObjectMapper json) {
        Map<String, String> documents = new LinkedHashMap<>();
        for (ContractDocument document : ContractDocument.all()) {
            documents.put(document.fileName(), rewriteServers(json, document));
        }
        this.documentsByFileName = Map.copyOf(documents);
        this.index = buildIndex(json);
    }

    /**
     * The serving copy of {@code fileName}, or empty when it names no known document. Unknown names are the ONLY 404 path: the name is
     * looked up in a closed six-entry map, so a traversal, encoded-separator or wildcard attempt simply misses.
     */
    public Optional<String> document(String fileName) {
        return Optional.ofNullable(documentsByFileName.get(fileName));
    }

    /** The {@code GET /openapi} index: what documents exist, what each is called, and where each one's API actually lives. */
    public String index() {
        return index;
    }

    private static String rewriteServers(ObjectMapper json, ContractDocument document) {
        String path = CLASSPATH_DIRECTORY + document.fileName();
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream in = resource.getInputStream()) {
            ObjectNode root = (ObjectNode) json.readTree(in);
            // putArray REPLACES the existing value while keeping the key where it already sits, so a served
            // document differs from the committed one in the servers value alone.
            ArrayNode servers = root.putArray("servers");
            document.publicServer()
                .ifPresent(url -> servers.addObject().put("description", ContractDocument.SERVER_DESCRIPTION).put("url", url));
            return json.writeValueAsString(root);
        } catch (IOException e) {
            throw new UncheckedIOException(
                "contract document " + path + " is missing from the classpath or is not readable JSON; the build's"
                    + " copy-contract-documents execution should have copied docs/api/" + document.fileName() + " into target/classes",
                e
            );
        } catch (ClassCastException e) {
            throw new IllegalStateException("contract document " + path + " is not a JSON object", e);
        }
    }

    private static String buildIndex(ObjectMapper json) {
        ObjectNode root = json.createObjectNode();
        ArrayNode documents = root.putArray("documents");
        for (ContractDocument document : ContractDocument.all()) {
            ObjectNode entry = documents.addObject();
            entry.put("name", document.artifactId());
            entry.put("title", document.title());
            // Relative on purpose: the gateway is mounted under /picsure by httpd but sees only the stripped
            // path, so it cannot name its own public URL. Resolved against the /openapi index's own URL, this
            // yields the right absolute URL in every environment.
            entry.put("file", document.fileName());
            entry.put("publiclyRoutable", document.publiclyRoutable());
            document.publicServer().ifPresent(url -> entry.put("server", url));
        }
        try {
            return json.writeValueAsString(root);
        } catch (IOException e) {
            throw new UncheckedIOException("could not render the /openapi index", e);
        }
    }
}

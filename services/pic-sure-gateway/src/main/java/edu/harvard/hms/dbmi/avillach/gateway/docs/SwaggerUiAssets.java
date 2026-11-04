package edu.harvard.hms.dbmi.avillach.gateway.docs;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;

/**
 * Serves a closed set of six files from the {@code org.webjars:swagger-ui} jar under {@code /swagger-ui/}. The webjar's version directory
 * is read from its own {@code pom.properties} at construction, so a BOM bump needs no Java change. The set is closed on purpose: Boot's
 * {@code /webjars/**} mapping is not on the gateway's unauthenticated allow-list, and nothing outside these six is needed by the
 * initializer page.
 */
public class SwaggerUiAssets {

    /** One servable file and the content type it is served with. */
    public record Asset(Resource resource, MediaType contentType) {
    }

    private static final MediaType CSS = MediaType.valueOf("text/css");
    private static final MediaType JAVASCRIPT = MediaType.valueOf("text/javascript");
    private static final Map<String, MediaType> SERVED = Map.of(
        "swagger-ui.css", CSS, "index.css", CSS, "swagger-ui-bundle.js", JAVASCRIPT, "swagger-ui-standalone-preset.js", JAVASCRIPT,
        "favicon-32x32.png", MediaType.IMAGE_PNG, "favicon-16x16.png", MediaType.IMAGE_PNG
    );

    private final String version;

    public SwaggerUiAssets() {
        this.version = discoverVersion();
    }

    private static String discoverVersion() {
        Properties pom = new Properties();
        try (InputStream in = new ClassPathResource("META-INF/maven/org.webjars/swagger-ui/pom.properties").getInputStream()) {
            pom.load(in);
        } catch (IOException ex) {
            throw new IllegalStateException("the swagger-ui webjar is not on the classpath", ex);
        }
        String version = pom.getProperty("version");
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("the swagger-ui webjar declares no version");
        }
        return version;
    }

    /** The webjar version directory the assets resolve under. */
    public String version() {
        return version;
    }

    /** The six file names this class will serve. */
    public Set<String> names() {
        return SERVED.keySet();
    }

    /** The asset for {@code name}, or empty for any name outside the closed set or missing from the webjar. */
    public Optional<Asset> asset(String name) {
        MediaType type = SERVED.get(name);
        if (type == null) {
            return Optional.empty();
        }
        Resource resource = new ClassPathResource("META-INF/resources/webjars/swagger-ui/" + version + "/" + name);
        return resource.exists() ? Optional.of(new Asset(resource, type)) : Optional.empty();
    }
}

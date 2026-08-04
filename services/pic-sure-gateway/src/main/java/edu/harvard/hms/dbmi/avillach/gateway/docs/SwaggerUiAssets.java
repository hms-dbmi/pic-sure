package edu.harvard.hms.dbmi.avillach.gateway.docs;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;

/**
 * Serves the handful of {@code org.webjars:swagger-ui} files the checked-in initializer actually loads, from the webjar on the gateway's
 * classpath -- so no minified blob is vendored into this repository and the viewer upgrades with a version bump in the platform BOM.
 *
 * <p><b>Why not Boot's own webjar handling.</b> Spring Boot already maps {@code classpath:/META-INF/resources/} onto {@code /**}, which
 * would expose this same webjar at {@code /webjars/swagger-ui/<version>/...}. That path is NOT on the gateway's unauthenticated allow-list
 * ({@code /openapi}, {@code /swagger-ui}), so every asset request from the viewer would be sent to PSAMA for introspection and denied.
 * Re-serving the files under {@code /swagger-ui/} puts them where the allow-list already permits them.
 *
 * <p><b>No path is built from request input.</b> {@link #asset(String)} matches the requested name against a closed map of literals before
 * touching the classpath, the same closed-set rule {@link OpenApiDocuments#document(String)} follows for documents.
 *
 * <p>The webjar nests its files under a version-numbered directory. Rather than duplicate the BOM's pin in Java, the version is read from
 * the webjar's own {@code META-INF/maven/.../pom.properties} at startup, so a BOM bump needs no code change and a missing webjar fails
 * loudly at startup instead of 404-ing a stylesheet in production.
 */
public class SwaggerUiAssets {

    private static final String POM_PROPERTIES = "META-INF/maven/org.webjars/swagger-ui/pom.properties";

    private static final MediaType TEXT_CSS = MediaType.valueOf("text/css");
    private static final MediaType TEXT_JAVASCRIPT = MediaType.valueOf("text/javascript");

    /** Exactly what {@code swagger-ui/index.html} references. Adding a reference there means adding it here. */
    private static final Map<String, MediaType> ALLOWED = Map.of(
        "swagger-ui.css", TEXT_CSS, "index.css", TEXT_CSS, "swagger-ui-bundle.js", TEXT_JAVASCRIPT, "swagger-ui-standalone-preset.js",
        TEXT_JAVASCRIPT, "favicon-16x16.png", MediaType.IMAGE_PNG, "favicon-32x32.png", MediaType.IMAGE_PNG
    );

    private final String webjarDirectory;

    public SwaggerUiAssets() {
        this(readWebjarVersion());
    }

    SwaggerUiAssets(String webjarVersion) {
        this.webjarDirectory = "META-INF/resources/webjars/swagger-ui/" + webjarVersion + "/";
    }

    /** An asset by its bare file name, or empty when the name is not one of the handful the initializer loads. */
    public Optional<Asset> asset(String fileName) {
        MediaType contentType = ALLOWED.get(fileName);
        if (contentType == null) {
            return Optional.empty();
        }
        Resource resource = new ClassPathResource(webjarDirectory + fileName);
        return resource.exists() ? Optional.of(new Asset(resource, contentType)) : Optional.empty();
    }

    /** The checked-in initializer page, the one hand-written file in the viewer. */
    public Resource initializer() {
        return new ClassPathResource("swagger-ui/index.html");
    }

    /** The resolved webjar version, for the test that pins it against the platform BOM. */
    String webjarDirectory() {
        return webjarDirectory;
    }

    private static String readWebjarVersion() {
        ClassPathResource resource = new ClassPathResource(POM_PROPERTIES);
        try (InputStream in = resource.getInputStream()) {
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version");
            if (version == null || version.isBlank()) {
                throw new IllegalStateException(POM_PROPERTIES + " declares no version");
            }
            return version;
        } catch (IOException e) {
            throw new UncheckedIOException("org.webjars:swagger-ui is not on the classpath; the /swagger-ui viewer cannot be served", e);
        }
    }

    /** A servable asset: the classpath resource plus the content type the browser must be told. */
    public record Asset(Resource resource, MediaType contentType) {
    }
}

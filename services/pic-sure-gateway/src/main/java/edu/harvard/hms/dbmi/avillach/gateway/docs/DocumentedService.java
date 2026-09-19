package edu.harvard.hms.dbmi.avillach.gateway.docs;

import java.util.regex.Pattern;

/**
 * One service whose live OpenAPI document the gateway proxies.
 *
 * @param name the URL segment under {@code /openapi/}; lowercase letters, digits, and hyphens only
 * @param title the label the console's picker shows; defaults to the name
 * @param url the upstream base, the same placeholder the proxy route uses
 * @param docsPath the document's path on the upstream, including the service's context path when it has one; defaults to
 *        {@code /v3/api-docs}
 * @param publicPrefix the ingress path the documented paths hang off; becomes {@code servers[0].url}
 */
public record DocumentedService(String name, String title, String url, String docsPath, String publicPrefix) {

    private static final Pattern NAME = Pattern.compile("[a-z0-9-]+");

    public DocumentedService {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("docs service name must match [a-z0-9-]+ but was '" + name + "'");
        }
        if (title == null || title.isBlank()) {
            title = name;
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("docs service '" + name + "' has no url");
        }
        if (docsPath == null || docsPath.isBlank()) {
            docsPath = "/v3/api-docs";
        }
        if (publicPrefix == null) {
            throw new IllegalArgumentException("docs service '" + name + "' has no public-prefix");
        }
    }

    /** The absolute upstream URL of the document: the base without a trailing slash joined to the docs path. */
    public String documentUrl() {
        String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        String path = docsPath.startsWith("/") ? docsPath : "/" + docsPath;
        return base + path;
    }
}

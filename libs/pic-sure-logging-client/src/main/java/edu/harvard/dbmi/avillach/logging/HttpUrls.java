package edu.harvard.dbmi.avillach.logging;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Checks for service URLs read from configuration, shared by the clients that call another PIC-SURE service over HTTP.
 */
public final class HttpUrls {

    private HttpUrls() {}

    /**
     * Requires a configured value to be an absolute http or https URL that names a host. A scheme-less or malformed value would otherwise
     * surface only when the first request is sent, so a caller checks it up front and fails with a message that names the setting.
     *
     * @param name the setting the value came from, which starts the exception message
     * @param url the value to check, which must not be null and is checked as given, so the caller removes any surrounding whitespace first
     * @throws IllegalArgumentException if {@code url} is not a valid URI, has a scheme other than http or https, or has no host
     */
    public static void requireAbsoluteHttpUrl(String name, String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(name + " is not a valid URL: " + url, e);
        }
        String scheme = uri.getScheme();
        boolean http = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (!http || uri.getHost() == null) {
            throw new IllegalArgumentException(name + " must be an absolute http(s) URL with a host, got: " + url);
        }
    }
}

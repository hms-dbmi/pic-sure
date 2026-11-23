package edu.harvard.dbmi.avillach.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URISyntaxException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpUrlsTest {

    @ParameterizedTest
    @ValueSource(
        strings = {"http://dictionary-api", "https://dictionary-api:8443/base", "HTTP://dictionary-api", "HttpS://dictionary-api/"}
    )
    void requireAbsoluteHttpUrl_httpOrHttpsUrlWithHost_isAccepted(String url) {
        assertDoesNotThrow(() -> HttpUrls.requireAbsoluteHttpUrl("dictionary.url", url));
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"dictionary-api", "dictionary-api:80", "/dictionary", "ftp://dictionary-api", "http:///path", "http:dictionary-api"}
    )
    void requireAbsoluteHttpUrl_noHttpSchemeOrNoHost_throwsNamingSettingAndValue(String url) {
        IllegalArgumentException thrown =
            assertThrows(IllegalArgumentException.class, () -> HttpUrls.requireAbsoluteHttpUrl("dictionary.url", url));

        assertEquals("dictionary.url must be an absolute http(s) URL with a host, got: " + url, thrown.getMessage());
    }

    @Test
    void requireAbsoluteHttpUrl_valueThatIsNotAUri_throwsNamingSettingAndValueWithTheParseFailureAsCause() {
        IllegalArgumentException thrown =
            assertThrows(IllegalArgumentException.class, () -> HttpUrls.requireAbsoluteHttpUrl("baseUrl", "http://host with spaces"));

        assertEquals("baseUrl is not a valid URL: http://host with spaces", thrown.getMessage());
        assertInstanceOf(URISyntaxException.class, thrown.getCause());
    }

    @Test
    void requireAbsoluteHttpUrl_surroundingWhitespace_isNotRemoved() {
        assertThrows(IllegalArgumentException.class, () -> HttpUrls.requireAbsoluteHttpUrl("baseUrl", " http://host "));
    }
}

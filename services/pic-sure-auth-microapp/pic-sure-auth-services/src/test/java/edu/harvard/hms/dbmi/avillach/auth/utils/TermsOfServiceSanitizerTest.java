package edu.harvard.hms.dbmi.avillach.auth.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins what the terms of service sanitizer keeps and removes. It keeps the markup the editor produces and the frontend renders, and drops
 * scripts, event handlers, scriptable links and styles outside the allowlist.
 */
class TermsOfServiceSanitizerTest {

    @Test
    void keepsOrdinaryEditorMarkup() {
        String html = "<h2>Terms</h2><p>Read <strong>all</strong> of it, then <a href=\"https://example.org/policy\">the policy</a>.</p>"
            + "<ul><li>One</li><li>Two</li></ul>";

        assertThat(TermsOfServiceSanitizer.sanitize(html)).contains("<h2>Terms</h2>", "<strong>all</strong>", "<li>Two</li>")
            .contains("href=\"https://example.org/policy\"");
    }

    @Test
    void removesScriptElementsAndTheirContent() {
        assertThat(TermsOfServiceSanitizer.sanitize("<p>ok</p><script>alert(1)</script>")).isEqualTo("<p>ok</p>");
    }

    @Test
    void removesEventHandlerAttributes() {
        assertThat(TermsOfServiceSanitizer.sanitize("<img src=\"https://example.org/a.png\" onerror=\"alert(1)\">"))
            .doesNotContain("onerror").contains("src=\"https://example.org/a.png\"");
    }

    @Test
    void removesScriptableLinkSchemes() {
        assertThat(TermsOfServiceSanitizer.sanitize("<a href=\"javascript:alert(1)\">x</a>")).doesNotContain("javascript");
        assertThat(TermsOfServiceSanitizer.sanitize("<img src=\"data:text/html;base64,AAAA\">")).doesNotContain("data:");
    }

    @Test
    void removesIframeAndObjectElements() {
        assertThat(TermsOfServiceSanitizer.sanitize("<iframe src=\"https://example.org\"></iframe><object data=\"x\"></object>"))
            .doesNotContain("iframe").doesNotContain("object");
    }

    @Test
    void keepsThemeColorsAndDropsEveryOtherStyle() {
        String html = "<span style=\"color: var(--color-primary-500); background: url(javascript:alert(1)); position: fixed\">x</span>";

        assertThat(TermsOfServiceSanitizer.sanitize(html)).contains("color:var(--color-primary-500)").doesNotContain("url(")
            .doesNotContain("position");
    }

    @Test
    void dropsColorVariablesOutsideTheTheme() {
        assertThat(TermsOfServiceSanitizer.sanitize("<p style=\"color: var(--evil)\">x</p>")).doesNotContain("--evil");
    }

    @Test
    void dropsTheStyleAttributeWhenNothingInItIsAllowed() {
        assertThat(TermsOfServiceSanitizer.sanitize("<p style=\"position: fixed\">x</p>")).isEqualTo("<p>x</p>");
    }

    @Test
    void passesNullThrough() {
        assertThat(TermsOfServiceSanitizer.sanitize(null)).isNull();
    }
}

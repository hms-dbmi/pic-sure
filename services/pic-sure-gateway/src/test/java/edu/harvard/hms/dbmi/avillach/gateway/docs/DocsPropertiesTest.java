package edu.harvard.hms.dbmi.avillach.gateway.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Registry validation runs inside constructor binding, so a bad application.yml fails the gateway at startup. */
class DocsPropertiesTest {

    private static DocumentedService entry(String name) {
        return new DocumentedService(name, "Title", "http://upstream:8080", null, "/picsure/" + name);
    }

    @Test
    void docsPathDefaultsAndDocumentUrlJoinsWithoutDoubleSlash() {
        DocumentedService service = new DocumentedService("ops", "Operations", "http://upstream:8080/", null, "/picsure/operations");
        assertThat(service.docsPath()).isEqualTo("/v3/api-docs");
        assertThat(service.documentUrl()).isEqualTo("http://upstream:8080/v3/api-docs");
    }

    @Test
    void enabledAndPublicBaseHaveDefaults() {
        DocsProperties props = new DocsProperties(null, null, null);
        assertThat(props.enabled()).isTrue();
        assertThat(props.publicBase()).isEqualTo("/picsure");
        assertThat(props.services()).isEmpty();
    }

    @Test
    void duplicateNamesAreRejected() {
        assertThatThrownBy(() -> new DocsProperties(true, "/picsure", List.of(entry("ops"), entry("ops"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ops");
    }

    @Test
    void malformedNamesAreRejected() {
        for (String bad : List.of("Ops", "ops service", "../ops", "ops/v3", "")) {
            assertThatThrownBy(() -> entry(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void bindingSurfacesTheRejection() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(
            Map.of(
                "picsure.gateway.docs.services[0].name", "ops", "picsure.gateway.docs.services[0].url", "http://a",
                "picsure.gateway.docs.services[0].public-prefix", "/picsure/ops", "picsure.gateway.docs.services[1].name", "ops",
                "picsure.gateway.docs.services[1].url", "http://b", "picsure.gateway.docs.services[1].public-prefix", "/picsure/ops"
            )
        );
        assertThatThrownBy(() -> new Binder(source).bind("picsure.gateway.docs", Bindable.of(DocsProperties.class)))
            .isInstanceOf(BindException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}

package edu.harvard.hms.dbmi.avillach.commons.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class AuditContextTest {

    @Test
    void isNoArgConstructableWithEmptyMetadataByDefault() {
        AuditContext context = new AuditContext();

        assertThat(context.getMetadata()).isEmpty();
    }

    @Test
    void putAddsKeyValuePairsToMetadata() {
        AuditContext context = new AuditContext();

        context.put("resource_id", "abc-123");
        context.put("username", "researcher1");

        assertThat(context.getMetadata()).containsEntry("resource_id", "abc-123").containsEntry("username", "researcher1");
    }

    @Test
    void putIgnoresNullKeysAndValues() {
        AuditContext context = new AuditContext();

        context.put(null, "value");
        context.put("key", null);

        assertThat(context.getMetadata()).isEmpty();
    }

    @Test
    void getMetadataIsAnUnmodifiableLiveView() {
        AuditContext context = new AuditContext();
        context.put("resource_id", "abc-123");

        Map<String, Object> view = context.getMetadata();

        assertThatThrownBy(() -> view.put("injected", "x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.remove("resource_id")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(view::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(context.getMetadata()).containsEntry("resource_id", "abc-123");

        context.put("username", "researcher1");
        assertThat(view).containsEntry("username", "researcher1");
    }
}

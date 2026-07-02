package edu.harvard.hms.dbmi.avillach.commons.audit;

import static org.assertj.core.api.Assertions.assertThat;

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
}

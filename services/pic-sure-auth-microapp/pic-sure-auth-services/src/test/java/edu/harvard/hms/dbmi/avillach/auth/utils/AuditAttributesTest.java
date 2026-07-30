package edu.harvard.hms.dbmi.avillach.auth.utils;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuditAttributesTest {

    @Test
    void shouldPutAndRetrieveMetadata() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AuditAttributes.putMetadata(request, "key1", "value1");
        AuditAttributes.putMetadata(request, "key2", 42);

        Map<String, Object> all = AuditAttributes.getMetadata(request);
        assertEquals("value1", all.get("key1"));
        assertEquals(42, all.get("key2"));
        assertEquals(2, all.size());
    }

    @Test
    void shouldIgnoreNullKeyOrValue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AuditAttributes.putMetadata(request, null, "value");
        AuditAttributes.putMetadata(request, "key", null);

        Map<String, Object> all = AuditAttributes.getMetadata(request);
        assertTrue(all.isEmpty());
    }

    @Test
    void shouldIgnoreNullRequest() {
        AuditAttributes.putMetadata(null, "key", "value");
        Map<String, Object> all = AuditAttributes.getMetadata(null);
        assertTrue(all.isEmpty());
    }

    /**
     * v3 query bodies carry no resourceUUID, so the audit resource label is derived from the path the request targets. Losing this would
     * leave every introspection audit record without a resource.
     */
    @Test
    void shouldDeriveResourceLabelFromTargetServicePath() {
        assertEquals("hpds", AuditAttributes.resourceLabelForPath("/hpds/auth/v3/query"));
        assertEquals("picsure", AuditAttributes.resourceLabelForPath("/picsure/proxy/dictionary/search"));
        assertEquals("operations", AuditAttributes.resourceLabelForPath("operations/dataset/named"));
        assertEquals("hpds", AuditAttributes.resourceLabelForPath("/hpds"));
    }

    @Test
    void shouldReturnNullResourceLabelWhenThereIsNoPath() {
        assertNull(AuditAttributes.resourceLabelForPath(null));
        assertNull(AuditAttributes.resourceLabelForPath(""));
        assertNull(AuditAttributes.resourceLabelForPath("   "));
        assertNull(AuditAttributes.resourceLabelForPath("/"));
    }

    @Test
    void shouldNotIncludeNonAuditAttributes() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("some.other.attr", "not-audit");
        AuditAttributes.putMetadata(request, "audit_key", "audit_value");

        Map<String, Object> all = AuditAttributes.getMetadata(request);
        assertEquals(1, all.size());
        assertEquals("audit_value", all.get("audit_key"));
    }
}

package edu.harvard.hms.dbmi.avillach.hpds.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import edu.harvard.hms.dbmi.avillach.hpds.processing.audit.AuditAttributes;

class AuditAttributesTest {

    @Test
    void testPutAndGetMetadata() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        AuditAttributes.putMetadata(request, "query_id", "abc-123");
        AuditAttributes.putMetadata(request, "result_type", "COUNT");

        Map<String, Object> metadata = AuditAttributes.getMetadata(request);
        assertEquals("abc-123", metadata.get("query_id"));
        assertEquals("COUNT", metadata.get("result_type"));
    }

    @Test
    void testNullKeyIsIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        AuditAttributes.putMetadata(request, null, "value");

        assertTrue(AuditAttributes.getMetadata(request).isEmpty());
    }

    @Test
    void testNullValueIsIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        AuditAttributes.putMetadata(request, "key", null);

        assertTrue(AuditAttributes.getMetadata(request).isEmpty());
    }

    @Test
    void testNullRequestIsIgnored() {
        // Should not throw
        AuditAttributes.putMetadata(null, "key", "value");
    }

    @Test
    void testGetMetadataCreatesMapOnFirstCall() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        Map<String, Object> metadata = AuditAttributes.getMetadata(request);

        assertNotNull(metadata);
        assertTrue(metadata.isEmpty());
    }

    @Test
    void testGetMetadataReturnsSameMapOnSubsequentCalls() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        Map<String, Object> first = AuditAttributes.getMetadata(request);
        first.put("key", "value");
        Map<String, Object> second = AuditAttributes.getMetadata(request);

        assertSame(first, second);
        assertEquals("value", second.get("key"));
    }

    @Test
    void testOverwritesExistingKey() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        AuditAttributes.putMetadata(request, "result_type", "COUNT");
        AuditAttributes.putMetadata(request, "result_type", "DATAFRAME");

        assertEquals("DATAFRAME", AuditAttributes.getMetadata(request).get("result_type"));
    }
}

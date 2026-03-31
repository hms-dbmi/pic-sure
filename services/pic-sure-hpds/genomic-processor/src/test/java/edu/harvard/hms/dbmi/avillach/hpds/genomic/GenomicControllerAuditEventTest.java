package edu.harvard.hms.dbmi.avillach.hpds.genomic;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;

import edu.harvard.hms.dbmi.avillach.hpds.processing.DistributableQuery;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import org.junit.jupiter.api.Test;

class GenomicControllerAuditEventTest {

    @Test
    void queryForPatientMaskHasCorrectAuditEvent() throws Exception {
        Method method = GenomicProcessorController.class.getMethod("queryForPatientMask", DistributableQuery.class);
        AuditEvent event = method.getAnnotation(AuditEvent.class);

        assertNotNull(event, "queryForPatientMask should have @AuditEvent");
        assertEquals("QUERY", event.type());
        assertEquals("genomic.patient.mask", event.action());
    }

    @Test
    void queryForVariantsHasCorrectAuditEvent() throws Exception {
        Method method = GenomicProcessorController.class.getMethod("queryForVariants", DistributableQuery.class);
        AuditEvent event = method.getAnnotation(AuditEvent.class);

        assertNotNull(event, "queryForVariants should have @AuditEvent");
        assertEquals("QUERY", event.type());
        assertEquals("genomic.variant.list", event.action());
    }

    @Test
    void getPatientIdsHasCorrectAuditEvent() throws Exception {
        Method method = GenomicProcessorController.class.getMethod("getPatientIds");
        AuditEvent event = method.getAnnotation(AuditEvent.class);

        assertNotNull(event, "getPatientIds should have @AuditEvent");
        assertEquals("DATA_ACCESS", event.type());
        assertEquals("genomic.patient.ids", event.action());
    }

    @Test
    void getInfoStoreColumnsHasCorrectAuditEvent() throws Exception {
        Method method = GenomicProcessorController.class.getMethod("getInfoStoreColumns");
        AuditEvent event = method.getAnnotation(AuditEvent.class);

        assertNotNull(event, "getInfoStoreColumns should have @AuditEvent");
        assertEquals("OTHER", event.type());
        assertEquals("genomic.info", event.action());
    }

    @Test
    void getInfoStoreValuesHasCorrectAuditEvent() throws Exception {
        Method method = GenomicProcessorController.class.getMethod("getInfoStoreValues", String.class);
        AuditEvent event = method.getAnnotation(AuditEvent.class);

        assertNotNull(event, "getInfoStoreValues should have @AuditEvent");
        assertEquals("OTHER", event.type());
        assertEquals("genomic.info", event.action());
    }

    @Test
    void getInfoMetadataHasCorrectAuditEvent() throws Exception {
        Method method = GenomicProcessorController.class.getMethod("getInfoMetadata");
        AuditEvent event = method.getAnnotation(AuditEvent.class);

        assertNotNull(event, "getInfoMetadata should have @AuditEvent");
        assertEquals("OTHER", event.type());
        assertEquals("genomic.info", event.action());
    }
}

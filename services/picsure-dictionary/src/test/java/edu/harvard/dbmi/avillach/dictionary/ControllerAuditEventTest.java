package edu.harvard.dbmi.avillach.dictionary;

import static org.junit.jupiter.api.Assertions.*;

import edu.harvard.dbmi.avillach.dictionary.concept.ConceptController;
import edu.harvard.dbmi.avillach.dictionary.dashboard.DashboardController;
import edu.harvard.dbmi.avillach.dictionary.dashboarddrawer.DashboardDrawerController;
import edu.harvard.dbmi.avillach.dictionary.facet.FacetController;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.info.InfoController;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.LegacySearchController;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

class ControllerAuditEventTest {

    private void assertAuditEvent(Class<?> controller, String methodName, Class<?>[] params, String expectedType, String expectedAction)
        throws Exception {
        Method method = controller.getMethod(methodName, params);
        AuditEvent event = method.getAnnotation(AuditEvent.class);
        assertNotNull(event, controller.getSimpleName() + "." + methodName + " missing @AuditEvent");
        assertEquals(expectedType, event.type(), controller.getSimpleName() + "." + methodName + " wrong type");
        assertEquals(expectedAction, event.action(), controller.getSimpleName() + "." + methodName + " wrong action");
    }

    @Test
    void conceptController() throws Exception {
        Class<?> c = ConceptController.class;
        assertAuditEvent(c, "listConcepts", new Class[] {Filter.class, int.class, int.class}, "SEARCH", "concept.search");
        assertAuditEvent(c, "dumpConcepts", new Class[] {int.class, int.class}, "DATA_ACCESS", "concept.dump");
        assertAuditEvent(c, "conceptDetail", new Class[] {String.class, String.class}, "SEARCH", "concept.detail");
        assertAuditEvent(c, "conceptsDetail", new Class[] {List.class}, "SEARCH", "concept.detail");
        assertAuditEvent(c, "conceptTree", new Class[] {String.class, String.class, Integer.class}, "SEARCH", "concept.tree");
        assertAuditEvent(c, "conceptHierarchy", new Class[] {String.class, String.class}, "SEARCH", "concept.hierarchy");
        assertAuditEvent(c, "allConceptTrees", new Class[] {Integer.class}, "SEARCH", "concept.tree");
    }

    @Test
    void facetController() throws Exception {
        Class<?> c = FacetController.class;
        assertAuditEvent(c, "getFacets", new Class[] {Filter.class}, "SEARCH", "facet.search");
        assertAuditEvent(c, "facetDetails", new Class[] {String.class, String.class}, "SEARCH", "facet.detail");
    }

    @Test
    void dashboardController() throws Exception {
        Class<?> c = DashboardController.class;
        assertAuditEvent(c, "getDashboard", new Class[] {}, "OTHER", "dashboard.read");
    }

    @Test
    void dashboardDrawerController() throws Exception {
        Class<?> c = DashboardDrawerController.class;
        assertAuditEvent(c, "findAll", new Class[] {}, "OTHER", "dashboard_drawer.list");
        assertAuditEvent(c, "findByDatasetId", new Class[] {Integer.class}, "OTHER", "dashboard_drawer.read");
    }

    @Test
    void infoController() throws Exception {
        Class<?> c = InfoController.class;
        assertAuditEvent(c, "getInfo", new Class[] {Object.class}, "OTHER", "info");
    }

    @Test
    void legacySearchController() throws Exception {
        Class<?> c = LegacySearchController.class;
        assertAuditEvent(c, "legacySearch", new Class[] {String.class}, "SEARCH", "search.legacy");
    }
}

package edu.harvard.dbmi.avillach.dictionary.dashboarddrawer;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Serves the per-study detail the PIC-SURE frontend shows in the dashboard drawer. Hidden from the OpenAPI document.
 */
@Controller
@RequestMapping("/dashboard-drawer")
@Hidden
public class DashboardDrawerController {

    @Autowired
    private DashboardDrawerService dashboardDrawerService;

    @AuditEvent(type = "OTHER", action = "dashboard_drawer.list")
    @GetMapping
    public ResponseEntity<List<DashboardDrawer>> findAll() {
        return dashboardDrawerService.findAll().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @AuditEvent(type = "OTHER", action = "dashboard_drawer.read")
    @GetMapping("/{id}")
    public ResponseEntity<DashboardDrawer> findByDatasetId(@PathVariable Integer id) {
        return dashboardDrawerService.findByDatasetId(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}

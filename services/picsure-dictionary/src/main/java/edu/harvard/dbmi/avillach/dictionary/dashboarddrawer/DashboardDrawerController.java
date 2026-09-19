package edu.harvard.dbmi.avillach.dictionary.dashboarddrawer;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/dashboard-drawer")
@Tag(name = "Dashboard drawer", description = "Per-study detail shown in the dashboard drawer")
public class DashboardDrawerController {

    @Autowired
    private DashboardDrawerService dashboardDrawerService;

    @Operation(summary = "Drawer detail for every study")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "404", description = "No dashboard drawer data configured")}
    )
    @AuditEvent(type = "OTHER", action = "dashboard_drawer.list")
    @GetMapping
    public ResponseEntity<List<DashboardDrawer>> findAll() {
        return dashboardDrawerService.findAll().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Drawer detail for one study")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "404", description = "No drawer data for that dataset id")}
    )
    @AuditEvent(type = "OTHER", action = "dashboard_drawer.read")
    @GetMapping("/{id}")
    public ResponseEntity<DashboardDrawer> findByDatasetId(@PathVariable Integer id) {
        return dashboardDrawerService.findByDatasetId(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}

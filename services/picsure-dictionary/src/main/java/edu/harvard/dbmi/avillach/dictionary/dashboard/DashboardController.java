package edu.harvard.dbmi.avillach.dictionary.dashboard;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@Tag(name = "Dashboard", description = "The study dashboard table")
public class DashboardController {
    private final DashboardService dashboardService;

    @Autowired
    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @Operation(summary = "The dashboard table")
    @ApiResponse(responseCode = "200", description = "The dashboard table rows")
    @AuditEvent(type = "OTHER", action = "dashboard.read")
    @GetMapping("/dashboard")
    public ResponseEntity<Dashboard> getDashboard() {
        return ResponseEntity.ok(dashboardService.getDashboard());
    }
}

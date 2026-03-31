package edu.harvard.dbmi.avillach.dictionary.dashboard;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {
    private final DashboardService dashboardService;

    @Autowired
    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @AuditEvent(type = "OTHER", action = "dashboard.read")
    @GetMapping("/dashboard")
    public ResponseEntity<Dashboard> getDashboard() {
        return ResponseEntity.ok(dashboardService.getDashboard());
    }
}

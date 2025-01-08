package edu.harvard.dbmi.avillach.dictionary.dashboarddrawer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/dashboard-drawer")
public class DashboardDrawerController {

    @Autowired
    private DashboardDrawerService dashboardDrawerService;

    @GetMapping
    public ResponseEntity<List<DashboardDrawer>> findAll() {
        return dashboardDrawerService.findAll().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<DashboardDrawer> findByDatasetId(@PathVariable Integer id) {
        return dashboardDrawerService.findByDatasetId(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}

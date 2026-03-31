package edu.harvard.dbmi.avillach.dump.local;

import edu.harvard.dbmi.avillach.dump.entities.DumpRow;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Controller
public class DumpController {
    private final DumpService dumpService;
    private final DateTimeFormatter isoFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    public DumpController(DumpService dumpService) {
        this.dumpService = dumpService;
    }

    @AuditEvent(type = "DATA_ACCESS", action = "dump.table")
    @GetMapping("/dump/{table}")
    public ResponseEntity<List<? extends DumpRow>> dumpTable(@PathVariable DumpTable table) {
        List<? extends DumpRow> rows = dumpService.dumpTable(table);
        return ResponseEntity.ok(rows);
    }

    @AuditEvent(type = "OTHER", action = "dump.last_updated")
    @GetMapping("/last-updated")
    public ResponseEntity<String> getLastUpdated() {
        return ResponseEntity.ok(dumpService.getLastUpdate().format(isoFormat));
    }

    @AuditEvent(type = "OTHER", action = "dump.db_version")
    @GetMapping("/database-version")
    public ResponseEntity<Integer> getDBVersion() {
        return ResponseEntity.ok(dumpService.getDatabaseVersion());
    }
}

package edu.harvard.dbmi.avillach.dump.local;

import edu.harvard.dbmi.avillach.dump.entities.DumpRow;
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

    @GetMapping("/dump/{table}")
    public ResponseEntity<List<? extends DumpRow>> dumpTable(@PathVariable DumpTable table) {
        List<? extends DumpRow> rows = dumpService.dumpTable(table);
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/last-updated")
    public ResponseEntity<String> getLastUpdated() {
        return ResponseEntity.ok(dumpService.getLastUpdate().format(isoFormat));
    }
}

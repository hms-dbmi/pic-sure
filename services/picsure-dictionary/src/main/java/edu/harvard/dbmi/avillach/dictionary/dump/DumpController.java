package edu.harvard.dbmi.avillach.dictionary.dump;

import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
public class DumpController {
    private final DumpService dumpService;

    public DumpController(DumpService dumpService) {
        this.dumpService = dumpService;
    }

    @GetMapping("/dump/{table}")
    public ResponseEntity<List<List<String>>> dumpTable(
        @PathVariable DumpTable table, @RequestParam(name = "page_number", defaultValue = "0", required = false) int page,
        @RequestParam(name = "page_size", defaultValue = "1000", required = false) int size
    ) {
        Pageable pageable = Pageable.ofSize(size).withPage(page);
        List<List<String>> rows = dumpService.getRowsForTable(pageable, table);
        return ResponseEntity.ok(rows);
    }
}

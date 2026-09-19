package edu.harvard.dbmi.avillach.dictionary.info;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.UUID;

@Controller
@Tag(name = "Info", description = "Resource identity for PIC-SURE clients")
public class InfoController {

    @Operation(summary = "Identify this resource")
    @AuditEvent(type = "OTHER", action = "info")
    @PostMapping("/info")
    public ResponseEntity<InfoResponse> getInfo(@RequestBody Object ignored) {
        return ResponseEntity.ok(new InfoResponse(UUID.nameUUIDFromBytes(":)".getBytes()), ":)", List.of()));
    }
}

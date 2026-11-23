package edu.harvard.hms.dbmi.avillach.conventions.auditfixtures.internalmisnamed;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Declares no class-level path. A public handler carries the prefix, and two handlers each map a public and an
 * internal path, which no single action can name.
 */
@RestController
public class MisnamedPublicController {

    @AuditEvent(type = "QUERY", action = "internal_thing.read")
    @GetMapping("/things/{thingId}")
    public String read() {
        return "";
    }

    @AuditEvent(type = "DATA_ACCESS", action = "thing.export")
    @PostMapping(path = {"/things/export", "/internal/things/export"})
    public String export() {
        return "";
    }

    @AuditEvent(type = "OTHER", action = "internal_thing.sync")
    @PostMapping({"/things/sync", "/internal/things/sync"})
    public String sync() {
        return "";
    }
}

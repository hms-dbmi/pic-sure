package edu.harvard.hms.dbmi.avillach.conventions.auditfixtures.internalnamed;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Declares no class-level path. One handler maps internal paths at the method level, one of them below another
 * segment, and carries the prefix. The others are public, including paths where {@code internal} is only part of
 * a segment or the name of a variable.
 */
@RestController
public class MixedThingController {

    @AuditEvent(type = "SEARCH", action = "internal_thing.search")
    @PostMapping(path = {"/internal/things/search/{backend}", "/service/internal/things/search"})
    public String search() {
        return "";
    }

    @AuditEvent(type = "QUERY", action = "thing.read")
    @GetMapping("/things/{thingId}")
    public String read() {
        return "";
    }

    @AuditEvent(type = "QUERY", action = "thing.list_internals")
    @GetMapping("/internals")
    public String internals() {
        return "";
    }

    @AuditEvent(type = "QUERY", action = "thing.read_by_variable")
    @GetMapping("/things/by/{internal}")
    public String byVariable() {
        return "";
    }
}

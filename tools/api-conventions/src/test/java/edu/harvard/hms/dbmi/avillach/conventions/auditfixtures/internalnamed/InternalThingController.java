package edu.harvard.hms.dbmi.avillach.conventions.auditfixtures.internalnamed;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sits under a class-level internal path and gives every labelled handler the prefix. Its unlabeled handler
 * belongs to {@code handler-has-audit-event}, not to the naming rule.
 */
@RestController
@RequestMapping("/internal/things")
public class InternalThingController {

    @AuditEvent(type = "OTHER", action = "internal_thing.save")
    @PostMapping
    public String save() {
        return "";
    }

    @AuditEvent(type = "OTHER", action = "internal_thing.read")
    @GetMapping("/{thingId}")
    public String read() {
        return "";
    }

    @GetMapping("/unlabeled")
    public String unlabeled() {
        return "";
    }
}

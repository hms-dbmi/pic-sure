package edu.harvard.hms.dbmi.avillach.conventions.auditfixtures.complete;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Labels every handler. */
@RestController
public class CompleteController {

    @AuditEvent(type = "QUERY", action = "thing.read")
    @GetMapping("/complete")
    public String read() {
        return "";
    }

    @AuditEvent(type = "ADMIN", action = "thing.delete")
    @DeleteMapping("/complete")
    public String delete() {
        return "";
    }
}

package edu.harvard.hms.dbmi.avillach.conventions.auditfixtures.partial;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Labels one handler and forgets the other. Its unmapped helper is not a handler and needs no label. */
@RestController
public class AuditedController {

    @AuditEvent(type = "QUERY", action = "thing.read")
    @GetMapping("/audited")
    public String read() {
        return helper();
    }

    @PostMapping("/audited")
    public String create() {
        return helper();
    }

    public String helper() {
        return "";
    }
}

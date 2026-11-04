package edu.harvard.hms.dbmi.avillach.conventions.auditfixtures.unaudited;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Belongs to a module where no handler carries the annotation, which still fails once per handler. */
@RestController
public class UnauditedController {

    @GetMapping("/unaudited")
    public String read() {
        return "";
    }
}

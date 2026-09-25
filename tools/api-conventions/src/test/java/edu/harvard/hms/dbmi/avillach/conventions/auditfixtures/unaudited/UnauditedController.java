package edu.harvard.hms.dbmi.avillach.conventions.auditfixtures.unaudited;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Belongs to a module that does not audit through the annotation at all. */
@RestController
public class UnauditedController {

    @GetMapping("/unaudited")
    public String read() {
        return "";
    }
}

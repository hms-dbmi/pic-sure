package edu.harvard.hms.dbmi.avillach.conventions.auditfixtures.partial;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A controller in an audited module whose handler carries no label, the way a new controller arrives. */
@RestController
public class ForgottenController {

    @GetMapping("/forgotten")
    public String consents() {
        return "";
    }
}

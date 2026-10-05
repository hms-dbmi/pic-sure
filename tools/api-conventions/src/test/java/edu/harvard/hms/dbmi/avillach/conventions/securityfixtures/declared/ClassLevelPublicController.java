package edu.harvard.hms.dbmi.avillach.conventions.securityfixtures.declared;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.hms.dbmi.avillach.openapi.PublicEndpoint;
import edu.harvard.hms.dbmi.avillach.openapi.PublicEndpoint.Access;

/** Declares its access once on the class, which the reader of a single handler would miss. */
@RestController
@PublicEndpoint(Access.AUTHENTICATED)
public class ClassLevelPublicController {

    @PublicEndpoint(Access.AUTHENTICATED)
    @GetMapping("/class-level")
    public String read() {
        return "";
    }
}

package edu.harvard.hms.dbmi.avillach.conventions.securityfixtures.declared;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.hms.dbmi.avillach.openapi.PublicEndpoint;
import edu.harvard.hms.dbmi.avillach.openapi.PublicEndpoint.Access;

/** Handlers that declare their access in each accepted way, one that declares nothing, and one that declares twice. */
@RestController
public class DeclaringController {

    @PreAuthorize("hasAnyAuthority('ADMIN')")
    @GetMapping("/guarded")
    public String guarded() {
        return "";
    }

    @PublicEndpoint(Access.ANONYMOUS)
    @GetMapping("/anonymous")
    public String anonymous() {
        return "";
    }

    @PublicEndpoint(Access.AUTHENTICATED)
    @GetMapping("/authenticated")
    public String authenticated() {
        return "";
    }

    @GetMapping("/undeclared")
    public String undeclared() {
        return "";
    }

    @PreAuthorize("hasAnyAuthority('ADMIN')")
    @PublicEndpoint(Access.AUTHENTICATED)
    @PostMapping("/both")
    public String both() {
        return "";
    }

    public String helper() {
        return "";
    }
}

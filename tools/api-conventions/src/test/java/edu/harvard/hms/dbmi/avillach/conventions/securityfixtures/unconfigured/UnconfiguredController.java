package edu.harvard.hms.dbmi.avillach.conventions.securityfixtures.unconfigured;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A guard in a module that never turns on method security, so nothing enforces it. */
@RestController
public class UnconfiguredController {

    @PreAuthorize("hasAnyAuthority('ADMIN')")
    @GetMapping("/unconfigured")
    public String read() {
        return "";
    }
}

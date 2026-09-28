package edu.harvard.hms.dbmi.avillach.conventions.securityfixtures.configured;

import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Every security annotation the standard replaces, on a class and on its handlers. */
@RolesAllowed("ADMIN")
@RestController
public class LegacyController {

    @RolesAllowed({"ADMIN", "SUPER_ADMIN"})
    @GetMapping("/legacy/roles")
    public String rolesAllowed() {
        return "";
    }

    @Secured("SUPER_ADMIN")
    @GetMapping("/legacy/secured")
    public String secured() {
        return "";
    }

    @PermitAll
    @GetMapping("/legacy/permit")
    public String permitAll() {
        return "";
    }

    @DenyAll
    @GetMapping("/legacy/deny")
    public String denyAll() {
        return "";
    }
}

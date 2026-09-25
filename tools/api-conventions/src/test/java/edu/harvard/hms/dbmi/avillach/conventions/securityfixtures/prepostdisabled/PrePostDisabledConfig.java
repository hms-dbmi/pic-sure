package edu.harvard.hms.dbmi.avillach.conventions.securityfixtures.prepostdisabled;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Method security switched on with pre/post support switched off, which leaves {@code @PreAuthorize} inert. */
@EnableMethodSecurity(prePostEnabled = false, jsr250Enabled = true)
@RestController
public class PrePostDisabledConfig {

    @PreAuthorize("hasAnyAuthority('ADMIN')")
    @GetMapping("/prepost-disabled")
    public String read() {
        return "";
    }
}

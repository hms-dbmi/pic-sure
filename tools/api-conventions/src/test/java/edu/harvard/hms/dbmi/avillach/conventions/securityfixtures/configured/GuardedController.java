package edu.harvard.hms.dbmi.avillach.conventions.securityfixtures.configured;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/** Handler guards in every accepted form, and the expression shapes the standard rejects. */
@RestController
public class GuardedController {

    @PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/any")
    public String anyAuthority() {
        return "";
    }

    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN','PRIV_DATA_ADMIN')")
    @GetMapping("/any-unspaced")
    public String anyAuthorityWithoutSpace() {
        return "";
    }

    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    @GetMapping("/one")
    public String oneAuthority() {
        return "";
    }

    @GetMapping("/open")
    public String unguarded() {
        return "";
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/role")
    public String role() {
        return "";
    }

    @PreAuthorize("hasAnyAuthority('ADMIN') and isAuthenticated()")
    @PostMapping("/compound")
    public String compound() {
        return "";
    }

    @PreAuthorize("hasAuthority('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/two-for-one")
    public String twoValuesForOne() {
        return "";
    }

    @PreAuthorize("hasAnyAuthority()")
    @DeleteMapping("/empty")
    public String empty() {
        return "";
    }

    @PreAuthorize("hasAnyAuthority('ADMIN', 'ADMIN')")
    @DeleteMapping("/repeated")
    public String repeated() {
        return "";
    }
}

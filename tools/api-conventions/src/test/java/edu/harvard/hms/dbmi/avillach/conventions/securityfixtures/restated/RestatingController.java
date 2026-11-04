package edu.harvard.hms.dbmi.avillach.conventions.securityfixtures.restated;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Documentation text that repeats what the guards already say, next to text that only looks similar. */
@Tag(name = "Restating", description = "Things only an ADMIN may change")
@RestController
public class RestatingController {

    @Operation(summary = "List things", description = "GET every thing, requires ADMIN or SUPER_ADMIN role")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/things")
    public String list() {
        return "";
    }

    @Operation(summary = "SUPER_ADMIN only: delete a thing")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @DeleteMapping("/things")
    public String delete() {
        return "";
    }

    @Operation(summary = "Ask an administrator", description = "ADMINISTRATOR and SUPER_ADMINS are not authority names")
    @PreAuthorize("hasAnyAuthority('ADMIN')")
    @PostMapping("/things")
    public String create() {
        return "";
    }
}

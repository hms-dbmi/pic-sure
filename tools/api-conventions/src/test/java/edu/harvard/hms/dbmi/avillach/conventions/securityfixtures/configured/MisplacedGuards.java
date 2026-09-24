package edu.harvard.hms.dbmi.avillach.conventions.securityfixtures.configured;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code @PreAuthorize} placed where the published document cannot see it. */
public final class MisplacedGuards {

    private MisplacedGuards() {}

    /** A class-level guard on a controller. */
    @PreAuthorize("hasAnyAuthority('ADMIN')")
    @RestController
    public static class ClassLevelController {

        @GetMapping("/class-level")
        public String read() {
            return "";
        }
    }

    /** A guard on a method that is not a request handler. */
    public static class GuardedService {

        @PreAuthorize("hasAnyAuthority('ADMIN')")
        public String work() {
            return "";
        }
    }
}

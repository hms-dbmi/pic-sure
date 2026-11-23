package edu.harvard.hms.dbmi.avillach.conventions.auditfixtures.internalmisnamed;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sits under a class-level path that ends in the internal segment and labels its handlers the way a public
 * controller would. The method path with no leading slash still lands below that segment. One action holds the
 * prefix in the middle, which is not where the rule wants it.
 */
@RestController
@RequestMapping("/internal")
public class MisnamedInternalController {

    @AuditEvent(type = "SEARCH", action = "search.execute")
    @PostMapping("/search/{backend}")
    public String search() {
        return "";
    }

    @AuditEvent(type = "OTHER", action = "thing.list")
    @GetMapping("things")
    public String list() {
        return "";
    }

    @AuditEvent(type = "OTHER", action = "thing.internal_sync")
    @PostMapping("/things/sync")
    public String sync() {
        return "";
    }
}

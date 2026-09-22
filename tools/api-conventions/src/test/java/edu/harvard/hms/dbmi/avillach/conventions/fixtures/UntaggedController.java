package edu.harvard.hms.dbmi.avillach.conventions.fixtures;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Carries neither Tag nor Hidden, so it violates R1. */
@RestController
public class UntaggedController {

    @GetMapping("/untagged")
    public String read() {
        return "";
    }
}

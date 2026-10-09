package edu.harvard.hms.dbmi.avillach.conventions.mappingpathfixtures;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Satisfies {@code no-trailing-slash}: with no class-level mapping, a method-level root path serves the root. */
@RestController
public class UnmappedClassController {

    @GetMapping("/")
    public String root() {
        return "";
    }
}

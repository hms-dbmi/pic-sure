package edu.harvard.hms.dbmi.avillach.conventions.mappingpathfixtures;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Satisfies R16: a root path, a handler with no path, and slash-less paths. */
@RestController
@RequestMapping("/")
public class RootController {

    @GetMapping("/")
    public String root() {
        return "";
    }

    @GetMapping
    public String unmapped() {
        return "";
    }

    @PutMapping({"/one", "/two/{id}"})
    public String update() {
        return "";
    }
}

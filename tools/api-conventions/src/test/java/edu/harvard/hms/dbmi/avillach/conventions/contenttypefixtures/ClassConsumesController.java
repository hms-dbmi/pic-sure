package edu.harvard.hms.dbmi.avillach.conventions.contenttypefixtures;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A class level consumes reaches a GET handler that sets none of its own. */
@RestController
@RequestMapping(path = "/class-consumes", consumes = "application/json")
public class ClassConsumesController {

    @GetMapping("/inherits")
    public String inherits() {
        return "";
    }

    @GetMapping(path = "/overrides", consumes = "*/*")
    public String overrides() {
        return "";
    }

    @PostMapping("/post")
    public String post() {
        return "";
    }
}

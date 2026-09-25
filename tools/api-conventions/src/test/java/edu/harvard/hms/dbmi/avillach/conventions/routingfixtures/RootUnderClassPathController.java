package edu.harvard.hms.dbmi.avillach.conventions.routingfixtures;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A method-level root path under a class path, which Spring serves with a trailing slash. */
@RestController
@RequestMapping("/dataset/named")
public class RootUnderClassPathController {

    @GetMapping("/")
    public String list() {
        return "";
    }

    @PostMapping("")
    public String create() {
        return "";
    }
}

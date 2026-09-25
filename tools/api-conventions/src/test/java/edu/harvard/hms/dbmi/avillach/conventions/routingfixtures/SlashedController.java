package edu.harvard.hms.dbmi.avillach.conventions.routingfixtures;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Trailing slashes at class level and in each property form R16 reads. */
@RestController
@RequestMapping("/slashed/")
public class SlashedController {

    @GetMapping("/list/")
    public String list() {
        return "";
    }

    @PostMapping(path = {"/fine", "/also/"})
    public String create() {
        return "";
    }

    @RequestMapping(value = "/request/")
    public String request() {
        return "";
    }

    @DeleteMapping("/fine")
    public String remove() {
        return "";
    }
}

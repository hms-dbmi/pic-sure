package edu.harvard.hms.dbmi.avillach.conventions.contenttypefixtures;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** Every GET handler shape R14 must flag, beside the shapes it must accept. */
@RestController
@RequestMapping("/consumes")
public class ConsumesController {

    @GetMapping(path = "/get-json", consumes = "application/json")
    public String getJson() {
        return "";
    }

    @GetMapping(path = "/get-mixed", consumes = {"*/*", "application/json"})
    public String getMixed() {
        return "";
    }

    @RequestMapping(path = "/request-get-json", method = RequestMethod.GET, consumes = "application/json")
    public String requestGetJson() {
        return "";
    }

    @RequestMapping(path = "/request-any-json", consumes = "application/json")
    public String requestAnyJson() {
        return "";
    }

    @GetMapping(path = "/get-any", consumes = "*/*")
    public String getAny() {
        return "";
    }

    @GetMapping("/get-plain")
    public String getPlain() {
        return "";
    }

    @PostMapping(path = "/post-json", consumes = "application/json")
    public String postJson() {
        return "";
    }

    @RequestMapping(path = "/request-post-json", method = RequestMethod.POST, consumes = "application/json")
    public String requestPostJson() {
        return "";
    }
}

package edu.harvard.hms.dbmi.avillach.conventions.requestmethodfixtures;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** Handlers mapped every way the rule has to tell apart, under a class-level path prefix. */
@RestController
@RequestMapping("/verbs")
public class VerbController {

    @RequestMapping("/any")
    public String anyVerb() {
        return "";
    }

    @RequestMapping(path = "/empty", method = {})
    public String emptyMethods() {
        return "";
    }

    @RequestMapping(path = "/one", method = RequestMethod.POST)
    public String oneVerb() {
        return "";
    }

    @RequestMapping(path = "/two", method = {RequestMethod.GET, RequestMethod.POST})
    public String twoVerbs() {
        return "";
    }

    @GetMapping("/composed")
    public String composed() {
        return "";
    }
}

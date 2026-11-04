package edu.harvard.hms.dbmi.avillach.conventions.requestmethodfixtures;

import org.springframework.web.bind.annotation.RequestMapping;

/** Carries a verbless mapping but is no controller, so Spring never routes to it. */
public class NotAController {

    @RequestMapping("/ignored")
    public String ignored() {
        return "";
    }
}

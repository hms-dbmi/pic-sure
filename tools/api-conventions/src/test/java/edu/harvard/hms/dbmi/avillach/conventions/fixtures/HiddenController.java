package edu.harvard.hms.dbmi.avillach.conventions.fixtures;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Hidden at class level, so its bare handler is exempt from the operation and response rules. */
@Hidden
@RestController
public class HiddenController {

    @GetMapping("/hidden")
    public String read() {
        return "";
    }
}

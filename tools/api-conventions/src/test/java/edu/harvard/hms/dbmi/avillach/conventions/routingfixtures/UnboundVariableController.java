package edu.harvard.hms.dbmi.avillach.conventions.routingfixtures;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Named path variables that no mapped path declares. */
@RestController
@RequestMapping("/user")
public class UnboundVariableController {

    @GetMapping("/me/consents")
    public String consents(@PathVariable("userId") String userId) {
        return userId;
    }

    @PutMapping(path = "/{id}")
    public String rename(@PathVariable(name = "userId") String userId) {
        return userId;
    }

    @GetMapping("/{userIdentifier}/roles")
    public String roles(@PathVariable("userId") String userId) {
        return userId;
    }
}

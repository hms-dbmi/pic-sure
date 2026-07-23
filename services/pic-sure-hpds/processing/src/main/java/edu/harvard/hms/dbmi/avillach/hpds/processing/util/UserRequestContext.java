package edu.harvard.hms.dbmi.avillach.hpds.processing.util;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.List;

@Component
@RequestScope
public class UserRequestContext {
    private List<String> userConsents = List.of();

    public List<String> getUserConsents() {
        return userConsents;
    }

    public UserRequestContext setUserConsents(List<String> userConsents) {
        this.userConsents = userConsents;
        return this;
    }
}

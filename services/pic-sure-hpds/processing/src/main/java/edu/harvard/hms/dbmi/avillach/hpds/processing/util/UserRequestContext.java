package edu.harvard.hms.dbmi.avillach.hpds.processing.util;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.Set;

@Component
@RequestScope
public class UserRequestContext {
    private Set<String> userConsents = Set.of();

    public Set<String> getUserConsents() {
        return userConsents;
    }

    public UserRequestContext setUserConsents(Set<String> userConsents) {
        this.userConsents = userConsents;
        return this;
    }
}

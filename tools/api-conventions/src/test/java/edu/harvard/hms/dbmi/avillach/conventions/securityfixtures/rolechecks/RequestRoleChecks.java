package edu.harvard.hms.dbmi.avillach.conventions.securityfixtures.rolechecks;

import java.util.function.Predicate;

import jakarta.servlet.http.HttpServletRequest;

/** Role checks against the servlet request, as a call and as a method reference. */
public class RequestRoleChecks {

    public boolean isAdmin(HttpServletRequest request) {
        return request.isUserInRole("ADMIN");
    }

    public Predicate<String> roleTest(HttpServletRequest request) {
        return request::isUserInRole;
    }
}

package edu.harvard.hms.dbmi.avillach.gateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class TokenRefreshResponseFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
        throws ServletException, IOException {
        chain.doFilter(req, resp);
        Object refreshed = req.getAttribute(PsamaIntrospectionFilter.ATTR_REFRESHED_TOKEN);
        if (refreshed != null) {
            resp.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + refreshed);
        }
    }
}

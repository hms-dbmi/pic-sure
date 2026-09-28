package edu.harvard.hms.dbmi.avillach.conventions.securityfixtures.configured;

import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/** Turns on pre/post method security, so this package's {@code @PreAuthorize} annotations are enforced. */
@EnableMethodSecurity
public class MethodSecurityConfig {
}

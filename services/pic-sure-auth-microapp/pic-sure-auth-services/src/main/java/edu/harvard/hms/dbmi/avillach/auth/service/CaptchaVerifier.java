package edu.harvard.hms.dbmi.avillach.auth.service;

/**
 * Verifies a CAPTCHA challenge response before a gated action (self-service API key generation, open-access session issuance). There is one
 * instance per purpose, each bound to its own widget and selected by its {@code captcha.<purpose>.provider} property; inject it by bean
 * name ({@code apiKeyCaptchaVerifier}, {@code sessionCaptchaVerifier}). Deployments without egress (or local dev) use the disabled
 * implementation, which accepts everything.
 */
public interface CaptchaVerifier {

    boolean verify(String captchaToken, String remoteIp);
}

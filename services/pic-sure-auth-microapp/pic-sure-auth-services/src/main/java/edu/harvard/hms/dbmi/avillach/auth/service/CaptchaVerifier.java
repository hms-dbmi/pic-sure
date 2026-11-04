package edu.harvard.hms.dbmi.avillach.auth.service;

/**
 * Verifies a CAPTCHA challenge response before a self-service API key is minted. Implementations are selected by the
 * {@code captcha.provider} property; deployments without egress (or local dev) use the disabled implementation, which accepts everything.
 */
public interface CaptchaVerifier {

    boolean verify(String captchaToken, String remoteIp);
}

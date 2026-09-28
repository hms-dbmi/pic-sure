package edu.harvard.hms.dbmi.avillach.auth.service.impl.captcha;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import edu.harvard.hms.dbmi.avillach.auth.service.CaptchaVerifier;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.captcha.CaptchaPurpose.Setting;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts only {@link CaptchaConfiguration} with both purposes pointed at a local fake siteverify endpoint. The fake accepts any secret, so
 * the replay tests exercise the expected-action check on its own.
 */
public class CaptchaConfigurationTest {

    private static final String API_KEY_SECRET = "api-key-test-secret";
    private static final String SESSION_SECRET = "session-test-secret";
    private static final String API_KEY_ACTION = "generate-api-key";
    private static final String SESSION_ACTION = "open-access-session";
    private static final String TOKEN = "XXXX.DUMMY.TOKEN";
    private static final String DISABLED = CaptchaProvider.DISABLED.value();
    private static final String TURNSTILE = CaptchaProvider.TURNSTILE.value();
    // a test that forgets to set a response gets a named rejection in the log instead of a handler NullPointerException
    private static final String NO_RESPONSE_SET = "{\"success\": false, \"error-codes\": [\"test-did-not-set-a-siteverify-response\"]}";

    private final List<String> postedSecrets = new CopyOnWriteArrayList<>();
    private volatile String siteVerifyBody = NO_RESPONSE_SET;
    private HttpServer siteVerifyServer;
    private String siteVerifyUrl;

    @BeforeEach
    public void startSiteVerify() throws IOException {
        siteVerifyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        siteVerifyServer.createContext("/siteverify", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            for (String field : form.split("&")) {
                if (field.startsWith("secret=")) {
                    postedSecrets.add(URLDecoder.decode(field.substring("secret=".length()), StandardCharsets.UTF_8));
                }
            }
            byte[] response = siteVerifyBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        siteVerifyServer.start();
        siteVerifyUrl = "http://127.0.0.1:" + siteVerifyServer.getAddress().getPort() + "/siteverify";
    }

    @AfterEach
    public void stopSiteVerify() {
        siteVerifyServer.stop(0);
    }

    private Map<String, String> properties(String apiKeyProvider, String sessionProvider) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("api.key.generation.enabled", "false");
        properties.put("api.key.allow.ungated.generation", "false");
        properties.put(CaptchaPurpose.API_KEY.property(Setting.PROVIDER), apiKeyProvider);
        properties.put(CaptchaPurpose.API_KEY.property(Setting.SECRET), API_KEY_SECRET);
        properties.put(CaptchaPurpose.API_KEY.property(Setting.EXPECTED_ACTION), API_KEY_ACTION);
        properties.put(CaptchaPurpose.API_KEY.property(Setting.URL), siteVerifyUrl);
        properties.put(CaptchaPurpose.SESSION.property(Setting.PROVIDER), sessionProvider);
        properties.put(CaptchaPurpose.SESSION.property(Setting.SECRET), SESSION_SECRET);
        properties.put(CaptchaPurpose.SESSION.property(Setting.EXPECTED_ACTION), SESSION_ACTION);
        properties.put(CaptchaPurpose.SESSION.property(Setting.URL), siteVerifyUrl);
        return properties;
    }

    private ApplicationContextRunner runner(Map<String, String> properties) {
        return new ApplicationContextRunner().withUserConfiguration(CaptchaConfiguration.class, CaptchaGateValidator.class)
            .withBean(HttpClient.class, HttpClients::createDefault, definition -> definition.setDestroyMethodName("close"))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues(properties.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new));
    }

    private ApplicationContextRunner runner(String apiKeyProvider, String sessionProvider) {
        return runner(properties(apiKeyProvider, sessionProvider));
    }

    private void respondWithAction(String action) {
        siteVerifyBody = "{\"success\": true, \"action\": \"" + action + "\"}";
    }

    @Test
    public void testBothPurposesAreDistinctInstancesWithTheirOwnSecretAndAction() {
        runner(TURNSTILE, TURNSTILE).run(context -> {
            CaptchaVerifier apiKey = context.getBean("apiKeyCaptchaVerifier", CaptchaVerifier.class);
            CaptchaVerifier session = context.getBean("sessionCaptchaVerifier", CaptchaVerifier.class);
            assertThat(apiKey).isInstanceOf(TurnstileCaptchaVerifier.class).isNotSameAs(session);
            assertThat(session).isInstanceOf(TurnstileCaptchaVerifier.class);

            respondWithAction(API_KEY_ACTION);
            assertThat(apiKey.verify(TOKEN, null)).isTrue();
            respondWithAction(SESSION_ACTION);
            assertThat(session.verify(TOKEN, null)).isTrue();

            assertThat(postedSecrets).containsExactly(API_KEY_SECRET, SESSION_SECRET);
        });
    }

    @Test
    public void testApiKeyTokenIsRejectedBySessionVerifier() {
        runner(TURNSTILE, TURNSTILE).run(context -> {
            respondWithAction(API_KEY_ACTION);

            assertThat(context.getBean("sessionCaptchaVerifier", CaptchaVerifier.class).verify(TOKEN, null)).isFalse();
        });
    }

    @Test
    public void testSessionTokenIsRejectedByApiKeyVerifier() {
        runner(TURNSTILE, TURNSTILE).run(context -> {
            respondWithAction(SESSION_ACTION);

            assertThat(context.getBean("apiKeyCaptchaVerifier", CaptchaVerifier.class).verify(TOKEN, null)).isFalse();
        });
    }

    @Test
    public void testEmptyOrAbsentActionIsRejectedByBothPurposes() {
        runner(TURNSTILE, TURNSTILE).run(context -> {
            for (String body : List.of("{\"success\": true, \"action\": \"\"}", "{\"success\": true}")) {
                siteVerifyBody = body;

                assertThat(context.getBean("apiKeyCaptchaVerifier", CaptchaVerifier.class).verify(TOKEN, null)).as(body).isFalse();
                assertThat(context.getBean("sessionCaptchaVerifier", CaptchaVerifier.class).verify(TOKEN, null)).as(body).isFalse();
            }
        });
    }

    @Test
    public void testApiKeyTurnstileWithSessionDisabled() {
        runner(TURNSTILE, DISABLED).run(context -> {
            CaptchaVerifier apiKey = context.getBean("apiKeyCaptchaVerifier", CaptchaVerifier.class);
            CaptchaVerifier session = context.getBean("sessionCaptchaVerifier", CaptchaVerifier.class);
            assertThat(apiKey).isInstanceOf(TurnstileCaptchaVerifier.class);
            assertThat(session).isInstanceOf(DisabledCaptchaVerifier.class);

            assertThat(session.verify(null, null)).isTrue();
            assertThat(apiKey.verify(null, null)).isFalse();
            respondWithAction(SESSION_ACTION);
            assertThat(apiKey.verify(TOKEN, null)).isFalse();
            respondWithAction(API_KEY_ACTION);
            assertThat(apiKey.verify(TOKEN, null)).isTrue();
            assertThat(postedSecrets).containsOnly(API_KEY_SECRET);
        });
    }

    @Test
    public void testSessionTurnstileWithApiKeyDisabled() {
        runner(DISABLED, TURNSTILE).run(context -> {
            CaptchaVerifier apiKey = context.getBean("apiKeyCaptchaVerifier", CaptchaVerifier.class);
            CaptchaVerifier session = context.getBean("sessionCaptchaVerifier", CaptchaVerifier.class);
            assertThat(apiKey).isInstanceOf(DisabledCaptchaVerifier.class);
            assertThat(session).isInstanceOf(TurnstileCaptchaVerifier.class);

            assertThat(apiKey.verify(null, null)).isTrue();
            assertThat(session.verify(null, null)).isFalse();
            respondWithAction(API_KEY_ACTION);
            assertThat(session.verify(TOKEN, null)).isFalse();
            respondWithAction(SESSION_ACTION);
            assertThat(session.verify(TOKEN, null)).isTrue();
            assertThat(postedSecrets).containsOnly(SESSION_SECRET);
        });
    }

    // preserves the old @ConditionalOnProperty(havingValue = ...) selection, which compared case-insensitively
    @Test
    public void testProviderIsCaseInsensitive() {
        runner(" Turnstile ", "DISABLED").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("apiKeyCaptchaVerifier")).isInstanceOf(TurnstileCaptchaVerifier.class);
            assertThat(context.getBean("sessionCaptchaVerifier")).isInstanceOf(DisabledCaptchaVerifier.class);
        });
    }

    @ParameterizedTest
    @CsvSource(
        {"API_KEY, SECRET", "API_KEY, EXPECTED_ACTION", "API_KEY, URL", "SESSION, SECRET", "SESSION, EXPECTED_ACTION", "SESSION, URL"}
    )
    public void testBlankTurnstileSettingStopsStartupNamingThePurpose(CaptchaPurpose purpose, Setting setting) {
        Map<String, String> properties = properties(TURNSTILE, TURNSTILE);
        properties.put(purpose.property(setting), " ");

        runner(properties).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(purpose.property(setting));
        });
    }

    @ParameterizedTest
    @EnumSource(CaptchaPurpose.class)
    public void testUnknownProviderStopsStartupNamingThePurpose(CaptchaPurpose purpose) {
        Map<String, String> properties = properties(DISABLED, DISABLED);
        properties.put(purpose.property(Setting.PROVIDER), "recaptcha");

        runner(properties).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(purpose.property(Setting.PROVIDER)).hasMessageContaining("recaptcha");
        });
    }

    @ParameterizedTest
    @EnumSource(CaptchaPurpose.class)
    public void testMissingPropertyStopsStartupNamingThePurpose(CaptchaPurpose purpose) {
        Map<String, String> properties = properties(DISABLED, DISABLED);
        properties.remove(purpose.property(Setting.PROVIDER));

        runner(properties).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(purpose.property(Setting.PROVIDER));
        });
    }

    @ParameterizedTest
    @EnumSource(CaptchaPurpose.class)
    public void testRelativeUrlStopsStartupNamingThePurpose(CaptchaPurpose purpose) {
        Map<String, String> properties = properties(TURNSTILE, TURNSTILE);
        properties.put(purpose.property(Setting.URL), "challenges.cloudflare.com/turnstile/v0/siteverify");

        runner(properties).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(purpose.property(Setting.URL));
        });
    }

    // an operator copying the api-key block into the session block must not start with two purposes on one widget
    @ParameterizedTest
    @EnumSource(value = Setting.class, names = {"SECRET", "EXPECTED_ACTION"})
    public void testSharedTurnstileValueStopsStartupNamingBothPurposes(Setting setting) {
        Map<String, String> properties = properties(TURNSTILE, TURNSTILE);
        properties.put(CaptchaPurpose.SESSION.property(setting), properties.get(CaptchaPurpose.API_KEY.property(setting)));

        runner(properties).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(CaptchaPurpose.API_KEY.property(setting))
                .hasMessageContaining(CaptchaPurpose.SESSION.property(setting));
        });
    }
}

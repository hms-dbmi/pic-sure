package edu.harvard.hms.dbmi.avillach.query.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.query.config.HpdsProperties;
import edu.harvard.hms.dbmi.avillach.query.config.HpdsProperties.RsMode;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsCommunicationException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Covers {@link HpdsRouter}: settings checks, eligibility, each mode, each serve fallback reason, and shadow sampling, in-flight dropping
 * and comparison counting. Rust calls are lambdas returning canned outcomes, so no HTTP is involved.
 */
class HpdsRouterTest {

    private static final byte[] JAVA_BODY = "1234".getBytes(StandardCharsets.UTF_8);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<HpdsRouter> routers = new ArrayList<>();
    private final List<String> persisted = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger javaCalls = new AtomicInteger();
    private final AtomicInteger rustCalls = new AtomicInteger();

    @AfterEach
    void stopRouters() {
        routers.forEach(HpdsRouter::destroy);
    }

    private static HpdsProperties props(RsMode mode) {
        HpdsProperties props = new HpdsProperties();
        props.getRs().setUrl("http://hpds-rs:8080/PIC-SURE");
        props.getRs().setMode(mode);
        props.getRs().setShadowSample(1.0);
        return props;
    }

    private HpdsRouter router(HpdsProperties props) {
        return router(props, () -> 0.0);
    }

    private HpdsRouter router(HpdsProperties props, DoubleSupplier random) {
        HpdsRouter router = new HpdsRouter(props, null, meters, random);
        routers.add(router);
        return router;
    }

    private HpdsRouter.Call<byte[]> call(String backend, String type, RustOutcome rust) {
        return new HpdsRouter.Call<>(backend, type, () -> {
            javaCalls.incrementAndGet();
            return JAVA_BODY;
        }, (client, apiBase, token, maxBody) -> {
            rustCalls.incrementAndGet();
            return rust;
        }, RustOutcome.Answered::body, body -> body);
    }

    private Routed<byte[]> route(HpdsRouter router, HpdsRouter.Call<byte[]> call) {
        return router.route(call, body -> {
            persisted.add(new String(body, StandardCharsets.UTF_8));
            return "stored-id";
        });
    }

    private static RustOutcome answered(int status, String body) {
        return new RustOutcome.Answered(status, body.getBytes(StandardCharsets.UTF_8), "rust-query-id");
    }

    private double count(String name, String... tags) {
        Counter counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    @Nested
    class Settings {

        @Test
        void defaultsAreOffWithTheFourCountTypes() {
            HpdsProperties props = new HpdsProperties();
            assertThat(props.getRs().getMode()).isEqualTo(RsMode.OFF);
            assertThat(props.getRs().getTypes())
                .containsExactly("COUNT", "CROSS_COUNT", "CATEGORICAL_CROSS_COUNT", "CONTINUOUS_CROSS_COUNT");
            assertThat(props.getRs().getShadowSample()).isEqualTo(0.1);
            assertThat(props.getRs().getShadowMaxInFlight()).isEqualTo(4);
            assertThat(props.getRs().getTimeoutMs()).isEqualTo(30000);
            assertThat(router(props).mode()).isEqualTo(RsMode.OFF);
        }

        @Test
        void anUnsetUrlTurnsRoutingOffWhateverTheMode() {
            HpdsProperties props = props(RsMode.SERVE);
            props.getRs().setUrl(" ");
            HpdsRouter router = router(props);

            assertThat(router.mode()).isEqualTo(RsMode.OFF);
            assertThat(router.eligible("auth", "COUNT")).isFalse();
        }

        @Test
        void aNullModeIsOff() {
            HpdsProperties props = props(null);
            assertThat(router(props).mode()).isEqualTo(RsMode.OFF);
        }

        @Test
        void typeNamesAreTrimmedAndCaseInsensitive() {
            HpdsProperties props = props(RsMode.SERVE);
            props.getRs().setTypes(new LinkedHashSet<>(List.of(" count ", "search_values", "")));
            HpdsRouter router = router(props);

            assertThat(router.eligible("auth", "COUNT")).isTrue();
            assertThat(router.eligible("auth", HpdsRouter.SEARCH_VALUES)).isTrue();
            assertThat(router.eligible("auth", "CROSS_COUNT")).isFalse();
        }

        @Test
        void anUnknownTypeStopsStartup() {
            HpdsProperties props = props(RsMode.SERVE);
            props.getRs().setTypes(Set.of("COUNTS"));

            assertThatThrownBy(() -> router(props)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("COUNTS")
                .hasMessageContaining("HPDS_RS_TYPES");
        }

        @ParameterizedTest
        @CsvSource(
            {"-0.1, 4, 1000, HPDS_RS_SHADOW_SAMPLE", "1.5, 4, 1000, HPDS_RS_SHADOW_SAMPLE", "0.5, 0, 1000, HPDS_RS_SHADOW_MAX_IN_FLIGHT",
                "0.5, 4, 0, HPDS_RS_TIMEOUT_MS"}
        )
        void outOfRangeValuesStopStartup(double sample, int maxInFlight, int timeoutMs, String setting) {
            HpdsProperties props = props(RsMode.SHADOW);
            props.getRs().setShadowSample(sample);
            props.getRs().setShadowMaxInFlight(maxInFlight);
            props.getRs().setTimeoutMs(timeoutMs);

            assertThatThrownBy(() -> router(props)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(setting);
        }

        @ParameterizedTest
        @ValueSource(strings = {"hpds-rs:8080/PIC-SURE", "ftp://hpds-rs/PIC-SURE", "http://", "http://hpds rs/PIC-SURE", "file:/tmp/x"})
        void aUrlThatIsNotHttpWithAHostStopsStartup(String url) {
            HpdsProperties props = props(RsMode.SERVE);
            props.getRs().setUrl(url);

            assertThatThrownBy(() -> router(props)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HPDS_RS_URL");
        }

        @ParameterizedTest
        @ValueSource(strings = {"PATIENTS", "DATAFRAME", "dataframe_timeseries", "DATAFRAME_PFB"})
        void anAsyncOnlyTypeStopsStartup(String type) {
            HpdsProperties props = props(RsMode.SERVE);
            props.getRs().setTypes(Set.of("COUNT", type));

            assertThatThrownBy(() -> router(props)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HPDS_RS_TYPES")
                .hasMessageContaining("asynchronously");
        }

        @Test
        void theRustApiBaseCarriesTheApiPath() {
            HpdsProperties props = props(RsMode.SERVE);
            props.setApiPath("/v3");
            List<String> bases = new ArrayList<>();
            HpdsRouter.Call<byte[]> call = new HpdsRouter.Call<>("auth", "COUNT", () -> JAVA_BODY, (client, apiBase, token, maxBody) -> {
                bases.add(apiBase + " " + token);
                return answered(200, "1");
            }, RustOutcome.Answered::body, body -> body);
            props.getRs().setToken("rs-token");

            route(router(props), call);

            assertThat(bases).containsExactly("http://hpds-rs:8080/PIC-SURE/v3 rs-token");
        }
    }

    @Nested
    class Eligibility {

        @Test
        void onlyTheAuthBackendIsEligible() {
            HpdsRouter router = router(props(RsMode.SERVE));
            assertThat(router.eligible("auth", "COUNT")).isTrue();
            assertThat(router.eligible("open", "COUNT")).isFalse();
            assertThat(router.eligible(null, "COUNT")).isFalse();
        }

        @Test
        void onlyConfiguredTypesAreEligible() {
            HpdsRouter router = router(props(RsMode.SERVE));
            assertThat(router.eligible("auth", "CROSS_COUNT")).isTrue();
            assertThat(router.eligible("auth", "CATEGORICAL_CROSS_COUNT")).isTrue();
            assertThat(router.eligible("auth", "CONTINUOUS_CROSS_COUNT")).isTrue();
            assertThat(router.eligible("auth", "VARIANT_COUNT_FOR_QUERY")).isFalse();
            assertThat(router.eligible("auth", HpdsRouter.SEARCH)).isFalse();
            assertThat(router.eligible("auth", null)).isFalse();
        }

        @Test
        void offMakesNothingEligible() {
            HpdsRouter router = router(props(RsMode.OFF));
            assertThat(router.eligible("auth", "COUNT")).isFalse();
        }

        @Test
        void anIneligibleCallGoesToJavaAloneWithNoLabel() {
            HpdsRouter router = router(props(RsMode.SERVE));

            Routed<byte[]> routed = route(router, call("open", "COUNT", answered(200, "9")));

            assertThat(routed.value()).isEqualTo(JAVA_BODY);
            assertThat(routed.hpdsBackend()).isNull();
            assertThat(rustCalls).hasValue(0);
            assertThat(persisted).containsExactly("1234");
        }
    }

    @Nested
    class Off {

        @Test
        void sendsNothingToRust() {
            HpdsRouter router = router(props(RsMode.OFF));

            Routed<byte[]> routed = route(router, call("auth", "COUNT", answered(200, "9")));

            assertThat(routed.value()).isEqualTo(JAVA_BODY);
            assertThat(routed.hpdsBackend()).isNull();
            assertThat(javaCalls).hasValue(1);
            assertThat(rustCalls).hasValue(0);
        }
    }

    @Nested
    class Serve {

        @Test
        void rustAnswersAndItsAnswerIsPersisted() {
            HpdsRouter router = router(props(RsMode.SERVE));

            Routed<byte[]> routed = route(router, call("auth", "COUNT", answered(200, "9")));

            assertThat(new String(routed.value(), StandardCharsets.UTF_8)).isEqualTo("9");
            assertThat(routed.hpdsBackend()).isEqualTo(HpdsRouter.BACKEND_RUST);
            assertThat(javaCalls).hasValue(0);
            assertThat(persisted).containsExactly("9");
            assertThat(count("hpds.rs.serve", "type", "COUNT", "outcome", "rust", "reason", "none")).isEqualTo(1);
        }

        @ParameterizedTest
        @CsvSource({"400, status-4xx", "404, status-4xx", "500, status-5xx", "503, status-5xx", "302, status-other"})
        void aNon2xxAnswerFallsBackToJava(int status, String reason) {
            HpdsRouter router = router(props(RsMode.SERVE));

            Routed<byte[]> routed = route(router, call("auth", "COUNT", answered(status, "refused")));

            assertThat(routed.value()).isEqualTo(JAVA_BODY);
            assertThat(routed.hpdsBackend()).isEqualTo(HpdsRouter.BACKEND_JAVA_FALLBACK);
            assertThat(persisted).containsExactly("1234");
            assertThat(count("hpds.rs.serve", "type", "COUNT", "outcome", "fallback", "reason", reason)).isEqualTo(1);
        }

        @ParameterizedTest
        @CsvSource({"CONNECT_ERROR, connect-error", "TIMEOUT, timeout", "IO_ERROR, io-error"})
        void aTransportFailureFallsBackToJava(RustFailure failure, String reason) {
            HpdsRouter router = router(props(RsMode.SERVE));

            Routed<byte[]> routed = route(router, call("auth", "COUNT", new RustOutcome.Failed(failure)));

            assertThat(routed.value()).isEqualTo(JAVA_BODY);
            assertThat(routed.hpdsBackend()).isEqualTo(HpdsRouter.BACKEND_JAVA_FALLBACK);
            assertThat(count("hpds.rs.serve", "type", "COUNT", "outcome", "fallback", "reason", reason)).isEqualTo(1);
        }

        @Test
        void anExceptionFromTheRustCallFallsBackToJavaAsAClientError() {
            HpdsRouter router = router(props(RsMode.SERVE));
            HpdsRouter.Call<byte[]> call = new HpdsRouter.Call<>("auth", "COUNT", () -> JAVA_BODY, (client, apiBase, token, maxBody) -> {
                throw new IllegalStateException("Connection pool shut down");
            }, RustOutcome.Answered::body, body -> body);

            Routed<byte[]> routed = route(router, call);

            assertThat(routed.value()).isEqualTo(JAVA_BODY);
            assertThat(routed.hpdsBackend()).isEqualTo(HpdsRouter.BACKEND_JAVA_FALLBACK);
            assertThat(count("hpds.rs.serve", "type", "COUNT", "outcome", "fallback", "reason", "client-error")).isEqualTo(1);
        }

        @Test
        void serveReadsTheWholeRustBody() {
            List<Long> limits = new ArrayList<>();
            HpdsRouter.Call<byte[]> call = new HpdsRouter.Call<>("auth", "COUNT", () -> JAVA_BODY, (client, apiBase, token, maxBody) -> {
                limits.add(maxBody);
                return answered(200, "9");
            }, RustOutcome.Answered::body, body -> body);

            route(router(props(RsMode.SERVE)), call);

            assertThat(limits).containsExactly(RustHpdsClient.UNLIMITED);
        }

        @Test
        void theLabelIsRecordedBeforeAFallbackJavaCallFails() {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
            try {
                HpdsRouter router = router(props(RsMode.SERVE));
                HpdsRouter.Call<byte[]> call = new HpdsRouter.Call<>("auth", "COUNT", () -> {
                    throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "refused");
                }, (client, apiBase, token, maxBody) -> answered(503, ""), RustOutcome.Answered::body, body -> body);

                assertThatThrownBy(() -> route(router, call)).isInstanceOf(PicsureException.class);
                assertThat(HpdsBackendLabel.current()).isEqualTo(HpdsRouter.BACKEND_JAVA_FALLBACK);
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        }

        @Test
        void anUnreadable2xxAnswerFallsBackToJava() {
            HpdsRouter.Call<byte[]> call = new HpdsRouter.Call<>(
                "auth", "SEARCH", () -> JAVA_BODY, (client, apiBase, token, maxBody) -> answered(200, "not json"), answered -> {
                    throw new IOException("unreadable");
                }, body -> body
            );
            HpdsProperties props = props(RsMode.SERVE);
            props.getRs().setTypes(Set.of("SEARCH"));

            Routed<byte[]> routed = route(router(props), call);

            assertThat(routed.value()).isEqualTo(JAVA_BODY);
            assertThat(routed.hpdsBackend()).isEqualTo(HpdsRouter.BACKEND_JAVA_FALLBACK);
            assertThat(count("hpds.rs.serve", "type", "SEARCH", "outcome", "fallback", "reason", "unreadable-answer")).isEqualTo(1);
        }

        @Test
        void aJavaFailureAfterFallbackReachesTheCaller() {
            HpdsRouter router = router(props(RsMode.SERVE));
            HpdsRouter.Call<byte[]> call = new HpdsRouter.Call<>("auth", "COUNT", () -> {
                throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "refused");
            }, (client, apiBase, token, maxBody) -> answered(400, "refused"), RustOutcome.Answered::body, body -> body);

            assertThatThrownBy(() -> route(router, call)).isInstanceOf(PicsureException.class);
            assertThat(persisted).isEmpty();
        }
    }

    @Nested
    class Shadow {

        @Test
        void javaAnswersAndTheMirrorIsCompared() throws InterruptedException {
            HpdsRouter router = router(props(RsMode.SHADOW));

            Routed<byte[]> routed = route(router, call("auth", "COUNT", answered(200, "1234")));
            assertThat(router.awaitShadowIdle(Duration.ofSeconds(5))).isTrue();

            assertThat(routed.value()).isEqualTo(JAVA_BODY);
            assertThat(routed.hpdsBackend()).isEqualTo(HpdsRouter.BACKEND_JAVA_SHADOW);
            assertThat(persisted).containsExactly("1234");
            assertThat(javaCalls).hasValue(1);
            assertThat(rustCalls).hasValue(1);
            assertThat(count("hpds.rs.shadow", "type", "COUNT", "result", "match", "normalization", "none")).isEqualTo(1);
        }

        @Test
        void aDifferentAnswerIsCountedAsAMismatch() throws InterruptedException {
            HpdsRouter router = router(props(RsMode.SHADOW));

            route(router, call("auth", "COUNT", answered(200, "1235")));
            assertThat(router.awaitShadowIdle(Duration.ofSeconds(5))).isTrue();

            assertThat(count("hpds.rs.shadow", "type", "COUNT", "result", "mismatch", "normalization", "exact")).isEqualTo(1);
        }

        @Test
        void aRustRefusalOfAnAnswerJavaGaveIsAMismatchAndNeverReachesTheCaller() throws InterruptedException {
            HpdsRouter router = router(props(RsMode.SHADOW));

            Routed<byte[]> routed = route(router, call("auth", "COUNT", answered(400, "refused")));
            assertThat(router.awaitShadowIdle(Duration.ofSeconds(5))).isTrue();

            assertThat(routed.value()).isEqualTo(JAVA_BODY);
            assertThat(count("hpds.rs.shadow", "type", "COUNT", "result", "mismatch", "normalization", "status-only")).isEqualTo(1);
        }

        @Test
        void anUnreachableRustIsNotCompared() throws InterruptedException {
            HpdsRouter router = router(props(RsMode.SHADOW));

            route(router, call("auth", "COUNT", new RustOutcome.Failed(RustFailure.TIMEOUT)));
            assertThat(router.awaitShadowIdle(Duration.ofSeconds(5))).isTrue();

            assertThat(count("hpds.rs.shadow", "type", "COUNT", "result", "not-compared", "reason", "timeout")).isEqualTo(1);
        }

        @Test
        void aJavaFailureIsStillMirroredAndThenRethrown() throws InterruptedException {
            HpdsRouter router = router(props(RsMode.SHADOW));
            HpdsRouter.Call<byte[]> call = new HpdsRouter.Call<>("auth", "COUNT", () -> {
                throw new HpdsCommunicationException(
                    "HPDS sync call failed", HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "boom", null, null, null)
                );
            }, (client, apiBase, token, maxBody) -> answered(500, "boom"), RustOutcome.Answered::body, body -> body);

            assertThatThrownBy(() -> route(router, call)).isInstanceOf(HpdsCommunicationException.class);
            assertThat(router.awaitShadowIdle(Duration.ofSeconds(5))).isTrue();

            assertThat(persisted).isEmpty();
            assertThat(count("hpds.rs.shadow", "type", "COUNT", "result", "match", "normalization", "status-only")).isEqualTo(1);
        }

        @Test
        void anExceptionFromTheMirroredRustCallIsNotComparedAsAClientError() throws InterruptedException {
            HpdsRouter router = router(props(RsMode.SHADOW));
            HpdsRouter.Call<byte[]> call = new HpdsRouter.Call<>("auth", "COUNT", () -> JAVA_BODY, (client, apiBase, token, maxBody) -> {
                throw new IllegalArgumentException("bad uri");
            }, RustOutcome.Answered::body, body -> body);

            Routed<byte[]> routed = route(router, call);
            assertThat(router.awaitShadowIdle(Duration.ofSeconds(5))).isTrue();

            assertThat(routed.value()).isEqualTo(JAVA_BODY);
            assertThat(count("hpds.rs.shadow", "type", "COUNT", "result", "not-compared", "reason", "client-error")).isEqualTo(1);
        }

        @Test
        void theMirrorReadsAtMost16MibOfTheRustBody() throws InterruptedException {
            List<Long> limits = Collections.synchronizedList(new ArrayList<>());
            HpdsRouter router = router(props(RsMode.SHADOW));
            HpdsRouter.Call<byte[]> call = new HpdsRouter.Call<>("auth", "COUNT", () -> JAVA_BODY, (client, apiBase, token, maxBody) -> {
                limits.add(maxBody);
                return new RustOutcome.Answered(200, new byte[(int) maxBody], null, true);
            }, RustOutcome.Answered::body, body -> body);

            route(router, call);
            assertThat(router.awaitShadowIdle(Duration.ofSeconds(5))).isTrue();

            assertThat(limits).containsExactly((long) ShadowComparator.MAX_COMPARED_BYTES);
            assertThat(count("hpds.rs.shadow", "type", "COUNT", "result", "not-compared", "reason", ShadowComparator.BODY_TOO_LARGE))
                .isEqualTo(1);
        }

        @Test
        void aMirroredJavaFailureIsLabelled() {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
            try {
                HpdsRouter router = router(props(RsMode.SHADOW));
                HpdsRouter.Call<byte[]> call = new HpdsRouter.Call<>("auth", "COUNT", () -> {
                    throw new PicsureException(HttpStatus.FORBIDDEN, "consent_denied", "no");
                }, (client, apiBase, token, maxBody) -> answered(403, ""), RustOutcome.Answered::body, body -> body);

                assertThatThrownBy(() -> route(router, call)).isInstanceOf(PicsureException.class);
                assertThat(HpdsBackendLabel.current()).isEqualTo(HpdsRouter.BACKEND_JAVA_SHADOW);
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        }

        @Test
        void anUnsampledCallIsNotMirroredAndCarriesNoLabel() {
            HpdsProperties props = props(RsMode.SHADOW);
            props.getRs().setShadowSample(0.1);
            HpdsRouter router = router(props, () -> 0.1);

            Routed<byte[]> routed = route(router, call("auth", "COUNT", answered(200, "1234")));

            assertThat(routed.hpdsBackend()).isNull();
            assertThat(rustCalls).hasValue(0);
        }

        @Test
        void aZeroSampleMirrorsNothing() {
            HpdsProperties props = props(RsMode.SHADOW);
            props.getRs().setShadowSample(0.0);
            HpdsRouter router = router(props, () -> 0.0);

            route(router, call("auth", "COUNT", answered(200, "1234")));

            assertThat(rustCalls).hasValue(0);
        }

        @Test
        void aCallOverTheInFlightLimitIsDroppedAndCountedNotQueued() throws InterruptedException {
            HpdsProperties props = props(RsMode.SHADOW);
            props.getRs().setShadowMaxInFlight(1);
            HpdsRouter router = router(props);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch started = new CountDownLatch(1);
            HpdsRouter.Call<byte[]> slow = new HpdsRouter.Call<>("auth", "COUNT", () -> JAVA_BODY, (client, apiBase, token, maxBody) -> {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return answered(200, "1234");
            }, RustOutcome.Answered::body, body -> body);

            Routed<byte[]> first = route(router, slow);
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            Routed<byte[]> second = route(router, call("auth", "COUNT", answered(200, "1234")));
            release.countDown();
            assertThat(router.awaitShadowIdle(Duration.ofSeconds(5))).isTrue();

            assertThat(first.hpdsBackend()).isEqualTo(HpdsRouter.BACKEND_JAVA_SHADOW);
            assertThat(second.hpdsBackend()).isNull();
            assertThat(second.value()).isEqualTo(JAVA_BODY);
            assertThat(rustCalls).hasValue(0);
            assertThat(count("hpds.rs.shadow.dropped", "type", "COUNT", "reason", "in-flight-limit")).isEqualTo(1);
        }
    }

    @Test
    void javaStatusIsReadFromTheFailure() {
        assertThat(HpdsRouter.javaStatusOf(new PicsureException(HttpStatus.FORBIDDEN, "consent_denied", "no"))).isEqualTo(403);
        assertThat(
            HpdsRouter.javaStatusOf(
                new HpdsCommunicationException("x", HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "x", null, null, null))
            )
        ).isEqualTo(502);
        assertThat(HpdsRouter.javaStatusOf(new HpdsCommunicationException("unreachable"))).isZero();
        assertThat(HpdsRouter.javaStatusOf(new IllegalStateException())).isZero();
    }
}

package edu.harvard.hms.dbmi.avillach.query.routing;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.query.config.HpdsProperties;
import edu.harvard.hms.dbmi.avillach.query.config.HpdsProperties.RsMode;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsCommunicationException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Decides, per call, whether Java HPDS, Rust HPDS or both see a request, following {@code HPDS_RS_MODE}.
 *
 * <p>A call is eligible when the mode is not {@code off}, {@code HPDS_RS_URL} is set, the backend is {@code auth}, and its type (a sync
 * query's {@code expectedResultType}, or {@value #SEARCH} or {@value #SEARCH_VALUES}) is in {@code HPDS_RS_TYPES}. Callers route only sync
 * queries and the two search calls, so async creates, status, result and signed-url always stay on Java. Consent scoping has already run
 * when a call reaches the router, so Java and Rust receive the same scoped request object.
 *
 * <ul> <li>{@code off}, or an ineligible call: Java answers alone.</li> <li>{@code serve}: Rust answers. A connect error, a timeout, a
 * non-2xx status or an unreadable 2xx body falls back to Java's answer for that call, and the fallback is counted by reason.</li>
 * <li>{@code shadow}: Java answers. After Java's answer (and its stored query id) is known, a sample of {@code HPDS_RS_SHADOW_SAMPLE} of
 * eligible calls is mirrored to Rust on a bounded executor; a call over {@code HPDS_RS_SHADOW_MAX_IN_FLIGHT} is dropped and counted, never
 * queued. The mirror compares the two answers with {@link ShadowComparator}.</li> </ul>
 *
 * <p>Log lines and metrics carry only the result type, the stored query id, the two statuses, the two latencies, match or mismatch, and the
 * normalization or failure name. They never carry a body, a count, or a digest of a body, because a short body such as a COUNT is
 * recoverable from its hash. Mismatch triage re-runs the stored query against both backends inside the boundary.
 */
@Component
public class HpdsRouter implements DisposableBean {

    /** The type name of the concept search call. */
    public static final String SEARCH = "SEARCH";
    /** The type name of the genomic concept values call. */
    public static final String SEARCH_VALUES = "SEARCH_VALUES";
    /**
     * Response header that carries a routed or mirrored call's audit label to the gateway, which records it as the audit event's
     * {@code hpds_backend} metadata and removes the header before the response leaves the gateway.
     */
    public static final String BACKEND_HEADER = "X-PIC-SURE-HPDS-Backend";
    /** Audit label of a call Rust answered. */
    public static final String BACKEND_RUST = "rust";
    /** Audit label of a call that fell back from Rust to Java in serve mode. */
    public static final String BACKEND_JAVA_FALLBACK = "java-fallback";
    /** Audit label of a call Java answered and that was mirrored to Rust in shadow mode. */
    public static final String BACKEND_JAVA_SHADOW = "java-shadow";

    private static final Logger logger = LoggerFactory.getLogger(HpdsRouter.class);
    private static final String NO_VALUE = "-";
    /** Result types served only by {@code POST /query}, which are never routed, so naming one in {@code HPDS_RS_TYPES} is a mistake. */
    private static final Set<String> ASYNC_TYPES = Set.of("PATIENTS", "DATAFRAME", "DATAFRAME_TIMESERIES", "DATAFRAME_PFB");

    private final RsMode mode;
    private final String rustApiBase;
    private final String rustToken;
    private final Set<String> types;
    private final double sample;
    private final int maxInFlight;
    private final RustHpdsClient rust;
    private final MeterRegistry meters;
    private final DoubleSupplier random;
    private final Semaphore permits;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ExecutorService executor;

    /**
     * Reads and checks the {@code hpds.rs.*} settings once.
     *
     * @param props the HPDS settings
     * @param rust the Rust HPDS client
     * @param meters where the routing metrics are registered
     * @throws IllegalArgumentException when {@code HPDS_RS_URL} is set but is not an http or https URL with a host, {@code HPDS_RS_TYPES}
     *         names an unknown type or one served only asynchronously, the sample is outside 0 to 1, or the in-flight limit or timeout is
     *         not positive
     */
    @Autowired
    public HpdsRouter(HpdsProperties props, RustHpdsClient rust, MeterRegistry meters) {
        this(props, rust, meters, () -> ThreadLocalRandom.current().nextDouble());
    }

    /**
     * As {@link #HpdsRouter(HpdsProperties, RustHpdsClient, MeterRegistry)}, with the source of the shadow sampling draws.
     *
     * @param random returns a value in {@code [0, 1)} per eligible call; a call is mirrored when the value is below the sample
     */
    HpdsRouter(HpdsProperties props, RustHpdsClient rust, MeterRegistry meters, DoubleSupplier random) {
        HpdsProperties.Rs rs = props.getRs();
        this.types = parseTypes(rs.getTypes());
        this.sample = rs.getShadowSample();
        if (!(sample >= 0.0 && sample <= 1.0)) {
            throw new IllegalArgumentException("HPDS_RS_SHADOW_SAMPLE must be between 0 and 1, was " + sample);
        }
        if (rs.getShadowMaxInFlight() < 1) {
            throw new IllegalArgumentException("HPDS_RS_SHADOW_MAX_IN_FLIGHT must be at least 1, was " + rs.getShadowMaxInFlight());
        }
        if (rs.getTimeoutMs() < 1) {
            throw new IllegalArgumentException("HPDS_RS_TIMEOUT_MS must be at least 1, was " + rs.getTimeoutMs());
        }
        this.maxInFlight = rs.getShadowMaxInFlight();
        RsMode configured = rs.getMode() == null ? RsMode.OFF : rs.getMode();
        boolean hasUrl = rs.getUrl() != null && !rs.getUrl().isBlank();
        if (hasUrl) {
            requireHttpUrl(rs.getUrl());
        }
        this.mode = hasUrl ? configured : RsMode.OFF;
        this.rustApiBase = hasUrl ? rs.getUrl().strip() + props.getApiPath() : null;
        this.rustToken = rs.getToken();
        this.rust = rust;
        this.meters = meters;
        this.random = random;
        this.permits = new Semaphore(maxInFlight);
        this.executor = mode == RsMode.SHADOW ? Executors.newFixedThreadPool(maxInFlight, shadowThreads()) : null;
        Gauge.builder("hpds.rs.mode", () -> 1).description("The Rust HPDS routing mode in effect, as the mode tag")
            .tag("mode", mode.name().toLowerCase(Locale.ENGLISH)).register(meters);
        Gauge.builder("hpds.rs.shadow.in_flight", inFlight, AtomicInteger::get).description("Mirrored Rust HPDS calls in flight")
            .register(meters);
        if (configured != RsMode.OFF && !hasUrl) {
            logger.warn("HPDS_RS_MODE is {} but HPDS_RS_URL is unset, so Rust HPDS routing is off", configured);
        }
        logger.info(
            "Rust HPDS routing: mode={} types={} shadow_sample={} shadow_max_in_flight={} timeout_ms={}", mode, types, sample, maxInFlight,
            rs.getTimeoutMs()
        );
    }

    /**
     * A router with routing off, for code that builds the services without Spring.
     *
     * @return a router that sends every call to Java alone
     */
    public static HpdsRouter off() {
        return new HpdsRouter(new HpdsProperties(), null, new SimpleMeterRegistry());
    }

    /** @return the routing mode in effect, {@link RsMode#OFF} whenever {@code HPDS_RS_URL} is unset */
    public RsMode mode() {
        return mode;
    }

    /**
     * Whether a call is routed or mirrored under the current settings.
     *
     * @param backend the ingress {@code {backend}} segment
     * @param type the result type name, {@value #SEARCH} or {@value #SEARCH_VALUES}; null is never eligible
     * @return true when the mode is not off, the backend is {@code auth} and the type is in {@code HPDS_RS_TYPES}
     */
    public boolean eligible(String backend, String type) {
        return mode != RsMode.OFF && HpdsBackendSelector.AUTH.equals(backend) && type != null && types.contains(type);
    }

    /**
     * Runs one call on the backend or backends the mode selects, persists the answer the caller receives, and in shadow mode mirrors it.
     *
     * @param call the two ways of answering the call
     * @param persist stores the answer the caller receives and returns its stored query id, or null when nothing was stored
     * @param <T> the answer type
     * @return the answer and its audit label
     * @throws RuntimeException whatever Java's call or {@code persist} throws, unchanged; Rust failures never reach the caller
     */
    public <T> Routed<T> route(Call<T> call, Function<T, String> persist) {
        if (!eligible(call.backend(), call.type())) {
            T value = call.java().get();
            persist.apply(value);
            return new Routed<>(value, null);
        }
        return mode == RsMode.SERVE ? serve(call, persist) : shadow(call, persist);
    }

    private <T> Routed<T> serve(Call<T> call, Function<T, String> persist) {
        long start = System.nanoTime();
        RustOutcome outcome = callRust(call, RustHpdsClient.UNLIMITED);
        long rustMs = elapsedMs(start);
        latency("rust", call.type()).record(rustMs, TimeUnit.MILLISECONDS);
        RustFailure failure = null;
        T value = null;
        if (outcome instanceof RustOutcome.Failed failed) {
            failure = failed.reason();
        } else if (outcome instanceof RustOutcome.Answered answered && !answered.successful()) {
            failure = RustFailure.forStatus(answered.status());
        } else {
            try {
                value = call.decode().decode((RustOutcome.Answered) outcome);
            } catch (IOException | RuntimeException e) {
                failure = RustFailure.UNREADABLE_ANSWER;
            }
        }
        if (failure == null) {
            serveCounter(call.type(), "rust", "none").increment();
            HpdsBackendLabel.record(BACKEND_RUST);
            persist.apply(value);
            return new Routed<>(value, BACKEND_RUST);
        }
        serveCounter(call.type(), "fallback", failure.label()).increment();
        logger
            .warn("hpds_rs_fallback type={} reason={} rust_status={} rust_ms={}", call.type(), failure.label(), statusOf(outcome), rustMs);
        HpdsBackendLabel.record(BACKEND_JAVA_FALLBACK);
        T javaValue = timedJava(call);
        persist.apply(javaValue);
        return new Routed<>(javaValue, BACKEND_JAVA_FALLBACK);
    }

    private <T> Routed<T> shadow(Call<T> call, Function<T, String> persist) {
        long start = System.nanoTime();
        T value;
        try {
            value = call.java().get();
        } catch (RuntimeException e) {
            long javaMs = elapsedMs(start);
            latency("java", call.type()).record(javaMs, TimeUnit.MILLISECONDS);
            if (mirror(call, javaStatusOf(e), null, javaMs, null)) {
                HpdsBackendLabel.record(BACKEND_JAVA_SHADOW);
            }
            throw e;
        }
        long javaMs = elapsedMs(start);
        latency("java", call.type()).record(javaMs, TimeUnit.MILLISECONDS);
        String storedId = persist.apply(value);
        boolean mirrored = mirror(call, 200, value, javaMs, storedId);
        if (mirrored) {
            HpdsBackendLabel.record(BACKEND_JAVA_SHADOW);
        }
        return new Routed<>(value, mirrored ? BACKEND_JAVA_SHADOW : null);
    }

    private <T> T timedJava(Call<T> call) {
        long start = System.nanoTime();
        try {
            return call.java().get();
        } finally {
            latency("java", call.type()).record(elapsedMs(start), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Hands a mirror of the call to the shadow executor when the call is sampled and a permit is free.
     *
     * @return whether the mirror was scheduled
     */
    private <T> boolean mirror(Call<T> call, int javaStatus, T javaValue, long javaMs, String storedId) {
        if (!(random.getAsDouble() < sample)) {
            return false;
        }
        if (!permits.tryAcquire()) {
            droppedCounter(call.type(), "in-flight-limit").increment();
            return false;
        }
        inFlight.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    compare(call, javaStatus, javaValue, javaMs, storedId);
                } finally {
                    inFlight.decrementAndGet();
                    permits.release();
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            inFlight.decrementAndGet();
            permits.release();
            droppedCounter(call.type(), "rejected").increment();
            return false;
        }
    }

    private <T> void compare(Call<T> call, int javaStatus, T javaValue, long javaMs, String storedId) {
        String queryId = storedId == null ? NO_VALUE : storedId;
        try {
            byte[] javaBody = javaValue == null ? null : call.javaBody().apply(javaValue);
            long start = System.nanoTime();
            RustOutcome outcome = callRust(call, ShadowComparator.MAX_COMPARED_BYTES);
            long rustMs = elapsedMs(start);
            latency("rust", call.type()).record(rustMs, TimeUnit.MILLISECONDS);
            ShadowComparator.Verdict verdict = ShadowComparator.compare(call.type(), javaStatus, javaBody, asCallerSees(call, outcome));
            String normalization = verdict.normalization() == null ? NO_VALUE : verdict.normalization();
            String reason = verdict.reason() == null ? NO_VALUE : verdict.reason();
            Counter.builder("hpds.rs.shadow").description("Mirrored Rust HPDS calls by comparison result").tag("type", call.type())
                .tag("result", verdict.result().label()).tag("normalization", normalization).tag("reason", reason).register(meters)
                .increment();
            String line =
                "hpds_rs_shadow type={} query_id={} java_status={} rust_status={} java_ms={} rust_ms={} result={} normalization={} "
                    + "reason={}";
            Object[] fields =
                {call.type(), queryId, javaStatus, statusOf(outcome), javaMs, rustMs, verdict.result().label(), normalization, reason};
            if (verdict.result() == ShadowComparator.Result.MISMATCH) {
                logger.warn(line, fields);
            } else {
                logger.info(line, fields);
            }
        } catch (RuntimeException e) {
            Counter.builder("hpds.rs.shadow").description("Mirrored Rust HPDS calls by comparison result").tag("type", call.type())
                .tag("result", "error").tag("normalization", NO_VALUE).tag("reason", e.getClass().getSimpleName()).register(meters)
                .increment();
            logger.warn("hpds_rs_shadow_error type={} query_id={} error={}", call.type(), queryId, e.getClass().getSimpleName());
        }
    }

    /**
     * Rust's outcome with a 2xx body passed through the same model the caller's answer goes through ({@link Call#decode} then
     * {@link Call#javaBody}), so the comparison sees what a caller would receive from either backend. A 2xx body the model cannot read
     * becomes a {@link RustOutcome.Failed} with {@link RustFailure#UNREADABLE_ANSWER}.
     */
    private static <T> RustOutcome asCallerSees(Call<T> call, RustOutcome outcome) {
        if (!(outcome instanceof RustOutcome.Answered answered) || !answered.successful() || answered.truncated()) {
            return outcome;
        }
        try {
            return new RustOutcome.Answered(
                answered.status(), call.javaBody().apply(call.decode().decode(answered)), answered.queryMetadata()
            );
        } catch (IOException | RuntimeException e) {
            return new RustOutcome.Failed(RustFailure.UNREADABLE_ANSWER);
        }
    }

    /**
     * Waits until no mirrored call is in flight. For tests that need a mirror's effects before asserting.
     *
     * @param timeout how long to wait
     * @return whether the router went idle within the timeout
     * @throws InterruptedException when interrupted while waiting
     */
    boolean awaitShadowIdle(Duration timeout) throws InterruptedException {
        if (!permits.tryAcquire(maxInFlight, timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            return false;
        }
        permits.release(maxInFlight);
        return true;
    }

    /** Stops the shadow executor; mirrors already running finish, queued ones never exist because permits bound the work. */
    @Override
    public void destroy() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    private Timer latency(String backend, String type) {
        return Timer.builder("hpds.rs.latency").description("HPDS call latency on routed and mirrored calls").tag("backend", backend)
            .tag("mode", mode.name().toLowerCase(Locale.ENGLISH)).tag("type", type).register(meters);
    }

    private Counter serveCounter(String type, String outcome, String reason) {
        return Counter.builder("hpds.rs.serve").description("Serve-mode calls by who answered and the fallback reason").tag("type", type)
            .tag("outcome", outcome).tag("reason", reason).register(meters);
    }

    private Counter droppedCounter(String type, String reason) {
        return Counter.builder("hpds.rs.shadow.dropped").description("Sampled calls not mirrored, by reason").tag("type", type)
            .tag("reason", reason).register(meters);
    }

    private static int statusOf(RustOutcome outcome) {
        return outcome instanceof RustOutcome.Answered answered ? answered.status() : 0;
    }

    /**
     * Java's HTTP status for a failed call: the status of a {@link PicsureException}, or of the response behind a
     * {@link HpdsCommunicationException}, or 0 when Java gave no HTTP answer.
     */
    static int javaStatusOf(RuntimeException e) {
        if (e instanceof PicsureException picsure) {
            return picsure.getStatus().value();
        }
        if (e instanceof HpdsCommunicationException && e.getCause() instanceof RestClientResponseException response) {
            return response.getStatusCode().value();
        }
        return 0;
    }

    /**
     * Runs the call's Rust side, turning any exception it throws into {@link RustFailure#CLIENT_ERROR}, so no Rust failure reaches the
     * caller.
     *
     * @param maxBodyBytes the most body bytes kept, or {@link RustHpdsClient#UNLIMITED}
     */
    private <T> RustOutcome callRust(Call<T> call, long maxBodyBytes) {
        try {
            return call.rust().call(rust, rustApiBase, rustToken, maxBodyBytes);
        } catch (RuntimeException e) {
            return new RustOutcome.Failed(RustFailure.CLIENT_ERROR);
        }
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /**
     * Checks that {@code HPDS_RS_URL} is an absolute http or https URL with a host.
     *
     * @throws IllegalArgumentException naming the setting when it is not
     */
    private static void requireHttpUrl(String url) {
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("HPDS_RS_URL is not a valid URL: " + e.getReason());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ENGLISH);
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
            throw new IllegalArgumentException(
                "HPDS_RS_URL must be an http or https URL with a host, such as http://hpds-rs:8080/PIC-SURE"
            );
        }
    }

    private static Set<String> parseTypes(Set<String> configured) {
        Set<String> known = Arrays.stream(ResultType.values()).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
        known.add(SEARCH);
        known.add(SEARCH_VALUES);
        Set<String> parsed = new LinkedHashSet<>();
        for (String raw : configured == null ? Set.<String>of() : configured) {
            String name = raw == null ? "" : raw.strip().toUpperCase(Locale.ENGLISH);
            if (name.isEmpty()) {
                continue;
            }
            if (!known.contains(name)) {
                throw new IllegalArgumentException("HPDS_RS_TYPES names an unknown type '" + raw.strip() + "'; known types are " + known);
            }
            if (ASYNC_TYPES.contains(name)) {
                throw new IllegalArgumentException(
                    "HPDS_RS_TYPES names " + name + ", which is only served asynchronously and stays on Java HPDS; remove it"
                );
            }
            parsed.add(name);
        }
        return Set.copyOf(parsed);
    }

    private static ThreadFactory shadowThreads() {
        AtomicInteger next = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "hpds-rs-shadow-" + next.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * One routable call: how Java answers it, how Rust answers it, how a Rust answer becomes the caller's answer, and how Java's answer is
     * turned into bytes for comparison.
     *
     * @param backend the ingress {@code {backend}} segment
     * @param type the result type name, {@value #SEARCH} or {@value #SEARCH_VALUES}, or null when the query names none
     * @param java runs the call on Java HPDS
     * @param rust runs the same call on Rust HPDS
     * @param decode turns a 2xx Rust answer into the caller's answer
     * @param javaBody turns Java's answer into the bytes compared with Rust's body
     * @param <T> the answer type
     */
    public record Call<T>(
        String backend, String type, Supplier<T> java, RustCall rust, RustDecoder<T> decode, Function<T, byte[]> javaBody
    ) {
    }

    /** Runs a call on Rust HPDS. */
    @FunctionalInterface
    public interface RustCall {

        /**
         * @param client the Rust HPDS client
         * @param apiBase the Rust API base URL, the API path included
         * @param token the Rust service token, possibly null
         * @param maxBodyBytes the most body bytes to keep, or {@link RustHpdsClient#UNLIMITED}
         * @return the answer or the failure
         */
        RustOutcome call(RustHpdsClient client, String apiBase, String token, long maxBodyBytes);
    }

    /** Turns a 2xx Rust answer into the caller's answer. */
    @FunctionalInterface
    public interface RustDecoder<T> {

        /**
         * @param answered a 2xx Rust answer
         * @return the caller's answer
         * @throws IOException when the body cannot be read as the expected response
         */
        T decode(RustOutcome.Answered answered) throws IOException;
    }
}

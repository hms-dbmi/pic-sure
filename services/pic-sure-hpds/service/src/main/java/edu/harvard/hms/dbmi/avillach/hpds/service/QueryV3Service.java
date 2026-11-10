package edu.harvard.hms.dbmi.avillach.hpds.service;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.DictionaryService;
import edu.harvard.hms.dbmi.avillach.hpds.processing.io.CsvWriter;
import edu.harvard.hms.dbmi.avillach.hpds.processing.io.PfbWriter;
import edu.harvard.hms.dbmi.avillach.hpds.processing.io.ResultWriter;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.*;
import edu.harvard.dbmi.avillach.util.UUIDv5;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

@Service
public class QueryV3Service {


    private final int SMALL_JOB_LIMIT;

    private static final Logger log = LoggerFactory.getLogger(QueryV3Service.class);

    private final BlockingQueue<Runnable> largeTaskExecutionQueue;

    private final ExecutorService largeTaskExecutor;

    private final BlockingQueue<Runnable> smallTaskExecutionQueue;

    private final ExecutorService smallTaskExecutor;

    private final TimeseriesV3Processor timeseriesProcessor;
    private final CountV3Processor countProcessor;
    private final MultiValueQueryV3Processor multiValueQueryProcessor;
    private final PatientV3Processor patientProcessor;

    private final DictionaryService dictionaryService;

    private final QueryValidator queryValidator;
    private final LoggingClient loggingClient;

    final ConcurrentHashMap<String, AsyncResult> results = new ConcurrentHashMap<>();


    @Autowired
    public QueryV3Service(
        TimeseriesV3Processor timeseriesProcessor, CountV3Processor countProcessor, MultiValueQueryV3Processor multiValueQueryProcessor,
        @Autowired(required = false) DictionaryService dictionaryService, QueryValidator queryValidator,
        @Value("${SMALL_JOB_LIMIT}") Integer smallJobLimit, @Value("${SMALL_TASK_THREADS}") Integer smallTaskThreads,
        @Value("${LARGE_TASK_THREADS}") Integer largeTaskThreads, PatientV3Processor patientProcessor,
        @Autowired(required = false) LoggingClient loggingClient
    ) {
        this.timeseriesProcessor = timeseriesProcessor;
        this.countProcessor = countProcessor;
        this.multiValueQueryProcessor = multiValueQueryProcessor;
        this.dictionaryService = dictionaryService;
        this.queryValidator = queryValidator;

        SMALL_JOB_LIMIT = smallJobLimit;
        this.patientProcessor = patientProcessor;
        this.loggingClient = loggingClient;


        /*
         * These have to be of type Runnable(nothing more specific) in order to be compatible with ThreadPoolExecutor constructor prototype
         */
        largeTaskExecutionQueue = new PriorityBlockingQueue<Runnable>(1000);
        smallTaskExecutionQueue = new PriorityBlockingQueue<Runnable>(1000);

        largeTaskExecutor = createExecutor(largeTaskExecutionQueue, largeTaskThreads);
        smallTaskExecutor = createExecutor(smallTaskExecutionQueue, smallTaskThreads);
    }

    /**
     * Submits a query for asynchronous execution under the key from {@link #resultKeyFor(Query)}.
     *
     * <p>The query is validated before the result map is read or changed. A query the validator rejects with an
     * {@link IllegalArgumentException} gets a job in {@code ERROR} that is returned but never stored, so whatever the key held stays as it
     * was. Any other validation failure is thrown to the caller, and also leaves the key untouched.
     *
     * <p>A valid submit returns the job that is still queued or running under the key, and enqueues nothing, when that job was built from
     * the same query, as {@link #isSameQuery} defines it. In every other case a new job built from the submitted query takes over the key
     * and is enqueued: the key is unused, the stored job finished or failed, or the stored job was built from a query that differs in
     * anything that shapes the result. A replaced job that is still queued or running is not cancelled. It runs to its end, but no lookup
     * by key reaches it again.
     *
     * <p>The new job and its temporary file are built before the result map is locked, so a slow build never holds up another submit. When
     * two submits race, the map keeps one job and the other is discarded together with its file.
     *
     * @param query the query to run
     * @return the job stored under the key with its queue depth set, or an unstored job in {@code ERROR} for a rejected query
     * @throws IOException when the temporary result file cannot be created
     */
    public AsyncResult runQuery(Query query) throws IOException {
        HpdsV3Processor processor = processorFor(query);
        String queryId = resultKeyFor(query).toString();

        try {
            queryValidator.validate(query);
        } catch (IllegalArgumentException e) {
            AsyncResult rejected = newResult(query, processor, queryId).setStatus(AsyncResult.Status.ERROR);
            discard(rejected);
            return rejected;
        }

        AsyncResult stored = results.get(queryId);
        if (isReusableFor(stored, query)) {
            return withQueueDepth(stored);
        }

        AsyncResult candidate = newResult(query, processor, queryId);
        stored = results.compute(queryId, (key, existing) -> isReusableFor(existing, query) ? existing : candidate);
        if (stored != candidate) {
            discard(candidate);
            return withQueueDepth(stored);
        }

        enqueue(candidate);
        sendEnqueuedEvent(candidate);
        return withQueueDepth(candidate);
    }

    /**
     * Picks the processor for a query's result type.
     *
     * @param query the submitted query
     * @return the processor that computes the result
     * @throws RuntimeException when the result type has no asynchronous processor
     */
    private HpdsV3Processor processorFor(Query query) {
        return switch (query.expectedResultType()) {
            case PATIENTS -> patientProcessor;
            case DATAFRAME_TIMESERIES -> timeseriesProcessor;
            case COUNT, CATEGORICAL_CROSS_COUNT, CONTINUOUS_CROSS_COUNT -> countProcessor;
            case DATAFRAME_PFB, DATAFRAME -> multiValueQueryProcessor;
            default -> throw new RuntimeException("UNSUPPORTED RESULT TYPE");
        };
    }

    /**
     * Builds a pending job with its own temporary result file and its executor already chosen. The job is neither stored nor enqueued. When
     * the build fails after the file was created, the file is deleted.
     *
     * @param query the submitted query
     * @param processor the processor for the query's result type
     * @param queryId the key the job is stored under
     * @return the new job
     * @throws IOException when the temporary result file cannot be created
     */
    private AsyncResult newResult(Query query, HpdsV3Processor processor, String queryId) throws IOException {
        boolean pfb = ResultType.DATAFRAME_PFB.equals(query.expectedResultType());
        File file = File.createTempFile("result-" + System.nanoTime(), pfb ? ".avro" : ".sstmp");
        try {
            ResultWriter writer = pfb ? new PfbWriter(file, queryId, dictionaryService) : new CsvWriter(file);
            return new AsyncResult(query.generateId(), processor, writer).setStatus(AsyncResult.Status.PENDING)
                .setQueuedTime(System.currentTimeMillis()).setId(queryId).setLoggingClient(loggingClient)
                .setJobQueue(runsOnSmallExecutor(query) ? smallTaskExecutor : largeTaskExecutor);
        } catch (RuntimeException e) {
            Files.deleteIfExists(file.toPath());
            throw e;
        }
    }

    /**
     * Closes the writer of a job that will never run and deletes its temporary file.
     *
     * @param unused a job that is not stored and not enqueued
     */
    private static void discard(AsyncResult unused) {
        try {
            unused.closeWriter();
            Files.deleteIfExists(unused.getTempFilePath());
        } catch (IOException | RuntimeException e) {
            log.warn("Could not remove the temporary file of an unused job", e);
        }
    }

    /**
     * Hands a stored job to its executor. A job the executor does not accept, for any reason, is left in {@code ERROR}, so a stored job is
     * always either enqueued or in a terminal state.
     *
     * @param result the stored job
     */
    private static void enqueue(AsyncResult result) {
        try {
            result.enqueue();
        } catch (RuntimeException | Error e) {
            result.setStatus(AsyncResult.Status.ERROR);
            throw e;
        }
    }

    /**
     * Sends the audit event for an enqueued job. A failure to send is logged and never reaches the caller.
     *
     * @param result the enqueued job
     */
    private void sendEnqueuedEvent(AsyncResult result) {
        try {
            if (loggingClient != null && loggingClient.isEnabled()) {
                Query query = result.getQuery();
                loggingClient.send(
                    LoggingEvent.builder("QUERY").action("query.enqueued")
                        .metadata(
                            Map.of(
                                "query_id", result.getId(), "result_type", String.valueOf(query.expectedResultType()), "field_count",
                                String.valueOf(query.select().size()), "queue", runsOnSmallExecutor(query) ? "small" : "large"
                            )
                        ).build()
                );
            }
        } catch (Exception e) {
            log.warn("Failed to send audit log event", e);
        }
    }

    /**
     * Tells whether a submit can take the stored job instead of creating one.
     *
     * @param stored the job under the submit's key, or null when the key is unused
     * @param submitted the query of the new submit
     * @return true when the stored job is still queued or running and was built from the same query as the submit
     */
    private static boolean isReusableFor(AsyncResult stored, Query submitted) {
        return stored != null && isInFlight(stored) && isSameQuery(stored.getQuery(), submitted);
    }

    /**
     * Tells whether a job is still queued or running.
     *
     * @param result the stored job
     * @return true for {@code PENDING}, {@code RUNNING} and {@code RETRY}
     */
    private static boolean isInFlight(AsyncResult result) {
        return switch (result.getStatus()) {
            case PENDING, RUNNING, RETRY -> true;
            case SUCCESS, ERROR -> false;
        };
    }

    /**
     * Tells whether two queries ask for the same result, so that a job built from one can serve a submit of the other.
     *
     * <p>Every component that shapes the result has to be equal: {@code select}, {@code authorizationFilters}, {@code phenotypicClause},
     * {@code genomicFilters}, {@code expectedResultType} and {@code sample}. The authorization filters are compared as sets, so their order
     * does not matter. Everything else is compared as it stands: {@code select} in order, because its order is the column order of an
     * export, and the clauses of a subquery and the genomic filters in order too, so a reordered query counts as a different one and gets a
     * new job. An absent list equals an empty one.
     *
     * <p>{@code id} and {@code picsureId} are not compared. {@code id} is a random value given to each job's query, and {@code picsureId}
     * is the key both queries were already found under.
     *
     * @param running the query of the stored job
     * @param submitted the query of the new submit
     * @return true when both ask for the same result
     */
    private static boolean isSameQuery(Query running, Query submitted) {
        return hasSameAuthorizationFilters(running, submitted) && running.select().equals(submitted.select())
            && Objects.equals(running.phenotypicClause(), submitted.phenotypicClause())
            && running.genomicFilters().equals(submitted.genomicFilters()) && running.expectedResultType() == submitted.expectedResultType()
            && running.sample() == submitted.sample();
    }

    /**
     * Compares the authorization filters of two queries as sets, so the order of the filters does not matter.
     *
     * @param running the query of the stored job
     * @param submitted the query of the new submit
     * @return true when both carry the same filters
     */
    private static boolean hasSameAuthorizationFilters(Query running, Query submitted) {
        return new HashSet<>(running.authorizationFilters()).equals(new HashSet<>(submitted.authorizationFilters()));
    }

    /**
     * Looks up the job stored under an id and sets its queue depth.
     *
     * @param queryId the result key
     * @return the job, or null when no job is stored under the id
     */
    public AsyncResult getStatusFor(String queryId) {
        AsyncResult asyncResult = results.get(queryId);
        // note: code copied from this method in QueryService was removed, it was obviously not working
        return asyncResult == null ? null : withQueueDepth(asyncResult);
    }

    /**
     * Sets a job's queue depth to the number of jobs waiting on the executor it belongs to.
     *
     * @param asyncResult the job
     * @return the same job
     */
    private AsyncResult withQueueDepth(AsyncResult asyncResult) {
        int queueDepth = runsOnSmallExecutor(asyncResult.getQuery()) ? smallTaskExecutionQueue.size() : largeTaskExecutionQueue.size();
        return asyncResult.setQueueDepth(queueDepth);
    }

    /**
     * Decides which executor a query belongs to. A sample query always runs on the small executor, whatever its column count, so it never
     * waits behind a long full export. Any other query runs there only when it selects no more than {@code SMALL_JOB_LIMIT} columns.
     *
     * @param query the submitted query
     * @return true for the small executor, false for the large one
     */
    private boolean runsOnSmallExecutor(Query query) {
        return query.sample() || query.select().size() <= SMALL_JOB_LIMIT;
    }

    /**
     * Returns the key a query's result is stored under. A query that carries a {@code picsureId} is stored under that id, so the caller
     * chooses the key. Any other query is stored under a UUIDv5 hash of its content.
     *
     * @param query the query
     * @return the result key
     */
    public static UUID resultKeyFor(Query query) {
        return query.picsureId() != null ? query.picsureId() : UUIDv5.UUIDFromString(query.toString());
    }

    public AsyncResult getResultFor(UUID queryId) {
        return results.get(queryId.toString());
    }

    private ExecutorService createExecutor(BlockingQueue<Runnable> taskQueue, int numThreads) {
        return new ThreadPoolExecutor(1, Math.max(2, numThreads), 10, TimeUnit.MINUTES, taskQueue);
    }

}

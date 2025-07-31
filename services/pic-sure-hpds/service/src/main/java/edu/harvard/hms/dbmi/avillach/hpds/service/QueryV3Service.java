package edu.harvard.hms.dbmi.avillach.hpds.service;

import edu.harvard.dbmi.avillach.util.UUIDv5;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.DictionaryService;
import edu.harvard.hms.dbmi.avillach.hpds.processing.io.CsvWriter;
import edu.harvard.hms.dbmi.avillach.hpds.processing.io.PfbWriter;
import edu.harvard.hms.dbmi.avillach.hpds.processing.io.ResultWriter;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class QueryV3Service {


    private final int SMALL_JOB_LIMIT;

    private static final Logger log = LoggerFactory.getLogger(QueryV3Service.class);

    private final BlockingQueue<Runnable> largeTaskExecutionQueue;

    private final ExecutorService largeTaskExecutor;

    private final BlockingQueue<Runnable> smallTaskExecutionQueue;

    private final ExecutorService smallTaskExecutor;

    private final QueryV3Processor queryProcessor;
    private final TimeseriesV3Processor timeseriesProcessor;
    private final CountV3Processor countProcessor;
    private final MultiValueQueryV3Processor multiValueQueryProcessor;
    private final PatientV3Processor patientProcessor;

    private final DictionaryService dictionaryService;

    private final QueryValidator queryValidator;

    HashMap<String, AsyncResult> results = new HashMap<>();


    @Autowired
    public QueryV3Service(
        QueryV3Processor queryProcessor, TimeseriesV3Processor timeseriesProcessor, CountV3Processor countProcessor,
        MultiValueQueryV3Processor multiValueQueryProcessor, @Autowired(required = false) DictionaryService dictionaryService,
        QueryValidator queryValidator, @Value("${SMALL_JOB_LIMIT}") Integer smallJobLimit,
        @Value("${SMALL_TASK_THREADS}") Integer smallTaskThreads, @Value("${LARGE_TASK_THREADS}") Integer largeTaskThreads,
        PatientV3Processor patientProcessor
    ) {
        this.queryProcessor = queryProcessor;
        this.timeseriesProcessor = timeseriesProcessor;
        this.countProcessor = countProcessor;
        this.multiValueQueryProcessor = multiValueQueryProcessor;
        this.dictionaryService = dictionaryService;
        this.queryValidator = queryValidator;

        SMALL_JOB_LIMIT = smallJobLimit;
        this.patientProcessor = patientProcessor;


        /*
         * These have to be of type Runnable(nothing more specific) in order to be compatible with ThreadPoolExecutor constructor prototype
         */
        largeTaskExecutionQueue = new PriorityBlockingQueue<Runnable>(1000);
        smallTaskExecutionQueue = new PriorityBlockingQueue<Runnable>(1000);

        largeTaskExecutor = createExecutor(largeTaskExecutionQueue, largeTaskThreads);
        smallTaskExecutor = createExecutor(smallTaskExecutionQueue, smallTaskThreads);
    }

    public AsyncResult runQuery(Query query) throws IOException {
        AsyncResult result = initializeResult(query);

        try {
            queryValidator.validate(query);
            if (query.select().size() > SMALL_JOB_LIMIT) {
                result.setJobQueue(largeTaskExecutor);
            } else {
                result.setJobQueue(smallTaskExecutor);
            }

            result.enqueue();
        } catch (IllegalArgumentException e) {
            result.setStatus(AsyncResult.Status.ERROR);
            return result;
        }
        return getStatusFor(result.getId());
    }

    private AsyncResult initializeResult(Query query) throws IOException {

        HpdsV3Processor p = switch (query.expectedResultType()) {
            case PATIENTS -> patientProcessor;
            case SECRET_ADMIN_DATAFRAME -> queryProcessor;
            case DATAFRAME_TIMESERIES -> timeseriesProcessor;
            case COUNT, CATEGORICAL_CROSS_COUNT, CONTINUOUS_CROSS_COUNT -> countProcessor;
            case DATAFRAME_PFB, DATAFRAME -> multiValueQueryProcessor;
            default -> throw new RuntimeException("UNSUPPORTED RESULT TYPE");
        };

        String queryId = UUIDv5.UUIDFromString(query.toString()).toString();
        ResultWriter writer;
        if (ResultType.DATAFRAME_PFB.equals(query.expectedResultType())) {
            writer = new PfbWriter(File.createTempFile("result-" + System.nanoTime(), ".avro"), queryId, dictionaryService);
        } else {
            writer = new CsvWriter(File.createTempFile("result-" + System.nanoTime(), ".sstmp"));
        }

        query = query.generateId();
        AsyncResult result = new AsyncResult(query, p, writer).setStatus(AsyncResult.Status.PENDING)
            .setQueuedTime(System.currentTimeMillis()).setId(queryId);
        results.put(result.getId(), result);
        return result;
    }


    private List<String> includingOnlyDictionaryFields(Set<String> fields, Set<String> dictionaryFields) {
        return fields.stream().filter(dictionaryFields::contains).collect(Collectors.toList());
    }

    public AsyncResult getStatusFor(String queryId) {
        AsyncResult asyncResult = results.get(queryId);
        int queueDepth =
            asyncResult.getQuery().select().size() > SMALL_JOB_LIMIT ? largeTaskExecutionQueue.size() : smallTaskExecutionQueue.size();
        // note: code copied from this method in QueryService was removed, it was obviously not working
        asyncResult.setQueueDepth(queueDepth);
        return asyncResult;
    }

    public AsyncResult getResultFor(UUID queryId) {
        return results.get(queryId.toString());
    }

    private ExecutorService createExecutor(BlockingQueue<Runnable> taskQueue, int numThreads) {
        return new ThreadPoolExecutor(1, Math.max(2, numThreads), 10, TimeUnit.MINUTES, taskQueue);
    }

}

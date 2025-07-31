package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import com.fasterxml.jackson.annotation.JsonIgnore;
import edu.harvard.dbmi.avillach.util.PicSureStatus;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.ResultStore;
import edu.harvard.hms.dbmi.avillach.hpds.processing.ResultStoreStream;
import edu.harvard.hms.dbmi.avillach.hpds.processing.io.ResultWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Note: This class was copied from {@link edu.harvard.hms.dbmi.avillach.hpds.processing.AsyncResult} and updated to use new Query entity
 */
public class AsyncResult implements Runnable, Comparable<AsyncResult> {

    private static final Logger log = LoggerFactory.getLogger(AsyncResult.class);

    public byte[] readAllBytes() {
        try {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void closeWriter() {
        stream.closeWriter();
    }

    private final MediaType responseType;

    public MediaType getResponseType() {
        return responseType;
    }

    public File getFile() {
        return stream.getFile();
    }

    public enum Status {
        SUCCESS {
            @Override
            public PicSureStatus toPicSureStatus() {
                return PicSureStatus.AVAILABLE;
            }
        },
        ERROR {
            @Override
            public PicSureStatus toPicSureStatus() {
                return PicSureStatus.ERROR;
            }
        },
        PENDING {
            @Override
            public PicSureStatus toPicSureStatus() {
                return PicSureStatus.QUEUED;
            }
        },
        RUNNING {
            @Override
            public PicSureStatus toPicSureStatus() {
                return PicSureStatus.PENDING;
            }
        },
        RETRY {
            @Override
            public PicSureStatus toPicSureStatus() {
                return PicSureStatus.QUEUED;
            }
        };

        public abstract PicSureStatus toPicSureStatus();
    }

    private final Query query;

    public Query getQuery() {
        return query;
    }

    private Status status;

    public Status getStatus() {
        return status;
    }

    public AsyncResult setStatus(Status status) {
        this.status = status;
        return this;
    }

    private long queuedTime;

    public long getQueuedTime() {
        return queuedTime;
    }

    public AsyncResult setQueuedTime(long queuedTime) {
        this.queuedTime = queuedTime;
        return this;
    }

    private long completedTime;

    public long getCompletedTime() {
        return completedTime;
    }

    private int queueDepth;

    public int getQueueDepth() {
        return queueDepth;
    }

    public AsyncResult setQueueDepth(int queueDepth) {
        this.queueDepth = queueDepth;
        return this;
    }

    private int positionInQueue;

    public AsyncResult setPositionInQueue(int positionInQueue) {
        this.positionInQueue = positionInQueue;
        return this;
    }

    private int numRows;

    private int numColumns;

    private String id;

    public String getId() {
        return id;
    }

    public AsyncResult setId(String id) {
        this.id = id;
        return this;
    }

    @JsonIgnore
    private ResultStoreStream stream;

    public ResultStoreStream getStream() {
        return stream;
    }

    @JsonIgnore
    private String[] headerRow;

    /*
     * The result needs access to the jobQueue so it can requeue itself if it fails due to insufficient available heap to build its result
     * array.
     * 
     * The actual exception is thrown in @see ResultStore#constructor
     */
    @JsonIgnore
    private ExecutorService jobQueue;

    public ExecutorService getJobQueue() {
        return jobQueue;
    }

    public AsyncResult setJobQueue(ExecutorService jobQueue) {
        this.jobQueue = jobQueue;
        return this;
    }

    @JsonIgnore
    private HpdsV3Processor processor;

    public HpdsV3Processor getProcessor() {
        return processor;
    }

    public AsyncResult(Query query, HpdsV3Processor processor, ResultWriter writer) {
        this.query = query;
        this.processor = processor;
        this.headerRow = processor.getHeaderRow(query);
        this.responseType = writer.getResponseType();
        try {
            stream = new ResultStoreStream(headerRow, writer);
        } catch (IOException e) {
            log.error("Exception creating result stream", e);
        }
    }

    public void appendResults(List<String[]> dataEntries) {
        stream.appendResults(dataEntries);
    }

    public void appendMultiValueResults(List<List<List<String>>> dataEntries) {
        stream.appendMultiValueResults(dataEntries);
    }

    public void appendResultStore(ResultStore resultStore) {
        stream.appendResultStore(resultStore);
    }


    @Override
    public void run() {
        status = Status.RUNNING;
        long startTime = System.currentTimeMillis();
        try {
            processor.runQuery(query, this);
            this.numColumns = this.headerRow.length;
            this.numRows = stream.getNumRows();
            log.info(
                "Ran Query in " + (System.currentTimeMillis() - startTime) + "ms for " + stream.getNumRows() + " rows and "
                    + this.headerRow.length + " columns"
            );
            this.status = Status.SUCCESS;
        } catch (Exception e) {
            log.error("Query failed in " + (System.currentTimeMillis() - startTime) + "ms", e);
            this.status = Status.ERROR;
        } finally {
            this.completedTime = System.currentTimeMillis();
        }
    }

    public void enqueue() {
        try {
            this.jobQueue.execute(this);
        } catch (RejectedExecutionException e) {
            this.status = Status.ERROR;
        }
    }

    public void open() {
        stream.open();
    }

    @Override
    public int compareTo(AsyncResult o) {
        return this.query.id().compareTo(o.query.id());
    }

    public Path getTempFilePath() {
        return stream.getTempFilePath();
    }

}

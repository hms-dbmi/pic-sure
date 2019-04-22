package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnore;

import edu.harvard.dbmi.avillach.util.PicSureStatus;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.exception.NotEnoughMemoryException;

public class AsyncResult implements Runnable, Comparable<AsyncResult>{
	
	private static Logger log = LoggerFactory.getLogger(AsyncResult.class);
	
	public static enum Status{
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
		}, RETRY {
			@Override
			public PicSureStatus toPicSureStatus() {
				return PicSureStatus.QUEUED;
			}
		};

		public abstract PicSureStatus toPicSureStatus();
	}
	
	public Query query;
	
	public Status status;
	
	public long queuedTime;
	
	public long completedTime;
	
	public int retryCount;
	
	public int queueDepth;
	
	public int positionInQueue;
	
	public int numRows;
	
	public int numColumns;
	
	public String id;
	
	@JsonIgnore
	public ResultStoreStream stream;

	/*
	 * The result needs access to the jobQueue so it can requeue 
	 * itself if it fails due to insufficient available heap to 
	 * build its result array.
	 * 
	 * The actual exception is thrown in @see ResultStore#constructor
	 */
	@JsonIgnore
	public ExecutorService jobQueue;

	@JsonIgnore
	public AbstractProcessor processor;

	public AsyncResult(Query query) {
		this.query = query;
		try {
			stream = new ResultStoreStream(createHeaderRow(), query.expectedResultType==ResultType.DATAFRAME_MERGED);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Override
	public void run() {
		status = AsyncResult.Status.RUNNING;
		long startTime = System.currentTimeMillis();
		try {
			try {
				processor.runQuery(query, this);
			} catch(NotEnoughMemoryException e) {
				if(this.retryCount < 3) {
					log.info("Requeueing " + this.id);
					e.printStackTrace();
					this.status = AsyncResult.Status.RETRY;
					this.retryCount ++;
					this.enqueue();
				}else {
					this.status = AsyncResult.Status.ERROR;
				}
				return;
			}
			this.numColumns = this.createHeaderRow().length;
			this.numRows = stream.getNumRows();
			log.info("Ran Query in " + (System.currentTimeMillis()-startTime) + "ms for " + stream.getNumRows() + " rows and " + this.createHeaderRow().length + " columns");
			this.status = AsyncResult.Status.SUCCESS;
		} catch (Exception e) {
			log.error("Query failed in " + (System.currentTimeMillis()-startTime) + "ms");
			e.printStackTrace();
			this.status = AsyncResult.Status.ERROR;
		} finally {
			this.completedTime = System.currentTimeMillis();
		}
	}

	private String[] createHeaderRow() {
		String[] header;
		switch(query.expectedResultType) {
		case DATAFRAME :
			header = new String[query.fields.size()+1];
			header[0] = "Patient ID";
			System.arraycopy(query.fields.toArray(), 0, header, 1, query.fields.size());
			return header;
		case DATAFRAME_MERGED :
			header = new String[query.fields.size()+1];
			header[0] = "Patient ID";
			System.arraycopy(query.fields.toArray(), 0, header, 1, query.fields.size());
			return header;
		case COUNT :
			return new String[] {"Patient ID", "Count"};
		default : 
			throw new RuntimeException("UNSUPPORTED RESULT TYPE");
		}
	}

	public void enqueue() {
		try {
		this.jobQueue.execute(this);
		} catch (RejectedExecutionException e) {
			this.status = AsyncResult.Status.ERROR;
//			this.stream = new ByteArrayInputStream("Server is too busy to handle your request at this time.".getBytes());
		}
	}

	@Override
	public int compareTo(AsyncResult o) {
		return this.query.id.compareTo(o.query.id);
	}
	
}

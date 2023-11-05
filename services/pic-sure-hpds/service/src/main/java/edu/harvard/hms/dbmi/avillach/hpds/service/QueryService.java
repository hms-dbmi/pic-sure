package edu.harvard.hms.dbmi.avillach.hpds.service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;

import edu.harvard.dbmi.avillach.util.UUIDv5;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.*;
import edu.harvard.hms.dbmi.avillach.hpds.processing.AsyncResult.Status;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class QueryService {

	private static final int RESULTS_CACHE_SIZE = 50;

	private final int SMALL_JOB_LIMIT;
	private final int LARGE_TASK_THREADS;
	private final int SMALL_TASK_THREADS;

	private final Logger log = LoggerFactory.getLogger(this.getClass());
	
	private final BlockingQueue<Runnable> largeTaskExecutionQueue;

	private final ExecutorService largeTaskExecutor;

	private final BlockingQueue<Runnable> smallTaskExecutionQueue;

	private final ExecutorService smallTaskExecutor;

	private final AbstractProcessor abstractProcessor;
	private final QueryProcessor queryProcessor;
	private final TimeseriesProcessor timeseriesProcessor;
	private final CountProcessor countProcessor;

	HashMap<String, AsyncResult> results = new HashMap<>();


	@Autowired
	public QueryService (AbstractProcessor abstractProcessor,
						 QueryProcessor queryProcessor,
						 TimeseriesProcessor timeseriesProcessor,
						 CountProcessor countProcessor,
						 @Value("${SMALL_JOB_LIMIT}") Integer smallJobLimit,
						 @Value("${SMALL_TASK_THREADS}") Integer smallTaskThreads,
						 @Value("${LARGE_TASK_THREADS}") Integer largeTaskThreads) {
		this.abstractProcessor = abstractProcessor;
		this.queryProcessor = queryProcessor;
		this.timeseriesProcessor = timeseriesProcessor;
		this.countProcessor = countProcessor;

		SMALL_JOB_LIMIT = smallJobLimit;
		SMALL_TASK_THREADS = smallTaskThreads;
		LARGE_TASK_THREADS = largeTaskThreads;


		/* These have to be of type Runnable(nothing more specific) in order 
		 * to be compatible with ThreadPoolExecutor constructor prototype 
		 */
		largeTaskExecutionQueue = new PriorityBlockingQueue<Runnable>(1000);
		smallTaskExecutionQueue = new PriorityBlockingQueue<Runnable>(1000);

		largeTaskExecutor = createExecutor(largeTaskExecutionQueue, LARGE_TASK_THREADS);
		smallTaskExecutor = createExecutor(smallTaskExecutionQueue, SMALL_TASK_THREADS);
	}

	public AsyncResult runQuery(Query query) throws ClassNotFoundException, IOException {
		// Merging fields from filters into selected fields for user validation of results
		mergeFilterFieldsIntoSelectedFields(query);

		Collections.sort(query.getFields());

		AsyncResult result = initializeResult(query);
		
		// This is all the validation we do for now.
		if(!ensureAllFieldsExist(query)) {
			result.status = Status.ERROR;
		}else {
			if(query.getFields().size() > SMALL_JOB_LIMIT) {
				result.jobQueue = largeTaskExecutor;
			} else {
				result.jobQueue = smallTaskExecutor;
			}

			result.enqueue();
		}
		return getStatusFor(result.id);
	}

	ExecutorService countExecutor = Executors.newSingleThreadExecutor();

	public int runCount(Query query) throws InterruptedException, ExecutionException, ClassNotFoundException, FileNotFoundException, IOException {
		return countProcessor.runCounts(query);
	}

	private AsyncResult initializeResult(Query query) throws ClassNotFoundException, FileNotFoundException, IOException {
		
		HpdsProcessor p;
		switch(query.getExpectedResultType()) {
		case DATAFRAME :
		case SECRET_ADMIN_DATAFRAME:
		case DATAFRAME_MERGED :
			p = queryProcessor;
			break;
		case DATAFRAME_TIMESERIES :
			p = timeseriesProcessor;
			break;
		case COUNT :
		case CATEGORICAL_CROSS_COUNT :
		case CONTINUOUS_CROSS_COUNT :
			p = countProcessor;
			break;
		default : 
			throw new RuntimeException("UNSUPPORTED RESULT TYPE");
		}
		
		AsyncResult result = new AsyncResult(query, p.getHeaderRow(query));
		result.status = AsyncResult.Status.PENDING;
		result.queuedTime = System.currentTimeMillis();
		result.id = UUIDv5.UUIDFromString(query.toString()).toString();
		result.processor = p;
		query.setId(result.id);
		results.put(result.id, result);
		return result;
	}
	
	
	private void mergeFilterFieldsIntoSelectedFields(Query query) {
		LinkedHashSet<String> fields = new LinkedHashSet<>();
		fields.addAll(query.getFields());
		if(!query.getCategoryFilters().isEmpty()) {
			Set<String> categoryFilters = new TreeSet<String>(query.getCategoryFilters().keySet());
			Set<String> toBeRemoved = new TreeSet<String>();
			for(String categoryFilter : categoryFilters) {
				System.out.println("In : " + categoryFilter);
				if(VariantUtils.pathIsVariantSpec(categoryFilter)) {
					toBeRemoved.add(categoryFilter);
				}
			}
			categoryFilters.removeAll(toBeRemoved);
			for(String categoryFilter : categoryFilters) {
				System.out.println("Out : " + categoryFilter);
			}
			fields.addAll(categoryFilters);
		}
		fields.addAll(query.getAnyRecordOf());
		fields.addAll(query.getRequiredFields());
		fields.addAll(query.getNumericFilters().keySet());
		query.setFields(fields);
	}

	private boolean ensureAllFieldsExist(Query query) {
		TreeSet<String> allFields = new TreeSet<>();
		List<String> badNumericFilters = new ArrayList<String>();
		List<String> badCategoryFilters = new ArrayList<String>();
		Set<String> dictionaryFields = abstractProcessor.getDictionary().keySet();

		allFields.addAll(query.getFields());
		allFields.addAll(query.getRequiredFields());

		allFields.addAll(query.getNumericFilters().keySet());
		for(String field : includingOnlyDictionaryFields(query.getNumericFilters().keySet(), dictionaryFields)) {
			if(abstractProcessor.getDictionary().get(field).isCategorical()) {
				badNumericFilters.add(field);
			}
		}

		Set<String> catFieldNames = query.getCategoryFilters().keySet().stream()
				.filter(Predicate.not(VariantUtils::pathIsVariantSpec))
				.collect(Collectors.toSet());

		allFields.addAll(catFieldNames);
		for(String field : includingOnlyDictionaryFields(catFieldNames, dictionaryFields)) {
			if(!abstractProcessor.getDictionary().get(field).isCategorical()) {
				badCategoryFilters.add(field);
			}
		}

		List<String> missingFields = allFields.stream()
			.filter(Predicate.not(dictionaryFields::contains))
			.collect(Collectors.toList());

		if(badNumericFilters.isEmpty() && badCategoryFilters.isEmpty()) {
			log.info("All fields passed validation");
			return true;
		} else {
			log.info("Query failed due to field validation : " + query.getId());
			log.info("Non-existant fields : " + String.join(",", missingFields));
			log.info("Bad numeric fields : " + String.join(",", badNumericFilters));
			log.info("Bad category fields : " + String.join(",", badCategoryFilters));
			return false;
		}
	}

	private List<String> includingOnlyDictionaryFields(Set<String> fields, Set<String> dictionaryFields) {
		return fields.stream().filter((value)->{return dictionaryFields.contains(value);}).collect(Collectors.toList());
	}

	public AsyncResult getStatusFor(String queryId) {
		AsyncResult asyncResult = results.get(queryId);
		AsyncResult[] queue = asyncResult.query.getFields().size() > SMALL_JOB_LIMIT ?
				largeTaskExecutionQueue.toArray(new AsyncResult[largeTaskExecutionQueue.size()]) :
					smallTaskExecutionQueue.toArray(new AsyncResult[smallTaskExecutionQueue.size()]);
				if(asyncResult.status == Status.PENDING) {
					ArrayList<AsyncResult> queueSnapshot = new ArrayList<AsyncResult>();
					for(int x = 0;x<queueSnapshot.size();x++) {
						if(queueSnapshot.get(x).id.equals(queryId)) {
							asyncResult.positionInQueue = x;
							break;
						}
					}
				}else {
					asyncResult.positionInQueue = -1;
				}
				asyncResult.queueDepth = queue.length;
				return asyncResult;
	}

	public AsyncResult getResultFor(String queryId) {
		return results.get(queryId);
	}

	private int getIntProp(String key) {
		return Integer.parseInt(System.getProperty(key));
	}

	private ExecutorService createExecutor(BlockingQueue<Runnable> taskQueue, int numThreads) {
		return new ThreadPoolExecutor(1, Math.max(2, numThreads), 10, TimeUnit.MINUTES, taskQueue);
	}

}

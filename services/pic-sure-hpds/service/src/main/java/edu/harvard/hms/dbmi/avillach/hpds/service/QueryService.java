package edu.harvard.hms.dbmi.avillach.hpds.service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;

import edu.harvard.dbmi.avillach.util.UUIDv5;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.*;
import edu.harvard.hms.dbmi.avillach.hpds.processing.AsyncResult.Status;

public class QueryService {

	private final int SMALL_JOB_LIMIT;
	private final int LARGE_TASK_THREADS;
	private final int SMALL_TASK_THREADS;

	Logger log = LoggerFactory.getLogger(this.getClass());
	
	private BlockingQueue<Runnable> largeTaskExecutionQueue;

	ExecutorService largeTaskExecutor;

	private BlockingQueue<Runnable> smallTaskExecutionQueue;

	ExecutorService smallTaskExecutor;

	HashMap<String, AsyncResult> results = new HashMap<>();

	public QueryService () throws ClassNotFoundException, FileNotFoundException, IOException{
		SMALL_JOB_LIMIT = getIntProp("SMALL_JOB_LIMIT");
		SMALL_TASK_THREADS = getIntProp("SMALL_TASK_THREADS");
		LARGE_TASK_THREADS = getIntProp("LARGE_TASK_THREADS");


		/* These have to be of type Runnable(nothing more specific) in order 
		 * to be compatible with ThreadPoolExecutor constructor prototype 
		 */
		largeTaskExecutionQueue = new PriorityBlockingQueue<Runnable>(1000);
		smallTaskExecutionQueue = new PriorityBlockingQueue<Runnable>(1000);

		largeTaskExecutor = createExecutor(largeTaskExecutionQueue, LARGE_TASK_THREADS);
		smallTaskExecutor = createExecutor(smallTaskExecutionQueue, SMALL_TASK_THREADS);
	}

	public AsyncResult runQuery(Query query) throws ClassNotFoundException, FileNotFoundException, IOException {
		// Merging fields from filters into selected fields for user validation of results
		mergeFilterFieldsIntoSelectedFields(query);

		Collections.sort(query.fields);

		AsyncResult result = initializeResult(query);

		// This is all the validation we do for now.
		Map<String, List<String>> validationResults = ensureAllFieldsExist(query);
		if(validationResults != null) {
			result.status = Status.ERROR;
		}else {
			if(query.fields.size() > SMALL_JOB_LIMIT) {
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
		return new CountProcessor().runCounts(query);
	}

	private AsyncResult initializeResult(Query query) throws ClassNotFoundException, FileNotFoundException, IOException {
		
		AbstractProcessor p;
		switch(query.expectedResultType) {
		case DATAFRAME :
		case DATAFRAME_MERGED :
			p = new QueryProcessor();
			break;
		case DATAFRAME_TIMESERIES :
			p = new TimeseriesProcessor();
			break;
		case COUNT :
			p = new CountProcessor();
			break;
		default : 
			throw new RuntimeException("UNSUPPORTED RESULT TYPE");
		}
		
		AsyncResult result = new AsyncResult(query, p.getHeaderRow(query));
		result.status = AsyncResult.Status.PENDING;
		result.queuedTime = System.currentTimeMillis();
		result.id = UUIDv5.UUIDFromString(query.toString()).toString();
		result.processor = p;
		query.id = result.id;
		results.put(result.id, result);
		return result;
	}
	
	
	private void mergeFilterFieldsIntoSelectedFields(Query query) {
		LinkedHashSet<String> fields = new LinkedHashSet<>();
		if(query.fields != null)fields.addAll(query.fields);
		if(query.categoryFilters != null) {
			Set<String> categoryFilters = new TreeSet<String>(query.categoryFilters.keySet());
			Set<String> toBeRemoved = new TreeSet<String>();
			for(String categoryFilter : categoryFilters) {
				System.out.println("In : " + categoryFilter);
				if(AbstractProcessor.pathIsVariantSpec(categoryFilter)) {
					toBeRemoved.add(categoryFilter);
				}
			}
			categoryFilters.removeAll(toBeRemoved);
			for(String categoryFilter : categoryFilters) {
				System.out.println("Out : " + categoryFilter);
			}
			fields.addAll(categoryFilters);
		}
		if(query.anyRecordOf != null)fields.addAll(query.anyRecordOf);
		if(query.requiredFields != null)fields.addAll(query.requiredFields);
		if(query.numericFilters != null)fields.addAll(query.numericFilters.keySet());
		query.fields = new ArrayList<String>(fields);
	}

	private Map<String, List<String>> ensureAllFieldsExist(Query query) {
		TreeSet<String> allFields = new TreeSet<>();
		List<String> missingFields = new ArrayList<String>();
		List<String> badNumericFilters = new ArrayList<String>();
		List<String> badCategoryFilters = new ArrayList<String>();
		Set<String> dictionaryFields = AbstractProcessor.getDictionary().keySet();

		allFields.addAll(query.fields);

		if(query.requiredFields != null) {
			allFields.addAll(query.requiredFields);
		}
		if(query.numericFilters != null) {
			allFields.addAll(query.numericFilters.keySet());
			for(String field : includingOnlyDictionaryFields(query.numericFilters.keySet(), dictionaryFields)) {
				if(AbstractProcessor.getDictionary().get(field).isCategorical()) {
					badNumericFilters.add(field);
				}
			}
		}

		if(query.categoryFilters != null) {
			Set<String> catFieldNames = new TreeSet<String>(query.categoryFilters.keySet());
			catFieldNames.removeIf((field)->{return AbstractProcessor.pathIsVariantSpec(field);});
			allFields.addAll(catFieldNames);
			for(String field : includingOnlyDictionaryFields(catFieldNames, dictionaryFields)) {
				if( ! AbstractProcessor.getDictionary().get(field).isCategorical()) {
					badCategoryFilters.add(field);
				}
			}
		}

		for(String field : allFields) {
			if(!dictionaryFields.contains(field)) {
				missingFields.add(field);
			}
		}

		if(missingFields.isEmpty() && badNumericFilters.isEmpty() && badCategoryFilters.isEmpty()) {
			System.out.println("All fields passed validation");
			return null;
		} else {
			log.info("Query failed due to field validation : " + query.id);
			log.info("Non-existant fields : " + String.join(",", missingFields));
			log.info("Bad numeric fields : " + String.join(",", badNumericFilters));
			log.info("Bad category fields : " + String.join(",", badCategoryFilters));
			return ImmutableMap.of(
					"nonExistantFileds", missingFields, 
					"numeric_filters_on_categorical_variables", badNumericFilters, 
					"category_filters_on_numeric_variables", badCategoryFilters)
					;
		}
	}

	private List<String> includingOnlyDictionaryFields(Set<String> fields, Set<String> dictionaryFields) {
		return fields.stream().filter((value)->{return dictionaryFields.contains(value);}).collect(Collectors.toList());
	}

	public AsyncResult getStatusFor(String queryId) {
		AsyncResult asyncResult = results.get(queryId);
		AsyncResult[] queue = asyncResult.query.fields.size() > SMALL_JOB_LIMIT ? 
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

	public TreeMap<String, ColumnMeta> getDataDictionary() {
		return AbstractProcessor.getDictionary();
	}

	private int getIntProp(String key) {
		return Integer.parseInt(System.getProperty(key));
	}

	private ExecutorService createExecutor(BlockingQueue<Runnable> taskQueue, int numThreads) {
		return new ThreadPoolExecutor(1, Math.max(2, numThreads), 10, TimeUnit.MINUTES, taskQueue);
	}

}

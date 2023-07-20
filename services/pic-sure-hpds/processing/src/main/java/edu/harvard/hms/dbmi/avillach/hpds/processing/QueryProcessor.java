package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Collectors;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.exception.NotEnoughMemoryException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * This class handles DATAFRAME export queries for HPDS.
 * @author nchu
 *
 */
@Component
public class QueryProcessor implements HpdsProcessor {
 
	private static final byte[] EMPTY_STRING_BYTES = "".getBytes();
	private Logger log = LoggerFactory.getLogger(QueryProcessor.class);

	private final String ID_CUBE_NAME;
	private final int ID_BATCH_SIZE;

	private final AbstractProcessor abstractProcessor;

	@Autowired
	public QueryProcessor(AbstractProcessor abstractProcessor) {
		this.abstractProcessor = abstractProcessor;
		ID_BATCH_SIZE = Integer.parseInt(System.getProperty("ID_BATCH_SIZE", "0"));
		ID_CUBE_NAME = System.getProperty("ID_CUBE_NAME", "NONE");
	}
	
	@Override
	public String[] getHeaderRow(Query query) {
		String[] header = new String[query.getFields().size()+1];
		header[0] = "Patient ID";
		System.arraycopy(query.getFields().toArray(), 0, header, 1, query.getFields().size());
		return header;
	}

	public void runQuery(Query query, AsyncResult result) {
		TreeSet<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
		log.info("Processing " + idList.size() + " rows for result " + result.id);
		Lists.partition(new ArrayList<>(idList), ID_BATCH_SIZE).parallelStream()
			.map(list -> buildResult(result, query, new TreeSet<>(list)))
			.sequential()
			.forEach(result.stream::appendResultStore);
	}

	
	private ResultStore buildResult(AsyncResult result, Query query, TreeSet<Integer> ids) {
		List<ColumnMeta> columns = query.getFields().stream()
			.map(abstractProcessor.getDictionary()::get)
			.filter(Objects::nonNull)
			.collect(Collectors.toList());
		List<String> paths = columns.stream()
			.map(ColumnMeta::getName)
			.collect(Collectors.toList());
		int columnCount = paths.size() + 1;

		ArrayList<Integer> columnIndex = abstractProcessor.useResidentCubesFirst(paths, columnCount);
		ResultStore results = new ResultStore(result.id, columns, ids);

		columnIndex.parallelStream().forEach((column)->{
			clearColumn(paths, ids, results, column);
			processColumn(paths, ids, results, column);
		});

		return results;
	}

	private void clearColumn(List<String> paths, TreeSet<Integer> ids, ResultStore results, Integer x) {
		String path = paths.get(x-1);
		if(VariantUtils.pathIsVariantSpec(path)) {
			ByteBuffer doubleBuffer = ByteBuffer.allocate(Double.BYTES);
			int idInSubsetPointer = 0;
			for(int id : ids) {
				writeVariantNullResultField(results, x, doubleBuffer, idInSubsetPointer);
				idInSubsetPointer++;
			}
		} else {
			PhenoCube<?> cube = abstractProcessor.getCube(path);
			ByteBuffer doubleBuffer = ByteBuffer.allocate(Double.BYTES);
			int idInSubsetPointer = 0;
			for(int id : ids) {
				writeNullResultField(results, x, cube, doubleBuffer, idInSubsetPointer);
				idInSubsetPointer++;
			}
		}
	}

	private void processColumn(List<String> paths, TreeSet<Integer> ids, ResultStore results,
			Integer x) {
		String path = paths.get(x-1);
		if(VariantUtils.pathIsVariantSpec(path)) {
			VariantMasks masks = abstractProcessor.getMasks(path, new VariantBucketHolder<VariantMasks>());
			String[] patientIds = abstractProcessor.getPatientIds();
			int idPointer = 0;

			ByteBuffer doubleBuffer = ByteBuffer.allocate(Double.BYTES);
			int idInSubsetPointer = 0;
			for(int id : ids) {
				while(idPointer < patientIds.length) {
					int key = Integer.parseInt(patientIds[idPointer]);
					if(key < id) {
						idPointer++;
					} else if(key == id){
						idPointer = writeVariantResultField(results, x, masks, idPointer, doubleBuffer,
								idInSubsetPointer);
						break;
					} else {
						writeVariantNullResultField(results, x, doubleBuffer, idInSubsetPointer);
						break;
					}
				}
				idInSubsetPointer++;
			}
		}else {
			PhenoCube<?> cube = abstractProcessor.getCube(path);

			KeyAndValue<?>[] cubeValues = cube.sortedByKey();

			int idPointer = 0;

			ByteBuffer doubleBuffer = ByteBuffer.allocate(Double.BYTES);
			int idInSubsetPointer = 0;
			for(int id : ids) {
				while(idPointer < cubeValues.length) {
					int key = cubeValues[idPointer].getKey();
					if(key < id) {
						idPointer++;
					} else if(key == id){
						idPointer = writeResultField(results, x, cube, cubeValues, idPointer, doubleBuffer,
								idInSubsetPointer);
						break;
					} else {
						writeNullResultField(results, x, cube, doubleBuffer, idInSubsetPointer);
						break;
					}
				}
				idInSubsetPointer++;
			}
		}
	}

	private void writeVariantNullResultField(ResultStore results, Integer x, ByteBuffer doubleBuffer,
			int idInSubsetPointer) {
		byte[] valueBuffer = null;
		valueBuffer = EMPTY_STRING_BYTES;
		results.writeField(x,idInSubsetPointer, valueBuffer);
	}

	private int writeVariantResultField(ResultStore results, Integer x, VariantMasks masks, int idPointer,
			ByteBuffer doubleBuffer, int idInSubsetPointer) {
		byte[] valueBuffer;
		if(masks.heterozygousMask != null && masks.heterozygousMask.testBit(idPointer)) {
			valueBuffer = "0/1".getBytes();
		}else if(masks.homozygousMask != null && masks.homozygousMask.testBit(idPointer)) {
			valueBuffer = "1/1".getBytes();
		}else {
			valueBuffer = "0/0".getBytes();
		}
		valueBuffer = masks.toString().getBytes();
		results.writeField(x,idInSubsetPointer, valueBuffer);
		return idPointer;
	}

	private int writeResultField(ResultStore results, Integer x, PhenoCube<?> cube, KeyAndValue<?>[] cubeValues,
			int idPointer, ByteBuffer doubleBuffer, int idInSubsetPointer) {
		byte[] valueBuffer;
		Comparable<?> value = cubeValues[idPointer++].getValue();
		if(cube.isStringType()) {
			valueBuffer = value.toString().getBytes();
		}else {
			valueBuffer = doubleBuffer.putDouble((Double)value).array();
			doubleBuffer.clear();
		}
		results.writeField(x,idInSubsetPointer, valueBuffer);
		return idPointer;
	}

	/**
	 * Correctly handle null records. A numerical value should be a NaN if it is missing to distinguish from a zero. A
	 * String based value(categorical) should be empty instead of null because the word null might be a valid value.
	 * 
	 * @param results
	 * @param x
	 * @param cube
	 * @param doubleBuffer
	 * @param idInSubsetPointer
	 */
	private void writeNullResultField(ResultStore results, Integer x, PhenoCube<?> cube, ByteBuffer doubleBuffer, int idInSubsetPointer) {
		byte[] valueBuffer = null;
		if(cube.isStringType()) {
			valueBuffer = EMPTY_STRING_BYTES;
		}else {
			Double nullDouble = Double.NaN;
			valueBuffer = doubleBuffer.putDouble(nullDouble).array();
			doubleBuffer.clear();
		}
		results.writeField(x,idInSubsetPointer, valueBuffer);
	}
}

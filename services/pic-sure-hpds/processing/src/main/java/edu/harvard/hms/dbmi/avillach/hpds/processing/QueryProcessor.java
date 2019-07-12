package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;

import com.google.common.collect.Lists;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.exception.NotEnoughMemoryException;
import edu.harvard.hms.dbmi.avillach.hpds.exception.TooManyVariantsException;


public class QueryProcessor extends AbstractProcessor {
 
	private static final byte[] EMPTY_STRING_BYTES = "".getBytes();
	private Logger log = Logger.getLogger(QueryProcessor.class);

	public QueryProcessor() throws ClassNotFoundException, FileNotFoundException, IOException {
		super();
	}

	public void runQuery(Query query, AsyncResult result) throws NotEnoughMemoryException, TooManyVariantsException {
		TreeSet<Integer> idList = getPatientSubsetForQuery(query);
		log.info("Processing " + idList.size() + " rows for result " + result.id);
		for(List<Integer> list : Lists.partition(new ArrayList<>(idList), ID_BATCH_SIZE)){
			result.stream.appendResultStore(buildResult(result, query, new TreeSet<Integer>(list)));			
		};
	}

	
	private ResultStore buildResult(AsyncResult result, Query query, TreeSet<Integer> ids) throws NotEnoughMemoryException {
		List<String> paths = query.fields;
		int columnCount = paths.size() + 1;

		ArrayList<Integer> columnIndex = useResidentCubesFirst(paths, columnCount);
		ResultStore results = new ResultStore(result, query.id, paths.stream().map((path)->{
			return metaStore.get(path);
		}).collect(Collectors.toList()), ids);

		columnIndex.parallelStream().forEach((column)->{
			processColumn(paths, ids, results, column);
		});

		return results;
	}

	private void processColumn(List<String> paths, TreeSet<Integer> ids, ResultStore results,
			Integer x) {
		try{
			String path = paths.get(x-1);
			if(pathIsVariantSpec(path)) {
				VariantMasks masks = variantStore.getMasks(path);
				String[] patientIds = variantStore.getPatientIds();
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
				PhenoCube<?> cube = getCube(path);

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

		}catch(Exception e) {
			e.printStackTrace();
			return;
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

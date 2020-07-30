package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

import de.siegmar.fastcsv.writer.CsvWriter;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;

public class ResultStoreStream extends InputStream {

	private CsvWriter writer;
	private File tempFile;
	private InputStream in;
	private int value;
	private boolean streamIsClosed = false;
	private int numRows;
	private String[] expandedHeader;
	private TreeMap<String, ArrayList<Integer>> mergedColumnIndex;
	private String[] originalHeader;
	private boolean mergeColumns;

	public ResultStoreStream(String[] header, boolean mergeColumns) throws IOException {
		writer = new CsvWriter();
		tempFile = File.createTempFile("result-"+ System.nanoTime(), ".sstmp");
		this.originalHeader = header;
		if(mergeColumns) {
			this.expandedHeader = createMergedColumns(header);			
			writeHeader(this.expandedHeader);
		}else {
			writeHeader(this.originalHeader);
		}
		this.mergeColumns = mergeColumns;
	}

	private String[] createMergedColumns(String[] header) {
		ArrayList<String> allColumns = new ArrayList<String>();
		allColumns.add(header[0]);
		TreeMap<String, TreeSet<String>> mergedColumns = new TreeMap<>();
		this.mergedColumnIndex = new TreeMap<>();
		int columnNumber = 0;
		for(String column : header) {
			String[] split = column.split("\\\\");
			if(split.length > 1) {
				String key = split[1];
				TreeSet<String> subColumns = mergedColumns.get(key);
				ArrayList<Integer> columnIndex = mergedColumnIndex.get(key);
				if(subColumns == null) {
					subColumns = new TreeSet<String>();
					mergedColumns.put(key, subColumns);
					allColumns.add(key);
					columnIndex = new ArrayList<Integer>();
					mergedColumnIndex.put(key,  columnIndex);
				}
				columnIndex.add(columnNumber);
				subColumns.add(column);
			}
			columnNumber++;
		}
		for(int x = 1;x<header.length;x++) {
			allColumns.add(header[x]);
		}
		return allColumns.toArray(new String[allColumns.size()]);
	}

	private void writeHeader(String[] header) throws IOException {
		ArrayList<String[]> headerEntries = new ArrayList<String[]>();
		headerEntries.add(header);
		try(FileWriter out = new FileWriter(tempFile);){
			writer.write(out, headerEntries);			
		}
	}

	public void appendResultStore(ResultStore results) {
		try (FileWriter out = new FileWriter(tempFile, true);){
			int batchSize = 100;
			List<String[]> entries = new ArrayList<String[]>(batchSize);
			for(int x = 0;x<batchSize;x++) {
				entries.add(new String[results.getColumns().size()]);
			}
			entries = writeResultsToTempFile(results, out, batchSize, entries);
		} catch (IOException e) {
			throw new RuntimeException("IOException while appending temp file : " + tempFile.getAbsolutePath(), e);
		} 
	}

	private List<String[]> writeResultsToTempFile(ResultStore results, FileWriter out, int batchSize,
			List<String[]> entries) throws IOException {
		
		List<ColumnMeta> columns = results.getColumns();
		int[] columnWidths = new int[columns.size()];
		for(int x = 0;x<columns.size();x++) {
			columnWidths[x] = columns.get(x).getWidthInBytes();
		}

		for(int x = 0;x<(results.getNumRows());x+=batchSize) {
			int rowsInBatch = Math.min(batchSize, results.getNumRows() - x);
			for(int y = 0;y<rowsInBatch;y++) {
				results.readRowIntoStringArray(y+x, columnWidths, entries.get(y));
			}
			if(rowsInBatch < batchSize) {
				entries = entries.subList(0, rowsInBatch);
			}
			writer.write(out, entries);
			numRows += rowsInBatch;
		}
		return entries;
	}

	public void close() {
		try {
			in.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void open() {
		try {
			in = new BufferedInputStream(new FileInputStream(new File(tempFile.getAbsolutePath())), 1024 * 1024 * 8);
			if(mergeColumns) {
				File mergedFile = File.createTempFile(tempFile.getName(), "_merged");
				FileWriter out = new FileWriter(mergedFile);
				CSVParser parser = CSVFormat.DEFAULT.withDelimiter(',').parse(new InputStreamReader(in));
				CSVPrinter writer = new CSVPrinter(out, CSVFormat.DEFAULT.withDelimiter(','));
				final boolean[] firstRow = new boolean[] {true};
				parser.forEach((CSVRecord record)->{
					if(firstRow[0]) {
						try {
							ArrayList<String> header = new ArrayList<>();
							header.add("Patient ID");
							header.addAll(mergedColumnIndex.keySet());
							writer.printRecord(header);
							firstRow[0] = false;
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}else {
						ArrayList<String> records = new ArrayList<String>();
						records.add(record.get(0));
						for(String column : mergedColumnIndex.keySet()) {
							ArrayList<String> valuesToMerge = new ArrayList<>();
							for(Integer columnNumber : mergedColumnIndex.get(column)) {
								String value = record.get(columnNumber);
								if( value != null && ! value.isEmpty() ) {
									value = value.replaceAll("\"", "'");
									String label = originalHeader[columnNumber].replaceAll("\\\\"+ column, "");
									if(label.length()>1) {
										label = label.substring(1, label.length()-1);
									}else {
										label = null;
									}
									if(label==null || label.trim().contentEquals(value.trim())) {
										valuesToMerge.add(value);
									} else {
										valuesToMerge.add(label==null ? value : label.replaceAll("\\\\"+Pattern.quote(value), "") + " : " + value);
									}
								}
							}
							records.add(String.join(";", valuesToMerge));
						}
						//						for(int x = 1;x<record.size();x++) {
						//							records.add(record.get(x));
						//						}
						try {
							writer.printRecord(records);
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}					
					}
				});
				parser.close();
				writer.close();
				out.close();
				in.close();
				in = new BufferedInputStream(new FileInputStream(mergedFile), 1024 * 1024 * 8);
			}
			streamIsClosed = false;
		} catch (FileNotFoundException e) {
			throw new RuntimeException("temp file for result not found : " + tempFile.getAbsolutePath());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}



	@Override
	public int read() throws IOException {
		if(streamIsClosed) {
			return -1;
		}
		value = in.read();
		if(value == -1) {
			in.close();
			streamIsClosed = true;
		}
		return value;
	}

	int getNumRows() {
		return numRows;
	}

	public long estimatedSize() {
		return tempFile.length();
	}

}

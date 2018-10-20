package edu.harvard.hms.dbmi.avillach.pheno.data;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import de.siegmar.fastcsv.writer.CsvWriter;
import edu.harvard.hms.dbmi.avillach.pheno.store.ResultStore;

public class ResultStoreStream extends InputStream {

	private CsvWriter writer;
	private File tempFile;
	private InputStream in;
	private int value;
	private boolean streamIsClosed = false;
	private int numRows;

	public ResultStoreStream(String[] header) throws IOException {
		writer = new CsvWriter();
		tempFile = File.createTempFile("result-"+ System.nanoTime(), ".sstmp");
		writeHeader(header);
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
		for(int x = 0;x<(results.getNumRows());x+=batchSize) {
			int rowsInBatch = Math.min(batchSize, results.getNumRows() - x);
			for(int y = 0;y<rowsInBatch;y++) {
				results.readRowIntoStringArray(y+x, entries.get(y));
			}
			if(rowsInBatch < batchSize) {
				entries = entries.subList(0, rowsInBatch);
			}
			writer.write(out, entries);
			numRows += rowsInBatch;
		}
		return entries;
	}

	public void open() {
		try {
			in = new BufferedInputStream(new FileInputStream(new File(tempFile.getAbsolutePath())), 1024 * 1024 * 8);
			streamIsClosed = false;
		} catch (FileNotFoundException e) {
			throw new RuntimeException("temp file for result not found : " + tempFile.getAbsolutePath());
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

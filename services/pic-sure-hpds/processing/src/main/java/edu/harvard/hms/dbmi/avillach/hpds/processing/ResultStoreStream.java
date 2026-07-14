package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import edu.harvard.hms.dbmi.avillach.hpds.processing.io.CsvWriter;
import edu.harvard.hms.dbmi.avillach.hpds.processing.io.ResultWriter;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;

public class ResultStoreStream extends InputStream {

	private ResultWriter writer;
	private InputStream in;
	private int value;
	private boolean streamIsClosed = false;
	private int numRows;
	private String[] originalHeader;

	public ResultStoreStream(String[] header, ResultWriter writer) throws IOException {
		this.writer = writer;
		this.originalHeader = header;
		writeHeader(this.originalHeader);
		numRows = 0;
	}

	private void writeHeader(String[] header) {
		writer.writeHeader(header);
	}

	public void appendResultStore(ResultStore results) {
		int batchSize = 100;
		List<String[]> entries = new ArrayList<>(batchSize);
		for(int x = 0;x<batchSize;x++) {
			entries.add(new String[results.getColumns().size()]);
		}
		writeResultsToTempFile(results, batchSize, entries);
	}

	/**
	 * A more compact method to append data to the temp file without making assumptions about the composition.
	 * @param entries
	 */
	public void appendResults(List<String[]> entries) {
		writer.writeEntity(entries);
	}
	/**
	 * Appending data to the writer that supports multiple values per patient/variable combination
	 */
	public void appendMultiValueResults(List<List<List<String>>> entries) {
		writer.writeMultiValueEntity(entries);
	}

	private List<String[]> writeResultsToTempFile(ResultStore results, int batchSize,
			List<String[]> entries) {
		
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
			writer.writeEntity(entries);
			numRows += rowsInBatch;
		}
		return entries;
	}

	public void close() {
		try {
			in.close();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public void open() {
		try {
			in = new BufferedInputStream(new FileInputStream(writer.getFile().getAbsolutePath()), 1024 * 1024 * 8);
			streamIsClosed = false;
		} catch (FileNotFoundException e) {
			throw new RuntimeException("temp file for result not found : " + writer.getFile().getAbsolutePath());
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

	public int getNumRows() {
		return numRows;
	}

	public long estimatedSize() {
		return writer.getFile().length();
	}

	public void closeWriter() {
		writer.close();
	}

	public File getFile() {
		return writer.getFile();
	}
	public Path getTempFilePath() {
		return Path.of(getFile().getAbsolutePath());
	}

}

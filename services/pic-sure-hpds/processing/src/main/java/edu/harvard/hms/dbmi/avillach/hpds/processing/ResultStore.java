package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import org.apache.log4j.Logger;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.exception.NotEnoughMemoryException;
import edu.harvard.hms.dbmi.avillach.hpds.exception.ResultSetTooLargeException;

/**
 * This class handles composing a segment of a data export file in memory. The goal here
 * is to prevent memory errors during data export and maximize concurrency for processing
 * concepts.
 * 
 * Each ResultStore instance allocates a byte[] called resultArray. The size of this array is:
 * 
 *  (maximum width of a row in the results) * (number of patients in the result segment)
 *  
 * The maximum width of a row in the result is the sum of the maximum widths in bytes of all
 * columns. This width is stored when the data is loaded initially in the column meta for the
 * concept.
 * 
 * If the size of the array exceeds the maximum size for an array(Integer.MAX_VALUE) then a
 * {@link ResultSetTooLargeException} is thrown with a message that suggests the user select fewer fields.
 * 
 * If the size of the resultArray exceeds the available heap in the JVM a NotEnoughMemoryException
 * is thrown. The only recourse here is to increase the amount of available heap or make the BATCH_SIZE 
 * system property smaller. One way to increase the amount of available heap is to reduce the CACHE_SIZE
 * system property, but that has to be balanced against the performance of non-export queries. Finding 
 * the ideal settings for these properties is a trial and error process for each dataset and user-base.
 * 
 * The resultArray approach allows us to build the entire CSV segment concurrently for multiple concepts
 * without any synchronization overhead. Each concept is written into a dedicated fixed-width region of 
 * the resultArray. These regions do not overlap and a single thread is used for each concept, so there
 * are never two threads writing to the same location at the same time.
 * 
 * The data is then read out of this fixed width structure into a String[] at the time it is written to
 * a temp file for the result by the {@link ResultStoreStream} class.
 *
 */
public class ResultStore {

	private static Logger log = Logger.getLogger(ResultStore.class);

	private List<ColumnMeta> columns;
	int rowWidth;
	private int numRows;
	
	private static final ColumnMeta PATIENT_ID_COLUMN_META = new ColumnMeta().setColumnOffset(0).setName("PatientId").setWidthInBytes(Integer.BYTES);
	byte[] resultArray;
	
	/**
	 * 
	 * @param resultId The result id here is only used for logging
	 * @param columns The ColumnMeta entries involed in the result, these are used to calculate rowWidth
	 * @param ids The subject ids for in the current batch of the result
	 * @throws NotEnoughMemoryException If the size of available heap cannot support a byte array of size (rowWidth x numRows)
	 */
	public ResultStore(String resultId, List<ColumnMeta> columns, TreeSet<Integer> ids) throws NotEnoughMemoryException {
		this.columns = new ArrayList<ColumnMeta>();
		this.numRows = ids.size();
		this.getColumns().add(PATIENT_ID_COLUMN_META);
		int rowWidth = Integer.BYTES;
		for(ColumnMeta column : columns) {
			column.setColumnOffset(rowWidth);
			rowWidth += column.getWidthInBytes();
			this.getColumns().add(column);
		}
		this.rowWidth = rowWidth;
		if(1L * this.rowWidth * this.getNumRows() > Integer.MAX_VALUE) {
			throw new ResultSetTooLargeException((1L * this.rowWidth * this.getNumRows())/(Integer.MAX_VALUE/2));
		}
		try {
			log.info("Allocating result array : " + resultId);
			resultArray = new byte[this.rowWidth * this.getNumRows()];
		} catch(Error e) {
			throw new NotEnoughMemoryException();			
		}
		log.info("Store created for " + this.getNumRows() + " rows and " + columns.size() + " columns ");
		int x = 0;
		for(Integer id : ids) {
			writeField(0,x++,ByteBuffer.allocate(Integer.BYTES).putInt(id).array());
		}
	}

	/**
	 * Copies fieldValue into the resultArray of this instance using System.arraycopy
	 * @param column
	 * @param row
	 * @param fieldValue
	 */
	public void writeField(int column, int row, byte[] fieldValue) {
		int offset = getFieldOffset(row,column);
		try {
			System.arraycopy(fieldValue, 0, resultArray, offset, fieldValue.length);
		}catch(Exception e) {
			log.info("Exception caught writing field : " + column + ", " + row + " : " + fieldValue.length + " bytes into result at offset " + offset + " of " + resultArray.length);
			throw e;
		}
	}

	/**
	 * Finds the resultArray offset of the passed in row and column
	 * @param row
	 * @param column
	 * @return
	 */
	private int getFieldOffset(int row, int column) {
		int rowOffset = row*rowWidth;
		int columnOffset = getColumns().get(column).getColumnOffset();
		return rowOffset + columnOffset;
	}

	ByteBuffer wrappedResultArray;
	/**
	 * Populate 
	 * 
	 * @param rowNumber
	 * @param row
	 * @throws IOException
	 */
	public void readRowIntoStringArray(int rowNumber, int[] columnWidths, String[] row) throws IOException {
		if(wrappedResultArray == null) {
			wrappedResultArray = ByteBuffer.wrap(resultArray);
		}
		row[0] = wrappedResultArray.getInt(getFieldOffset(rowNumber, 0)) + "";
		
		stringifyRow(rowNumber, columnWidths, row);
	}

	/**
	 * Copy each field of a single row from the resultArray into a String[] to be written out to the CSV
	 * 
	 * @param rowNumber row number to copy
	 * @param row String[] to populate with field values
	 * @param columnBuffers a set of buffers corresponding to each column where each buffer is {@link ColumnMeta.widthInBytes} bytes
	 */
	private void stringifyRow(int rowNumber, int[] columnWidths, String[] row) {
		for(int x = 1;x<row.length;x++) {
			ColumnMeta columnMeta = getColumns().get(x);
			int fieldOffset = getFieldOffset(rowNumber, x);
			if(columnMeta.isCategorical()) {
				stringifyString(row, columnWidths, x, fieldOffset);
			} else {
				stringifyDouble(row, x, fieldOffset);
			}
		}
	}

	DecimalFormat decimalFormat = new DecimalFormat("########.####");
	private void stringifyDouble(String[] row, int x, int fieldOffset) {
		row[x] = Double.valueOf(wrappedResultArray.getDouble(fieldOffset)).toString();
	}

	private void stringifyString(String[] row, int[] columnWidths, int x, int fieldOffset) {
		row[x] = new String(Arrays.copyOfRange(resultArray, fieldOffset, fieldOffset + columnWidths[x])).trim();
	}

	public int getNumRows() {
		return numRows;
	}

	public List<ColumnMeta> getColumns() {
		return columns;
	}
}

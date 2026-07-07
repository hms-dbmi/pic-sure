package edu.harvard.hms.dbmi.avillach.hpds.exception;

public class ResultSetTooLargeException extends RuntimeException {
	/**
	 * This exception is thrown when a result set would be larger in memory than
	 * Integer.MAX_INT bytes. This is a JVM limitation... but seriously people...
	 * 
	 * You actually don't need that much data on the client side, work with us
	 * to implement whatever filtering you need.
	 * 
	 * @param multiplesOfCapacity The number of pieces to split this result to actually have it work.
	 */
	public ResultSetTooLargeException(long multiplesOfCapacity) {
		super("The result set is too large to handle in one request. "
				+ "We have a plan to fix this limitation, but for now try "
				+ "splitting your selected fields array in " + multiplesOfCapacity + " equal pieces "
				+ "and running " + multiplesOfCapacity + " queries. Additionally, do you actually "
				+ "need all this data? Do you have the compute resources "
				+ "to handle processing it?");
	}
}

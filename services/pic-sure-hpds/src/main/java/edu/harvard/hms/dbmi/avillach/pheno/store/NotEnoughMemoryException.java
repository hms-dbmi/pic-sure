package edu.harvard.hms.dbmi.avillach.pheno.store;

public class NotEnoughMemoryException extends Exception {

	private static final long serialVersionUID = 2592915631853567560L;

	public NotEnoughMemoryException() {
		super("Not enough available heap space to allocate results array."
				+ "Please reduce ID_BATCH_SIZE or CACHE_SIZE system property or increase the heap available to this application. "
				+ "This query will be retried up to 3 times.");
	}
}

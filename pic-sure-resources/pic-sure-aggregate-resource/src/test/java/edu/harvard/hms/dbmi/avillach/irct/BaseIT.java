package edu.harvard.hms.dbmi.avillach.irct;

import org.junit.BeforeClass;

public class BaseIT {
	protected static String endpointUrl;
	
	@BeforeClass
	public static void beforeClass() {
		endpointUrl = System.getProperty("service.url");
	}

}

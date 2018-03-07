package edu.harvard.hms.dbmi.avillach.irct;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.junit.BeforeClass;
import org.junit.Test;

import edu.harvard.dbmi.avillach.domain.QueryFormat;
import edu.harvard.dbmi.avillach.domain.ResourceInfo;

import static edu.harvard.hms.dbmi.avillach.HttpClientUtil.*;

import java.io.IOException;

public class IRCTResourceIT extends BaseIT {
	
	@Test
	public void testInfo() throws UnsupportedOperationException, IOException {
		HttpResponse response = retrievePostResponse(endpointUrl+"info", null);
		System.out.println(response.getStatusLine().getStatusCode());
		System.out.println(IOUtils.toString(response.getEntity().getContent(), "UTF-8"));
	}
}

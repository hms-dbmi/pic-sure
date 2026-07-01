package edu.harvard.dbmi.avillach.service;

import static org.junit.Assert.assertEquals;

import edu.harvard.dbmi.avillach.util.HttpClientUtil;
import org.junit.Test;

public class HttpClientUtilTest {

	@Test
	public void testComposeUrl() {
		String test = HttpClientUtil.composeURL("http://foo.bar.com/pic-sure", "info");
		assertEquals("http://foo.bar.com/pic-sure/info", test);
		
		test = HttpClientUtil.composeURL("http://foo.bar.com/pic-sure/", "info");
		assertEquals("http://foo.bar.com/pic-sure/info", test);
		
		test = HttpClientUtil.composeURL("http://foo.bar.com/pic-sure///", "//info");
		assertEquals("http://foo.bar.com/pic-sure/info", test);
		
		test = HttpClientUtil.composeURL("http://foo.bar.com/pic-sure", "info/something/something");
		assertEquals("http://foo.bar.com/pic-sure/info/something/something", test);
		
		test = HttpClientUtil.composeURL("http://foo.bar.com/pic-sure/", "/info");
		assertEquals("http://foo.bar.com/pic-sure/info", test);

		test = HttpClientUtil.composeURL("http://localhost:8080", "/info");
		assertEquals("http://localhost:8080/info", test);

		test = HttpClientUtil.composeURL("http://localhost:8080/", "/info");
		assertEquals("http://localhost:8080/info", test);
	}
}

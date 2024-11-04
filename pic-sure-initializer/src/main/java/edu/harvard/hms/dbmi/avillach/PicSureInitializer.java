package edu.harvard.hms.dbmi.avillach;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PicSureInitializer 
{
	private static String url, users, resources, token;
	private static Options options;
	private static Logger logger = LoggerFactory.getLogger(PicSureInitializer.class);
	
	public static void main( String[] args ) throws UnsupportedEncodingException, ClientProtocolException, IOException, ParseException
	{
		createOptions();
		try {
			parseCommandLine(args);
			populateUsers();
			populateResources();
		}catch(Exception e) {
			e.printStackTrace();
			printHelpAndExit();
		}
	}

	private static void populateUsers() throws UnsupportedEncodingException, ClientProtocolException, IOException, URISyntaxException {
		logger.error("setting users");
		post("/user", users);
	}

	private static void populateResources() throws UnsupportedEncodingException, ClientProtocolException, IOException, URISyntaxException {
		logger.error("setting resources");
		post("/resource", resources);
	}

	private static void post(String resourcePath, String body) throws UnsupportedEncodingException, IOException, ClientProtocolException, URISyntaxException {
		URI baseUri = new URI(url + resourcePath);
		HttpClient client = HttpClientBuilder.create().build();
		HttpPost post = new HttpPost(baseUri);
		post.setEntity(new StringEntity(body));
		post.addHeader("Authorization", "Bearer " + token);
		post.addHeader("Content-type","application/json");
		HttpResponse response = client.execute(post);
		if(response.getStatusLine().getStatusCode() != 200) {
			logger.error("Response status code not 200: " + response.getStatusLine().getStatusCode() + "  " + response.getStatusLine().getReasonPhrase());
		}else {
			logger.info("Success");
		}
	}

	private static void parseCommandLine(String[] args) throws ParseException, FileNotFoundException, IOException {
		CommandLine cmd = new DefaultParser().parse(options, args);
		if(cmd.hasOption("h")) {
			printHelpAndExit();
		}
		if(cmd.hasOption("h") || ( ! (cmd.hasOption("t") && cmd.hasOption("p") && cmd.hasOption("u") && cmd.hasOption("r")))) {
			printHelpAndExit();
		}
		url = cmd.getOptionValue("p");
		users = EntityUtils.toString(new FileInputStream(cmd.getOptionValue("u")), "UTF-8");
		resources = EntityUtils.toString(new FileInputStream(cmd.getOptionValue("r")), "UTF-8");
		token = cmd.getOptionValue("t");
	}

	private static void createOptions() {
		options = new Options();
		options.addOption("p", true, "REQUIRED : The PIC-SURE 2 base URL, this typically ends with \"PICSURE\" and should not have a trailing slash");
		options.addOption("t", true, "REQUIRED : An authorized ROLE_SYSTEM user's token for the PIC-SURE 2 instance pointed at by the base URL");
		options.addOption("r", true, 
				"REQUIRED : The resources initialization file, the contents of this file should be a JSON array "
				+ "where each entry is a hash of string keys(name, description, baseUrl, token) and "
				+ "string values for each of those keys");
		options.addOption("u", true, 
				"REQUIRED : The users initialization file, the contents of this file should be a JSON array where "
				+ "each entry is a hash of string keys(userId, subject, roles) and string values for "
				+ "each of those keys");
		options.addOption("h", false, "Prints this help documentation");
	}

	private static void printHelpAndExit() {
		new HelpFormatter().printHelp("java -jar PIC-SURE-2_initializer.jar -h http://pic-sure-2-api/PICSURE -u users.json -r resources.json", options);
		System.exit(-1);
	}
	
}

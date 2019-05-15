package edu.harvard.hms.dbmi.avillach;

import javax.ejb.Startup;
import javax.servlet.ServletContextListener;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

@Startup
@ApplicationPath("pic-sure")
public class JAXRSConfiguration extends Application implements ServletContextListener {
	
	public JAXRSConfiguration() {
		
	}

}

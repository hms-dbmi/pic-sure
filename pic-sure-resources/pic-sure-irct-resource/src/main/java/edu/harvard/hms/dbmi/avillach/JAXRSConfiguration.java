package edu.harvard.hms.dbmi.avillach;

import javax.annotation.PostConstruct;
import javax.ejb.Startup;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

@Startup
@ApplicationPath("pic-sure")
public class JAXRSConfiguration extends Application implements ServletContextListener {
	
	public JAXRSConfiguration() {
		
	}

}

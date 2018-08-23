package edu.harvard.hms.dbmi.avillach;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

@ApplicationPath("pic-sure")
@WebListener
public class JAXRSConfiguration extends Application implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent event) {
        ServletContext servletContext = event.getServletContext(); 
        servletContext.setInitParameter("resteasy.resources", "org.jboss.resteasy.plugins.stats.RegistryStatsResource");
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        // NOOP.
    }

	
}

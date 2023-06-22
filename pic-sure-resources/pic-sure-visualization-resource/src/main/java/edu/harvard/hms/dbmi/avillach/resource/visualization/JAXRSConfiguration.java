package edu.harvard.hms.dbmi.avillach.resource.visualization;

import edu.harvard.hms.dbmi.avillach.resource.visualization.filter.HeaderFilter;

import javax.inject.Inject;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;
import java.util.HashSet;
import java.util.Set;

@ApplicationPath("pic-sure")
@WebListener
public class JAXRSConfiguration extends Application implements ServletContextListener {

	@Inject
	private ApplicationProperties appProperties;

	@Override
	public void contextInitialized(ServletContextEvent event) {
		ServletContext servletContext = event.getServletContext();
		appProperties.init(servletContext.getContextPath());
		servletContext.setInitParameter("resteasy.resources", "org.jboss.resteasy.plugins.stats.RegistryStatsResource");
	}

	@Override
	public void contextDestroyed(ServletContextEvent event) {
		// NOOP.
	}

	@Override
	public Set<Class<?>> getClasses() {
		final Set<Class<?>> classes = new HashSet<>();
		classes.add(HeaderFilter.class);
		return classes;
	}

}

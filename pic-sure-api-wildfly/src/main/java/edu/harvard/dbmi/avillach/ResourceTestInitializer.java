package edu.harvard.dbmi.avillach;

import javax.annotation.PostConstruct;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.entity.User;

@Singleton
@Startup
public class ResourceTestInitializer 
{
    @PersistenceContext
    private EntityManager em;
    
    @PostConstruct
    public void insertTestUsers() {
		Resource fooResource = new Resource()
//				.setBaseUrl("https://nhanes.hms.harvard.edu/rest/v1")
                .setBaseUrl("http://localhost:8080/pic-sure-api-wildfly-2.0.0-SNAPSHOT/pic-sure/v1.4")
				.setDescription("HMS DBMI NHANES PIC-SURE 1.4")
				.setName("nhanes.hms.harvard.edu");
		em.persist(fooResource);
    }
    
}

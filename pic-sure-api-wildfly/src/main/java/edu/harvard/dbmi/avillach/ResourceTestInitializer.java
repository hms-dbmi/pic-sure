package edu.harvard.dbmi.avillach;

import javax.annotation.PostConstruct;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.entity.User;
import edu.harvard.hms.dbmi.avillach.IRCTResourceRS;

@Singleton
@Startup
public class ResourceTestInitializer 
{
    @PersistenceContext(unitName = "picsure")
    private EntityManager em;
    
    @PostConstruct
    public void insertTestUsers() {
		Resource fooResource = new Resource()
//				.setBaseUrl("https://nhanes.hms.harvard.edu/rest/v1")
                .setBaseUrl("http://localhost:8080/pic-sure-irct-resource-2.0.0-SNAPSHOT/pic-sure/v1.4")
				.setDescription("HMS DBMI NHANES PIC-SURE 1.4  Supply token with key '" + IRCTResourceRS.IRCT_BEARER_TOKEN_KEY + "'")
				.setName("nhanes.hms.harvard.edu")
                .setToken("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmb29AYmFyLmNvbSIsImlzcyI6ImJhciIsImV4cCI6ODY1NTI4Mzk4NTQzLCJpYXQiOjE1Mjg0ODQ5NDMsImp0aSI6IkZvbyIsImVtYWlsIjoiZm9vQGJhci5jb20ifQ.KE2NIfCzQnd_vhykhb0sHdPHEwvy2Wphc4UVsKAVTgM");
		em.persist(fooResource);

        Resource aggregateResource = new Resource()
                .setBaseUrl("http://localhost:8080/pic-sure-api-wildfly-2.0.0-SNAPSHOT/pic-sure/group")
                .setToken("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmb29AYmFyLmNvbSIsImlzcyI6ImJhciIsImV4cCI6ODY1NTI4Mzk4NTQzLCJpYXQiOjE1Mjg0ODQ5NDMsImp0aSI6IkZvbyIsImVtYWlsIjoiZm9vQGJhci5jb20ifQ.KE2NIfCzQnd_vhykhb0sHdPHEwvy2Wphc4UVsKAVTgM")
                .setDescription("Aggregate Resource RS")
                .setName("Aggregate Resource RS");
        em.persist(aggregateResource);

        Resource gnomeI2B2Resource = new Resource()
                .setBaseUrl("http://localhost:8080/pic-sure-api-wildfly-2.0.0-SNAPSHOT/pic-sure/gnome-i2b2-count")
                .setToken("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmb29AYmFyLmNvbSIsImlzcyI6ImJhciIsImV4cCI6ODY1NTI4Mzk4NTQzLCJpYXQiOjE1Mjg0ODQ5NDMsImp0aSI6IkZvbyIsImVtYWlsIjoiZm9vQGJhci5jb20ifQ.KE2NIfCzQnd_vhykhb0sHdPHEwvy2Wphc4UVsKAVTgM")
                .setDescription("Gnome I2B2 Count Resource RS")
                .setName("Gnome I2B2 Count Resource RS");
        em.persist(gnomeI2B2Resource);
    }
    
}

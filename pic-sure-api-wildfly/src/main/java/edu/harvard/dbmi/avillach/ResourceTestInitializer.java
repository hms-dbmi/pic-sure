package edu.harvard.dbmi.avillach;

import javax.annotation.PostConstruct;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.hms.dbmi.avillach.IRCTResourceRS;

import static edu.harvard.dbmi.avillach.service.HttpClientUtil.composeURL;

@Singleton
@Startup
public class ResourceTestInitializer
{
    @PersistenceContext(unitName = "picsure")
    private EntityManager em;

    @PostConstruct
    public void insertTestResources() {
        String TARGET_PICSURE_URL = System.getenv("TARGET_PICSURE_URL");

		Resource fooResource = new Resource()
				.setTargetURL("https://nhanes.hms.harvard.edu/rest/v1/")
//                .setTargetURL("http://localhost:8080/pic-sure-api-wildfly-2.0.0-SNAPSHOT/pic-sure/v1.4")
                .setResourceRSPath(composeURL(TARGET_PICSURE_URL, "v1.4"))
				.setDescription("HMS DBMI NHANES PIC-SURE 1.4  Supply token with key '" + IRCTResourceRS.IRCT_BEARER_TOKEN_KEY + "'")
				.setName("nhanes.hms.harvard.edu")
                .setToken("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmb29AYmFyLmNvbSIsImlzcyI6ImJhciIsImV4cCI6ODY1NTI4Mzk4NTQzLCJpYXQiOjE1Mjg0ODQ5NDMsImp0aSI6IkZvbyIsImVtYWlsIjoiZm9vQGJhci5jb20ifQ.KE2NIfCzQnd_vhykhb0sHdPHEwvy2Wphc4UVsKAVTgM");
		em.persist(fooResource);

        Resource aggregateResource = new Resource()
//                .setTargetURL("http://localhost:8080/pic-sure-api-wildfly-2.0.0-SNAPSHOT/pic-sure/group")
                .setTargetURL(TARGET_PICSURE_URL)
                .setResourceRSPath(composeURL(TARGET_PICSURE_URL,"group"))
                .setToken("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmb29AYmFyLmNvbSIsImlzcyI6ImJhciIsImV4cCI6ODY1NTI4Mzk4NTQzLCJpYXQiOjE1Mjg0ODQ5NDMsImp0aSI6IkZvbyIsImVtYWlsIjoiZm9vQGJhci5jb20ifQ.KE2NIfCzQnd_vhykhb0sHdPHEwvy2Wphc4UVsKAVTgM")
                .setDescription("Aggregate Resource RS")
                .setName("Aggregate Resource RS");
        em.persist(aggregateResource);
    }

}

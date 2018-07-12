package edu.harvard.dbmi.avillach;

import edu.harvard.dbmi.avillach.data.entity.User;
import edu.harvard.dbmi.avillach.util.PicsureNaming;

import javax.annotation.PostConstruct;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

@Singleton
@Startup
public class UserTestInitializer 
{
    @PersistenceContext(unitName = "picsure")
    private EntityManager em;
    
    @PostConstruct
    public void insertTestUsers() {
		User systemUser = new User()
				.setRoles(PicsureNaming.RoleNaming.ROLE_SYSTEM)
				.setSubject("samlp|foo@bar.com")
				.setUserId("foo@bar.com");
		User nonSystemUser = new User()
				.setRoles("")
				.setSubject("samlp|foo2@bar.com")
				.setUserId("foo2@bar.com");
		User tokenInspectionSystem = new User()
				.setRoles(PicsureNaming.RoleNaming.ROLE_TOKEN_INTROSPECTION)
				.setSubject("samlp|foo3@bar.com")
				.setUserId("foo3@bar.com");
		User tokenInspectionUser = new User()
				.setRoles(PicsureNaming.RoleNaming.ROLE_INTROSPECTION_USER)
				.setSubject("samlp|foo4@bar.com")
				.setUserId("foo4@bar.com");
		em.persist(systemUser);
		em.persist(nonSystemUser);
		em.persist(tokenInspectionUser);
		em.persist(tokenInspectionSystem);
    }
    
}

package edu.harvard.dbmi.avillach;

import javax.annotation.PostConstruct;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import edu.harvard.dbmi.avillach.data.entity.User;
import edu.harvard.dbmi.avillach.utils.PicsureWarNaming;

@Singleton
@Startup
public class UserTestInitializer 
{
    @PersistenceContext
    private EntityManager em;
    
    @PostConstruct
    public void insertTestUsers() {
		User systemUser = new User()
				.setRoles(PicsureWarNaming.RoleNaming.ROLE_SYSTEM)
				.setSubject("samlp|foo@bar.com")
				.setUserId("foo@bar.com");
		User nonSystemUser = new User()
				.setRoles("")
				.setSubject("samlp|foo2@bar.com")
				.setUserId("foo2@bar.com");
		em.persist(systemUser);
		em.persist(nonSystemUser);
    }
    
}

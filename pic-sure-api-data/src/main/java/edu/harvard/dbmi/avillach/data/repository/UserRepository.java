package edu.harvard.dbmi.avillach.data.repository;

import javax.enterprise.context.ApplicationScoped;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;
import javax.transaction.Transactional;

import edu.harvard.dbmi.avillach.data.entity.User;

@Transactional
@ApplicationScoped
public class UserRepository extends BaseRepository<User> {
	
	protected UserRepository() {
		super(new User());
	}

	@PersistenceContext
	private EntityManager em;
	
	public User findBySubject(String subject) {
		CriteriaQuery<User> query = em.getCriteriaBuilder().createQuery(User.class);
		Root<User> queryRoot = query.from(User.class);
		query.select(queryRoot);
		return em.createQuery(query.where(eq(queryRoot, "subject", subject))).getSingleResult();
	}

	public User findOrCreate(String subject, String userId) {
		User user = findBySubject(subject);
		
		if(user == null) {
			user = createUser(subject, userId);
		}
		return user;
	}
	
	public User createUser(String subject, String userId) {
		em.persist(new User().setSubject(subject).setUserId(userId));
		return findBySubject(subject);
	}
}

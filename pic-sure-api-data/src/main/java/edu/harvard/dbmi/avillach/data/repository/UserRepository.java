package edu.harvard.dbmi.avillach.data.repository;

import javax.enterprise.context.ApplicationScoped;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.NonUniqueResultException;
import javax.persistence.PersistenceContext;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;
import javax.transaction.Transactional;
import javax.ws.rs.NotAuthorizedException;

import edu.harvard.dbmi.avillach.data.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Transactional
@ApplicationScoped
public class UserRepository extends BaseRepository<User> {

	private Logger logger = LoggerFactory.getLogger(UserRepository.class);
	
	public UserRepository() {
		super(new User());
	}
	
	public User findBySubject(String subject) {
		CriteriaQuery<User> query = em.getCriteriaBuilder().createQuery(User.class);
		Root<User> queryRoot = query.from(User.class);
		query.select(queryRoot);
		return em.createQuery(query.where(eq(queryRoot, "subject", subject))).getSingleResult();
	}

	public User findOrCreate(String subject, String userId) {
		User user;
		try{
			user = findBySubject(subject);
			logger.debug("findOrCreate() user " + userId + "found a user");
		} catch (NoResultException e) {
			logger.error("findOrCreate() UserId " + userId +
					" could not be found by `entityManager`");
			user = createUser(subject, userId);
		}catch(NonUniqueResultException e){
			logger.error("findOrCreate() Exception:" + e.getMessage());
			throw new NotAuthorizedException("Duplicate User Found : " + userId, e);
		}
		return user;
	}
	
	public User createUser(String subject, String userId) {
		logger.info("createUser() creating user by userId: " + userId);
		em.persist(new User().setSubject(subject).setUserId(userId));
		return findBySubject(subject);
	}
}

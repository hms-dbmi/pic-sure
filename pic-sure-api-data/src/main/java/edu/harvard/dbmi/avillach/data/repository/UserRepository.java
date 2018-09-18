package edu.harvard.dbmi.avillach.data.repository;

import javax.enterprise.context.ApplicationScoped;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.NonUniqueResultException;
import javax.persistence.PersistenceContext;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;
import javax.transaction.Transactional;
import javax.ws.rs.NotAuthorizedException;

import edu.harvard.dbmi.avillach.data.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

@Transactional
@ApplicationScoped
public class UserRepository extends BaseRepository<User, UUID> {

	private Logger logger = LoggerFactory.getLogger(UserRepository.class);
	
	protected UserRepository() {
		super(User.class);
	}
	
	public User findBySubjectOrUserId(String subject, String userId) {
		CriteriaQuery<User> query = em.getCriteriaBuilder().createQuery(User.class);
		Root<User> queryRoot = query.from(User.class);
		query.select(queryRoot);
		CriteriaBuilder cb = cb();
		return em.createQuery(query
				.where(
						or(cb,
								eq(cb, queryRoot, "subject", subject),
								eq(cb, queryRoot, "userId", userId))))
				.getSingleResult();
	}

	/**
	 *
	 * @return
	 */
	public User findOrCreate(User inputUser) {
		User user = null;
		String subject = inputUser.getSubject(), userId = inputUser.getUserId();
		try{
			user = findBySubjectOrUserId(subject, userId);
			logger.info("findOrCreate(), trying to find user: {subject: " + subject+
					", userId: " + userId +
					"}, and found a user with uuid: " + user.getUuid()
					+ ", subject: " + user.getSubject()
					+ ", userId: " + user.getUserId());
		} catch (NoResultException e) {
			logger.debug("findOrCreate() UserId " + userId +
					" could not be found by `entityManager`, going to create a new user.");
			user = createUser(inputUser);
		}catch(NonUniqueResultException e){
			logger.error("findOrCreate() " + e.getClass().getSimpleName() + ": " + e.getMessage());
		}
		return user;
	}
	
	private User createUser(User inputUser) {
		String subject = inputUser.getSubject(), userId = inputUser.getUserId();
		if (subject == null && userId == null){
			logger.error("createUser() cannot create user when both subject and userId are null");
			return null;
		}
		logger.debug("createUser() creating user, subject: " + subject +", userId: " + userId + " ......");
		em().persist(inputUser);

		User result = getById(inputUser.getUuid());
		if (result != null)
			logger.info("createUser() created user: uuid: " + result.getUuid() + ", subject: " + subject +", userId: " + userId + ", role: " + result.getRoles());

		return result;
	}

	public User changeRole(User user, String role){
		logger.info("Starting changing the role of user: " + user.getUuid()
				+ ", with userId: " + user.getUserId() + ", to " + role);
		user.setRoles(role);
		em().merge(user);
		User updatedUser = getById(user.getUuid());
		logger.info("User: " + updatedUser.getUuid() + ", with userId: " +
				updatedUser.getUserId() + ", now has a new role: " + updatedUser.getRoles());
		return updatedUser;
	}

	@Override
	public void persist(User user) {
		findOrCreate(user);
	}
}

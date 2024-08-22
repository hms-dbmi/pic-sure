package edu.harvard.hms.dbmi.avillach.auth.repository;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * <p>Provides operations for the User entity to interact with a database.</p>
 *
 * @see User
 */

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    User findBySubject(String subject);

    User findBySubjectAndConnection(String subject, Connection connection);

    List<User> findByConnectionAndMatched(Connection connection, boolean matched);

    /**
     * <p>Find a user by email.</p>
     *
     * @param email the email to search for
     * @return User
     */
    User findByEmail(String email);

    Optional<User> findByEmailAndConnectionId(String email, String id);

    Set<User> findByPassportIsNotNull();

}

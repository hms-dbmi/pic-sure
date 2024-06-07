package edu.harvard.hms.dbmi.avillach.auth.repository;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * <p>Provides operations for the AccessRule entity to interact with a database.</p>
 *
 * @see AccessRule
 */
@Repository
public interface AccessRuleRepository extends JpaRepository<AccessRule, UUID> {

        AccessRule findByName(String name);

}

package edu.harvard.hms.dbmi.avillach.auth.repository;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * <p>Provides operations for the AccessRule entity to interact with a database.</p>
 *
 * @see AccessRule
 */
@Repository
public interface AccessRuleRepository extends JpaRepository<AccessRule, UUID> {

        AccessRule findByName(String name);

        @Query("SELECT p.accessRules FROM privilege p WHERE p.uuid = :uuid")
        Set<AccessRule> findAccessRulesByPrivilegeId(@Param("uuid") UUID uuid);

        @Query("SELECT p.accessRules FROM privilege p WHERE p.uuid IN :privilegeIds")
        List<AccessRule> getAccessRulesByPrivilegeIds(@Param("privilegeIds") List<UUID> privilegeIds);
}

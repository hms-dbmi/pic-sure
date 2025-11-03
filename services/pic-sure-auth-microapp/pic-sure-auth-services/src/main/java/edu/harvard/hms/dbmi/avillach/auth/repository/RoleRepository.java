package edu.harvard.hms.dbmi.avillach.auth.repository;

import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Set;
import java.util.UUID;

/**
 * <p>Provides operations for the Role entity to interact with a database.</p>
 * @see Role
 */

@Repository
public interface RoleRepository extends JpaRepository<Role, UUID> {

    Role findByName(String name);

    // find all user roles where uuid is in the list of uuids
    Set<Role> findByUuidIn(Set<UUID> uuids);

    Set<Role> findByNameIn(Set<String> names);

    Set<Role> findByNameNotIn(Set<String> names);
}

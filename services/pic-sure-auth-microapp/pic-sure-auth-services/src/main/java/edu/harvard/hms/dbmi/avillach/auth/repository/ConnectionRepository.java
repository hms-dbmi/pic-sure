package edu.harvard.hms.dbmi.avillach.auth.repository;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * <p>Provides operations for the Connection entity to interact with a database.</p>
 *
 * @see Connection
 */
@Repository
public interface ConnectionRepository extends JpaRepository<Connection, UUID> {

    Connection findByLabel(String label);

    Optional<Connection> findById(String id);

    void deleteById(String id);

}

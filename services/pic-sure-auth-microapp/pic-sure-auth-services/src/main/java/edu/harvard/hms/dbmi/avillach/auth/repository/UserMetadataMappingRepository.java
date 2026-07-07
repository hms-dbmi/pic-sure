package edu.harvard.hms.dbmi.avillach.auth.repository;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.UserMetadataMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * <p>Provides operations for the UserMetadataMapping entity to interact with a database.</p>
 * @see UserMetadataMapping
 */

@Repository
public interface UserMetadataMappingRepository extends JpaRepository<UserMetadataMapping, UUID> {

    List<UserMetadataMapping> findByConnection(Connection connection);
	
}

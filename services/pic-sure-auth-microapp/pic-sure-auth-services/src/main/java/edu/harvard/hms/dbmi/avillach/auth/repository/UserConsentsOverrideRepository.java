package edu.harvard.hms.dbmi.avillach.auth.repository;

import edu.harvard.hms.dbmi.avillach.auth.entity.UserConsentsOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * <p>Provides operations for the UserConsentsOverride entity to interact with a database.</p>
 * @see UserConsentsOverride
 */

@Repository
public interface UserConsentsOverrideRepository extends JpaRepository<UserConsentsOverride, UUID> {

    @Query("SELECT uco FROM user_consents_override uco WHERE uco.userId = :userId")
    UserConsentsOverride findByUserId(@Param("userId") UUID userId);

}

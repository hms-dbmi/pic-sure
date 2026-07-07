package edu.harvard.hms.dbmi.avillach.auth.repository;

import edu.harvard.hms.dbmi.avillach.auth.entity.UserConsents;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UserConsentsRepository extends JpaRepository<UserConsents, UUID> {

    @Query("SELECT uc FROM user_consents uc WHERE uc.userId = :userId")
    UserConsents findByUserId(@Param("userId") UUID userId);


}

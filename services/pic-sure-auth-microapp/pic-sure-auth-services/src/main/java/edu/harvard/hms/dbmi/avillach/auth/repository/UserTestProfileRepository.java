package edu.harvard.hms.dbmi.avillach.auth.repository;

import edu.harvard.hms.dbmi.avillach.auth.entity.UserTestProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UserTestProfileRepository extends JpaRepository<UserTestProfile, UUID> {

    @Query("SELECT utp FROM user_test_profile utp WHERE utp.userId = :userId")
    UserTestProfile findByUserId(@Param("userId") UUID userId);
}

package edu.harvard.hms.dbmi.avillach.auth.repository;

import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * <p>Provides operations for the Application entity to interact with a database.</p>>
 *
 * @see Application
 */

@Repository
public interface ApplicationRepository extends JpaRepository<Application, UUID> {

    Application findByName(String name);

    Optional<Application> findByUuid(UUID uuid);

}
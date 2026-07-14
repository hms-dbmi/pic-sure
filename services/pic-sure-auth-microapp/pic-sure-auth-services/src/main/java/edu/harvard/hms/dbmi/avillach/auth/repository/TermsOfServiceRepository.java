package edu.harvard.hms.dbmi.avillach.auth.repository;

import edu.harvard.hms.dbmi.avillach.auth.entity.TermsOfService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * <p>Provides operations for the TermsOfService  entity to interact with a database.</p>
 * @see TermsOfService
 */
@Repository
public interface TermsOfServiceRepository extends JpaRepository<TermsOfService, UUID> {

    /**
     * <p>Find the latest TermsOfService by date updated.</p>
     * @return TermsOfService
     */
    Optional<TermsOfService> findTopByOrderByDateUpdatedDesc();

}

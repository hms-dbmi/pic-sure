package edu.harvard.hms.dbmi.avillach.data.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import edu.harvard.hms.dbmi.avillach.data.entity.Site;

/**
 * Ported from the legacy {@code edu.harvard.dbmi.avillach.data.repository.SiteRepository} (CDI/{@code BaseRepository}) to a Spring Data JPA
 * interface. {@code domain} has a unique constraint (see {@code Site}'s {@code @Table}), so a derived query returning {@link Optional}
 * replaces legacy's generic {@code getByColumn("domain", ...)}.
 */
public interface SiteRepository extends JpaRepository<Site, UUID> {

    Optional<Site> findByDomain(String domain);
}

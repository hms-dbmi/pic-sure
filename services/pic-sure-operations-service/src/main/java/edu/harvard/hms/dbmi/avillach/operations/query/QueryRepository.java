package edu.harvard.hms.dbmi.avillach.operations.query;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Ported from the legacy {@code edu.harvard.dbmi.avillach.data.repository.QueryRepository} (CDI/{@code BaseRepository}) to a Spring Data
 * JPA interface. {@code findById}/{@code save} are inherited from {@link JpaRepository}.
 */
public interface QueryRepository extends JpaRepository<Query, UUID> {
}

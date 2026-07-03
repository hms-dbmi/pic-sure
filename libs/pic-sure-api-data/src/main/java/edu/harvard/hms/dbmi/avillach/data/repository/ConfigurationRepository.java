package edu.harvard.hms.dbmi.avillach.data.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import edu.harvard.hms.dbmi.avillach.data.entity.Configuration;

/**
 * Ported from the legacy {@code edu.harvard.dbmi.avillach.data.repository.ConfigurationRepository} (CDI/{@code BaseRepository}), which had
 * no custom finder methods beyond generic CRUD. A bare {@link JpaRepository} therefore preserves the same lookups
 * ({@code findById}/{@code findAll}/ {@code save}/{@code delete}, all inherited).
 */
public interface ConfigurationRepository extends JpaRepository<Configuration, UUID> {
}

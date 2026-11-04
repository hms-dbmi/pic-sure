package edu.harvard.hms.dbmi.avillach.operations.query;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for queries; {@code findById} and {@code save} are inherited from {@link JpaRepository}. */
public interface QueryRepository extends JpaRepository<Query, UUID> {
}

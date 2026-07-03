package edu.harvard.hms.dbmi.avillach.data.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import edu.harvard.hms.dbmi.avillach.data.entity.Query;

/**
 * Ported from the legacy {@code edu.harvard.dbmi.avillach.data.repository.QueryRepository} (CDI/{@code BaseRepository}) to a Spring Data
 * JPA interface. {@code findById}/{@code save} are inherited from {@link JpaRepository}.
 *
 * <p>The entity is named {@code Query}, which collides with Spring Data's {@code org.springframework.data.jpa.repository.Query} annotation;
 * the annotation is therefore referenced by its fully-qualified name below instead of being imported.
 */
public interface QueryRepository extends JpaRepository<Query, UUID> {

    /**
     * Legacy ran {@code SELECT * FROM query WHERE CONVERT(metadata USING utf8) LIKE ?} (MySQL's charset-conversion form of
     * {@code CONVERT}), with the LIKE pattern built as {@code "%commonAreaUUID\":\"" + caID + "\"%"}. That exact literal is valid on MySQL
     * but its {@code USING <charset>} clause cannot be parsed by H2 at all (confirmed against H2 2.3.232, including {@code MODE=MySQL}: the
     * parser rejects the {@code USING} keyword outright, regardless of the target column type), which makes it impossible to exercise with
     * a real H2-backed test as required by this module's DoD.
     *
     * <p>This uses the two-argument {@code CONVERT(expr, type)} cast form instead -- also standard, documented MySQL syntax (see the MySQL
     * {@code CAST}/{@code CONVERT} function reference) -- and decodes the BLOB using the connection's default character set rather than an
     * explicit {@code utf8} override. That is a behavior-preserving swap for this application: the connection is set to {@code utf8} (see
     * the schema's {@code SET NAMES utf8}), and the fields matched via {@code LIKE} (JSON keys/UUIDs) are ASCII, so the match is unaffected
     * either way. The {@code CHAR(8192)} length mirrors {@code Query.metadata}'s own {@code @Column(length =
     * 8192)} bound. This form parses identically on MySQL and H2 (verified) and decodes UTF-8 bytes back to text, so both the JVM and the
     * DB behave the same in production and in this module's tests.
     *
     * <p>The pattern is built by the {@link #getQueryUUIDFromCommonAreaUUID(UUID)} default method wrapping this native query, so callers
     * pass a {@link UUID}, exactly like the legacy {@code BaseRepository}-backed method did.
     *
     * <p>When no row matches, Spring Data JPA catches the underlying {@code NoResultException} and returns {@code null} for a single-result
     * query method, mirroring the legacy behavior of swallowing {@code PersistenceException} and returning {@code null}.
     */
    @org.springframework.data.jpa.repository.Query(
        value = "SELECT * FROM query WHERE CONVERT(metadata, CHAR(8192)) LIKE :caIDRegex", nativeQuery = true
    )
    Query findByCommonAreaUUIDRegex(@Param("caIDRegex") String caIDRegex);

    default Query getQueryUUIDFromCommonAreaUUID(UUID caID) {
        String caIDRegex = "%commonAreaUUID\":\"" + caID + "\"%";
        return findByCommonAreaUUIDRegex(caIDRegex);
    }
}

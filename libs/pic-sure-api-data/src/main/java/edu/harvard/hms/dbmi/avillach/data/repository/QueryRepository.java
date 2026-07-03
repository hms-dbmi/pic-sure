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
     * {@code CONVERT}), with the LIKE pattern built as {@code "%commonAreaUUID\":\"" + caID + "\"%"}. This is kept byte-for-byte identical
     * to production: {@code metadata} is a {@code BLOB}, and {@code CONVERT(... USING utf8)} decodes those bytes to text using MySQL's
     * charset-conversion machinery before the {@code LIKE} match runs. Swapping to the two-argument {@code CONVERT(expr, type)} cast form
     * (or any other rewrite) would ship different SQL in production than what has been running against MySQL, which is not an acceptable
     * trade-off purely to make the query parseable by H2 -- correctness of the GIC {@code commonAreaUUID} lookup takes priority over H2
     * coverage of this one native query. H2 cannot parse the {@code USING <charset>} clause at all (confirmed against H2 2.3.232, including
     * {@code MODE=MySQL}: the parser rejects the {@code USING} keyword outright), so the corresponding tests are disabled against H2 rather
     * than run against a rewritten query; see {@code RepositorySmokeTest} for the MySQL-only rationale.
     *
     * <p>The pattern is built by the {@link #getQueryUUIDFromCommonAreaUUID(UUID)} default method wrapping this native query, so callers
     * pass a {@link UUID}, exactly like the legacy {@code BaseRepository}-backed method did.
     *
     * <p>When no row matches, Spring Data JPA catches the underlying {@code NoResultException} and returns {@code null} for a single-result
     * query method, mirroring the legacy behavior of swallowing {@code PersistenceException} and returning {@code null}.
     */
    @org.springframework.data.jpa.repository.Query(
        value = "SELECT * FROM query WHERE CONVERT(metadata USING utf8) LIKE :caIDRegex", nativeQuery = true
    )
    Query findByCommonAreaUUIDRegex(@Param("caIDRegex") String caIDRegex);

    default Query getQueryUUIDFromCommonAreaUUID(UUID caID) {
        String caIDRegex = "%commonAreaUUID\":\"" + caID + "\"%";
        return findByCommonAreaUUIDRegex(caIDRegex);
    }
}

package edu.harvard.hms.dbmi.avillach.operations.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.JpaSystemException;

import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;
import edu.harvard.dbmi.avillach.domain.StoredQuery;

/**
 * Proves the {@code query} row id is assigned by the caller and stored unchanged. Under the former bare {@code @GeneratedValue}, Hibernate
 * 6.6 refused an instance that arrived with an id: {@code repo.save} merged it and failed with a stale-state error because no such row
 * existed, and {@code persist} rejected it as detached.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(QueryPersistenceService.class)
class QueryAssignedIdTest {

    @Autowired
    private QueryPersistenceService service;

    @Autowired
    private QueryRepository repo;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void assignedIdIsStoredAsGiven() {
        UUID assigned = UUID.randomUUID();
        Query entity = new Query();
        entity.setUuid(assigned);
        entity.setQuery("{\"select\":[\"foo\"]}");

        assertThat(repo.save(entity).getUuid()).isEqualTo(assigned);
        entityManager.flush();
        entityManager.clear();

        assertThat(repo.findById(assigned)).get().extracting(Query::getQuery).isEqualTo("{\"select\":[\"foo\"]}");
        assertThat(repo.count()).isEqualTo(1);

        UUID viaService = UUID.randomUUID();
        assertThat(service.save(viaService, new SaveQueryRequest("{}", "rr", "QUEUED", "3", null))).isEqualTo(viaService);
        entityManager.flush();
        entityManager.clear();
        assertThat(service.get(viaService).picsureId()).isEqualTo(viaService);
    }

    @Test
    void saveWithoutIdStillGetsARandomId() {
        SaveQueryRequest req = new SaveQueryRequest("{}", "rr", "QUEUED", "3", null);

        UUID first = service.save(req);
        UUID second = service.save(null, req);
        entityManager.flush();
        entityManager.clear();

        assertThat(first).isNotNull().isNotEqualTo(second);
        assertThat(second).isNotNull();
        assertThat(repo.findById(first)).isPresent();
        assertThat(repo.findById(second)).isPresent();
    }

    @Test
    void secondSaveOfSameIdUpdatesInsteadOfInserting() {
        UUID assigned = UUID.randomUUID();
        service.save(assigned, new SaveQueryRequest("{\"q\":1}", "rr", "QUEUED", "3", null));
        entityManager.flush();
        entityManager.clear();

        assertThat(service.save(assigned, new SaveQueryRequest("{\"q\":2}", "other", "PENDING", "4", null))).isEqualTo(assigned);
        entityManager.flush();
        entityManager.clear();

        assertThat(repo.count()).isEqualTo(1);
        StoredQuery stored = service.get(assigned);
        assertThat(stored.status()).isEqualTo("PENDING");
        assertThat(stored.query()).isEqualTo("{\"q\":1}");
        assertThat(stored.version()).isEqualTo("3");
        assertThat(stored.readyTime()).isNull();

        Query loaded = repo.findById(assigned).orElseThrow();
        assertThat(loaded.isNew()).isFalse();
        loaded.setResourceResultId("changed");
        repo.save(loaded);
        entityManager.flush();
        entityManager.clear();
        assertThat(repo.count()).isEqualTo(1);
        assertThat(repo.findById(assigned).orElseThrow().getResourceResultId()).isEqualTo("changed");
    }

    @Test
    void savingWithoutAnIdFails() {
        assertThatThrownBy(() -> {
            repo.save(new Query());
            entityManager.flush();
        }).isInstanceOf(JpaSystemException.class).hasMessageContaining("must be manually assigned");
    }
}

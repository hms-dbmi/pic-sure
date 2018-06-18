package edu.harvard.dbmi.avillach.data.repository;

import edu.harvard.dbmi.avillach.data.entity.Query;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import java.util.UUID;

@Transactional
@ApplicationScoped
public class QueryRepository extends BaseRepository<Query, UUID>{

    protected QueryRepository() {super(Query.class);}
}

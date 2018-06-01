package edu.harvard.dbmi.avillach.data.repository;

import edu.harvard.dbmi.avillach.data.entity.Query;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;

@Transactional
@ApplicationScoped
public class QueryRepository extends BaseRepository<Query>{

    public QueryRepository() {super(new Query());}
}

package edu.harvard.dbmi.avillach.data.repository;

import edu.harvard.dbmi.avillach.data.entity.NamedDataset;
import edu.harvard.dbmi.avillach.data.entity.Site;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import java.util.UUID;

@Transactional
@ApplicationScoped
public class SiteRepository extends BaseRepository<Site, UUID>{
    protected SiteRepository() {super(Site.class);}
}

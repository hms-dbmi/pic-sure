package edu.harvard.hms.dbmi.avillach.auth.service;

import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import org.hibernate.envers.boot.internal.EnversService;
import org.hibernate.envers.event.spi.BaseEnversCollectionEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PreCollectionUpdateEvent;
import org.hibernate.event.spi.PreCollectionUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdateUserListener extends BaseEnversCollectionEventListener implements PostInsertEventListener, PreCollectionUpdateEventListener {
    private Logger logger = LoggerFactory.getLogger(this.getClass());

    //Cannot inject into stateless eventlistener
    private MailService mailService = new MailService();

   public UpdateUserListener(EnversService es){
        super(es);
    }

    //If the user's collection of roles is updated, send an email
    public void onPreUpdateCollection(PreCollectionUpdateEvent e){
        Object entity = e.getAffectedOwnerOrNull();
        if (entity instanceof User){
            logger.info("Roles updated for user " + ((User) entity).getSubject());
            mailService.sendUsersAccessEmail((User)entity);
        }
    }

    //When a new user is created, send an email
    public void onPostInsert(PostInsertEvent e){
        Object entity = e.getEntity();
        if (entity instanceof User){
            logger.info("New user added: " + ((User) entity).getSubject());
            mailService.sendUsersAccessEmail((User)entity);
        }
    }

    public boolean requiresPostCommitHanding(EntityPersister persister) {
        return this.getEnversService().getEntitiesConfigurations().isVersioned(persister.getEntityName());
    }

}

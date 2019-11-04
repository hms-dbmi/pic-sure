package edu.harvard.hms.dbmi.avillach.auth.service;

import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import org.hibernate.envers.boot.internal.EnversService;
import org.hibernate.envers.event.spi.BaseEnversCollectionEventListener;
import org.hibernate.event.spi.PostCollectionUpdateEvent;
import org.hibernate.event.spi.PostCollectionUpdateEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Event listener that triggers email notifications a user is updated.</p>
 */
public class UpdateUserListener extends BaseEnversCollectionEventListener implements PostInsertEventListener,
        PostCollectionUpdateEventListener{

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    //Cannot inject into stateless eventlistener
    private MailService mailService = new MailService();

   public UpdateUserListener(EnversService es){
        super(es);
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

    @Override
    public void onPostUpdateCollection(PostCollectionUpdateEvent event) {
        Object entity = event.getAffectedOwnerOrNull();
        if (entity instanceof User){
            logger.info("Roles updated for user " + ((User) entity).getSubject());
            mailService.sendUsersAccessEmail((User)entity);
        }
    }
}

package edu.harvard.hms.dbmi.avillach.auth;

import edu.harvard.hms.dbmi.avillach.auth.service.UpdateUserListener;
import org.hibernate.boot.Metadata;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.envers.boot.internal.EnversService;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;

/**
 * implement Integrator for post user data changes to trigger sending notification emails
 */
public class
EventListenerIntegrator implements Integrator

    {

    @Override
    public void integrate(Metadata metadata, SessionFactoryImplementor
            sessionFactory, SessionFactoryServiceRegistry serviceRegistry) {

        EventListenerRegistry eventListenerRegistry =
                serviceRegistry.getService(EventListenerRegistry.class);

        final EnversService enversService = serviceRegistry.getService( EnversService.class );

        eventListenerRegistry.appendListeners(EventType.POST_COLLECTION_UPDATE, new UpdateUserListener((enversService)));
        eventListenerRegistry.appendListeners(EventType.POST_INSERT, new UpdateUserListener((enversService)));

    }

    @Override
    public void disintegrate(SessionFactoryImplementor sessionFactory,
                             SessionFactoryServiceRegistry serviceRegistry) {

    }
}

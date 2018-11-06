package edu.harvard.hms.dbmi.avillach.auth;

import edu.harvard.hms.dbmi.avillach.auth.service.MailService;
import org.hibernate.boot.Metadata;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;

public class EventListenerIntegrator  implements Integrator {

    @Override
    public void integrate(Metadata metadata, SessionFactoryImplementor
            sessionFactory, SessionFactoryServiceRegistry serviceRegistry) {

        EventListenerRegistry eventListenerRegistry =
                serviceRegistry.getService(EventListenerRegistry.class);

        eventListenerRegistry.getEventListenerGroup(EventType.POST_COLLECTION_UPDATE)
                .appendListener(new MailService());

    }

    @Override
    public void disintegrate(SessionFactoryImplementor sessionFactory,
                             SessionFactoryServiceRegistry serviceRegistry) {

    }
}

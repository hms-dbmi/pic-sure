package edu.harvard.hms.dbmi.avillach.auth;

import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.BootstrapServiceRegistry;
import org.hibernate.boot.registry.BootstrapServiceRegistryBuilder;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;

public class HibernateUtil {
    private static StandardServiceRegistry registry;
    private static SessionFactory sessionFactory;

    public static SessionFactory getSessionFactory() {
        if (sessionFactory == null) {
            try {
                BootstrapServiceRegistry bootstrapRegistry =
                        new BootstrapServiceRegistryBuilder()
                                .applyIntegrator(new EventListenerIntegrator())
                                .build();

                StandardServiceRegistryBuilder registryBuilder =
                        new StandardServiceRegistryBuilder(bootstrapRegistry);

                registry = registryBuilder.build();

                MetadataSources sources = new MetadataSources(registry)
                        .addAnnotatedClass(User.class);

                Metadata metadata = sources.getMetadataBuilder().build();

                sessionFactory = metadata.getSessionFactoryBuilder().build();
            } catch (Exception e) {
                if (registry != null) {
                    StandardServiceRegistryBuilder.destroy(registry);
                }
                e.printStackTrace();
            }
        }
        return sessionFactory;
    }

    public static void shutdown() {
        if (registry != null) {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}

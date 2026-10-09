package edu.harvard.hms.dbmi.avillach.hpds.processing.v3.layout;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Stops startup when {@code HPDS_STORE_FORMAT} names neither {@value StoreFormat#JAVABIN} nor {@value StoreFormat#LAYOUT}. Without it an
 * unknown value would leave both sets of store beans unregistered, and startup would fail later on an unrelated missing bean. It runs as a
 * bean factory post-processor, before any store bean is created.
 */
@Component
public class StoreFormatGuard implements BeanFactoryPostProcessor, EnvironmentAware {

    private Environment environment;

    /**
     * @param environment the application environment holding {@code HPDS_STORE_FORMAT}
     */
    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    /**
     * @param beanFactory unused
     * @throws IllegalStateException when {@code HPDS_STORE_FORMAT} is set to an unknown value
     */
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        check(environment.getProperty(StoreFormat.PROPERTY));
    }

    /**
     * Accepts an absent value (the javabin default) and either known format in any letter case, as the store beans' conditions do.
     *
     * @param format the configured value, or null when unset
     * @throws IllegalStateException for any other value
     */
    public static void check(String format) {
        if (format == null || StoreFormat.JAVABIN.equalsIgnoreCase(format) || StoreFormat.LAYOUT.equalsIgnoreCase(format)) {
            return;
        }
        throw new IllegalStateException(
            StoreFormat.PROPERTY + "=" + format + " is not a known store format. Set it to " + StoreFormat.JAVABIN + " (the default) or "
                + StoreFormat.LAYOUT + "."
        );
    }
}

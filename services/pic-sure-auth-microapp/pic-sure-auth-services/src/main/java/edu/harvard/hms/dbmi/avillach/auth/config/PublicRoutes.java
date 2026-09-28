package edu.harvard.hms.dbmi.avillach.auth.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;

/**
 * The complete set of routes PSAMA serves without a token. Any request that matches none of them requires authentication. <p> The shipped
 * routes are read from the {@code application.properties} packaged with PSAMA, never from the {@link Environment}, so no environment
 * variable, system property, or external config file can replace or remove one. A deployment can only add routes, through
 * {@code security.public-routes.additional}.
 *
 * @param shipped routes under {@code security.public-routes.shipped} in the packaged {@code application.properties}
 * @param additional routes a deployment adds under {@code security.public-routes.additional}
 */
public record PublicRoutes(List<PublicRoute> shipped, List<PublicRoute> additional) {

    static final String SHIPPED_PREFIX = "security.public-routes.shipped";

    static final String ADDITIONAL_PREFIX = "security.public-routes.additional";

    private static final Bindable<List<PublicRoute>> ROUTE_LIST = Bindable.listOf(PublicRoute.class);

    public PublicRoutes {
        shipped = List.copyOf(shipped);
        additional = additional == null ? List.of() : List.copyOf(additional);
    }

    /**
     * Reads the shipped routes from the packaged properties file and binds the additional routes from the environment.
     *
     * @param packagedProperties the {@code application.properties} packaged with PSAMA
     * @param environment the running application's environment
     * @return the shipped routes followed by any the deployment adds
     * @throws IOException if the packaged properties file cannot be read
     * @throws IllegalStateException if the packaged file ships no routes, or if the environment binds a shipped route list that differs
     *         from the packaged one
     * @throws org.springframework.boot.context.properties.bind.BindException if an entry is malformed; the cause is the
     *         {@link IllegalArgumentException} from {@link PublicRoute}
     */
    public static PublicRoutes load(Resource packagedProperties, Environment environment) throws IOException {
        Binder packaged = new Binder(
            ConfigurationPropertySources.from(new PropertiesPropertySourceLoader().load("packaged public routes", packagedProperties))
        );
        List<PublicRoute> shipped = packaged.bind(SHIPPED_PREFIX, ROUTE_LIST).orElse(List.of());
        if (shipped.isEmpty()) {
            throw new IllegalStateException(packagedProperties.getDescription() + " ships no " + SHIPPED_PREFIX + " entries");
        }

        Binder live = Binder.get(environment);
        List<PublicRoute> seenByEnvironment = live.bind(SHIPPED_PREFIX, ROUTE_LIST).orElse(shipped);
        if (!seenByEnvironment.equals(shipped)) {
            throw new IllegalStateException(
                SHIPPED_PREFIX + " cannot be overridden by the environment; add routes under " + ADDITIONAL_PREFIX + " instead"
            );
        }

        return new PublicRoutes(shipped, live.bind(ADDITIONAL_PREFIX, ROUTE_LIST).orElse(List.of()));
    }

    /**
     * Every route served without a token.
     *
     * @return the shipped routes followed by the additional ones
     */
    public List<PublicRoute> all() {
        List<PublicRoute> all = new ArrayList<>(shipped);
        all.addAll(additional);
        return List.copyOf(all);
    }
}

package edu.harvard.hms.dbmi.avillach.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;

@SpringBootTest
class GatewayApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    /**
     * Guards the {@code UserDetailsServiceAutoConfiguration} exclusion on the application class. Without it Spring Boot registers an
     * {@code InMemoryUserDetailsManager} holding a {@code user} account with a random password and logs that password at startup, even
     * though no filter chain here ever authenticates against it.
     */
    @Test
    void noGeneratedInMemoryUserIsCreated() {
        assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
    }
}

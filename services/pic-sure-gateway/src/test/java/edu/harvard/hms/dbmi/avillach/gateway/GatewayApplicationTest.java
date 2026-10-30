package edu.harvard.hms.dbmi.avillach.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.util.ReflectionTestUtils;

import edu.harvard.hms.dbmi.avillach.gateway.auth.OpenAccessRequests;
import edu.harvard.hms.dbmi.avillach.gateway.filter.OpenAccessFilter;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.PrevalidationThrottleFilter;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitStore;

@SpringBootTest
class GatewayApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    /** Both limiter stages take their buckets from the one {@link RateLimitStore} bean. */
    @Test
    void registersExactlyOneRateLimitStore() {
        assertThat(context.getBeanNamesForType(RateLimitStore.class)).hasSize(1);
    }

    /** The stage-1 throttle and {@link OpenAccessFilter} classify requests with the same {@link OpenAccessRequests} bean. */
    @Test
    void openAccessFilterAndThrottleShareTheOneOpenAccessRequestsBean() {
        assertThat(context.getBeanNamesForType(OpenAccessRequests.class)).hasSize(1);
        OpenAccessRequests bean = context.getBean(OpenAccessRequests.class);

        assertThat(ReflectionTestUtils.getField(registeredFilter("openAccessFilter", OpenAccessFilter.class), "openAccessRequests"))
            .isSameAs(bean);
        assertThat(
            ReflectionTestUtils
                .getField(registeredFilter("prevalidationThrottleFilter", PrevalidationThrottleFilter.class), "openAccessRequests")
        ).isSameAs(bean);
    }

    private <T> T registeredFilter(String beanName, Class<T> type) {
        return type.cast(context.getBean(beanName, FilterRegistrationBean.class).getFilter());
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

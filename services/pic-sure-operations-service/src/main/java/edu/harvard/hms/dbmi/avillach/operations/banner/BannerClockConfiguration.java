package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BannerClockConfiguration {

    @Bean
    @Qualifier("bannerClock")
    Clock bannerClock() {
        return Clock.systemUTC();
    }
}

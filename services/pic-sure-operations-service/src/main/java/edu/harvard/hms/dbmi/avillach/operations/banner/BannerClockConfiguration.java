package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BannerClockConfiguration {

    @Bean
    Clock bannerClock() {
        return Clock.systemUTC();
    }
}

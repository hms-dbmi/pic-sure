package edu.harvard.hms.dbmi.avillach.hpds.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan("edu.harvard.hms.dbmi.avillach.hpds")
public class HpdsApplication {

    public static void main(String[] args) {
        SpringApplication.run(HpdsApplication.class, args);
    }

}
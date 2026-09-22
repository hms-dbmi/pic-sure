package edu.harvard.hms.dbmi.avillach.operations.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Driver;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Guards the BDC and AIM-AHEAD datasource contract. Those environments render {@code operations.env} with
 * {@code SPRING_DATASOURCE_DRIVER_CLASS_NAME=com.amazonaws.secretsmanager.sql.AWSSecretsManagerMySQLDriver} and a
 * {@code jdbc-secretsmanager:mysql://} URL, so the driver fetches credentials from Secrets Manager on each new connection. Nothing else in
 * the build loads that class, so without this test a dropped dependency would only show up as a failed deploy.
 *
 * <p>The driver's static initializer builds a Secrets Manager client, which needs a resolvable AWS region. The test pins {@code aws.region}
 * so it runs without AWS configuration. No request leaves the JVM.
 */
class SecretsManagerDriverTest {

    private static final String DRIVER_CLASS = "com.amazonaws.secretsmanager.sql.AWSSecretsManagerMySQLDriver";

    @BeforeAll
    static void pinAwsRegion() {
        System.setProperty("aws.region", "us-east-1");
    }

    @Test
    void secretsManagerDriverAcceptsTheRenderedUrl() throws Exception {
        Driver driver = (Driver) Class.forName(DRIVER_CLASS).getDeclaredConstructor().newInstance();

        assertThat(driver.acceptsURL("jdbc-secretsmanager:mysql://picsure-db.example:3306/picsure?serverTimezone=UTC")).isTrue();
    }

    @Test
    void secretsManagerDriverRejectsAPlainMysqlUrl() throws Exception {
        Driver driver = (Driver) Class.forName(DRIVER_CLASS).getDeclaredConstructor().newInstance();

        assertThat(driver.acceptsURL("jdbc:mysql://picsure-db.example:3306/picsure")).isFalse();
    }
}

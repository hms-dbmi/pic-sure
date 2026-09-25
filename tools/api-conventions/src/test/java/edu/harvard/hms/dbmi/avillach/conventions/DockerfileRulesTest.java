package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixtures are inline text, not files on disk: a tracked fixture named {@code Dockerfile} would be picked
 * up by the reactor run of R20 itself.
 */
class DockerfileRulesTest {

    private static final String PINNED = "amazoncorretto:25-alpine@sha256:027310590da693629c2cf704d2f87e9359c33ee2f02bcaa777680b2f4b94f4c7";

    @Test
    void r20FlagsAFloatingTagWithItsPathAndLine() {
        String dockerfile = """
            # base image
            FROM amazoncorretto:25-alpine
            COPY target/app.jar /app.jar
            """;

        List<String> violations = DockerfileRules.baseImagesArePinned("services/app/Dockerfile", dockerfile);

        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).startsWith("services/app/Dockerfile:2 FROM amazoncorretto:25-alpine"), violations.get(0));
        assertTrue(violations.get(0).contains("has no @sha256 digest"), violations.get(0));
    }

    @Test
    void r20PassesADigestPinnedImage() {
        assertTrue(DockerfileRules.baseImagesArePinned("Dockerfile", "FROM " + PINNED + "\n").isEmpty());
    }

    @Test
    void r20ExemptsEarlierStagesAndScratch() {
        String dockerfile = """
            FROM mysql:8.4@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb AS empty-db
            RUN true
            FROM empty-db AS initialized-db
            FROM Initialized-DB
            FROM scratch
            """;

        assertTrue(DockerfileRules.baseImagesArePinned("Dockerfile", dockerfile).isEmpty());
    }

    @Test
    void r20DoesNotExemptAStageNameBeforeItIsDeclared() {
        String dockerfile = """
            FROM builder
            FROM maven:3.9-amazoncorretto-25 AS builder
            """;

        List<String> violations = DockerfileRules.baseImagesArePinned("Dockerfile", dockerfile);

        assertEquals(2, violations.size(), violations.toString());
        assertTrue(violations.get(0).startsWith("Dockerfile:1 FROM builder "), violations.get(0));
        assertTrue(violations.get(1).startsWith("Dockerfile:2 FROM maven:3.9-amazoncorretto-25 "), violations.get(1));
    }

    @Test
    void r20SkipsFlagsAndReadsContinuedLines() {
        String dockerfile = """
            FROM --platform=linux/amd64 %s AS build
            from --platform=$BUILDPLATFORM \\
                eclipse-temurin:25-jre
            """.formatted(PINNED);

        List<String> violations = DockerfileRules.baseImagesArePinned("Dockerfile", dockerfile);

        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).startsWith("Dockerfile:2 FROM eclipse-temurin:25-jre "), violations.get(0));
    }

    @Test
    void r20ChecksAnArgSubstitutedImageLikeAnyOther() {
        String dockerfile = """
            ARG BASE=amazoncorretto:25-alpine
            FROM ${BASE}
            """;

        List<String> violations = DockerfileRules.baseImagesArePinned("Dockerfile", dockerfile);

        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).startsWith("Dockerfile:2 FROM ${BASE} "), violations.get(0));
    }

    @Test
    void r20CoversDockerfileAndDockerfileDotAnything() {
        assertTrue(DockerfileRules.isDockerfile("Dockerfile"));
        assertTrue(DockerfileRules.isDockerfile("services/app/Dockerfile"));
        assertTrue(DockerfileRules.isDockerfile("services/app/Dockerfile.dev"));
        assertFalse(DockerfileRules.isDockerfile("services/app/dev.Dockerfile"));
        assertFalse(DockerfileRules.isDockerfile("services/app/Dockerfiles/README.md"));
        assertFalse(DockerfileRules.isDockerfile("services/app/NotADockerfile"));
    }
}

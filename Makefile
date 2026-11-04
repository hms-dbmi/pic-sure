# Entry points for local verification. CI runs the same two Maven commands.
#
# JAVA_HOME is resolved from .sdkmanrc, because a default JDK 11 fails enforce-jdk-25
# on every Maven run in this repository.

SHELL := /bin/bash
ROOT := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
CONVENTIONS := $(ROOT)/tools/api-conventions/pom.xml
JAVA_VERSION := $(shell sed -n 's/^java=//p' $(ROOT)/.sdkmanrc)
SDKMAN_JAVA := $(HOME)/.sdkman/candidates/java/$(JAVA_VERSION)

ifneq ($(strip $(JAVA_VERSION)),)
ifneq ($(wildcard $(SDKMAN_JAVA)),)
export JAVA_HOME := $(SDKMAN_JAVA)
endif
endif

MVN := mvn -B

.PHONY: help build verify verify-all clean check-java

help:
	@echo "build       compile the whole reactor and install it, skipping tests"
	@echo "verify      run the API convention rules only (needs a prior build)"
	@echo "verify-all  full reactor verify, then the convention rules. What CI runs."
	@echo "clean       mvn clean across the reactor"

build: check-java
	$(MVN) -T1C -f $(ROOT)/pom.xml install -DskipTests

verify: check-java
	$(MVN) -f $(CONVENTIONS) test -Dreactor.root=$(ROOT)

verify-all: check-java
	$(MVN) -T1C -f $(ROOT)/pom.xml verify
	$(MVN) -f $(CONVENTIONS) test -Dreactor.root=$(ROOT)

clean: check-java
	$(MVN) -f $(ROOT)/pom.xml clean

check-java:
	@if [ -z "$(strip $(JAVA_VERSION))" ]; then \
	  echo "No java= line found in $(ROOT)/.sdkmanrc, so the sdkman JDK path could not be resolved."; \
	  echo "Fix .sdkmanrc, or export JAVA_HOME yourself to a JDK 25 before running make."; \
	  exit 1; \
	fi
	@if [ ! -d "$(SDKMAN_JAVA)" ] && [ ! -x "$${JAVA_HOME}/bin/javac" ]; then \
	  echo "JDK not found at $(SDKMAN_JAVA), and JAVA_HOME does not point at a usable JDK."; \
	  echo "Install it with: sdk install java $(JAVA_VERSION)"; \
	  echo "Or export JAVA_HOME yourself to a JDK 25 before running make."; \
	  exit 1; \
	fi

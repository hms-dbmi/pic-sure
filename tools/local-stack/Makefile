# Layered local PIC-SURE stack. See README.md.
#
#   make run                              build, load the combined dataset through every lane, start HPDS, compare, print the URLs
#   make compare DATASET=all              the same for mini, 1000g, combined and synth, then one report
#   make jars | split | load | up | down | restart | clean
#
# Variables: DATASET (mini 1000g combined synth all), FLOWS (loader flows, default: those that apply), LAYERS (default hpds),
# PATIENTS CONCEPTS SEED (dataset synth), REBUILD=1 (rebuild the jars). Ports and hosts: .env.defaults, local.env.
# Needs GNU make 3.81 or newer, Java 25, Maven, Python 3, Docker.

SHELL := /bin/bash
.DEFAULT_GOAL := help

-include .env.defaults
-include local.env

DATASET ?= combined
LAYERS ?= hpds
DATASETS_ALL := mini 1000g combined synth
DATASETS_SEL := $(if $(filter all,$(DATASET)),$(DATASETS_ALL),$(DATASET))

REPO := $(abspath $(CURDIR)/../..)
WORK := $(CURDIR)/work
LOADER_JARS_DIR ?= $(REPO)/services/pic-sure-hpds/docker/pic-sure-hpds-etl
HPDS_SERVICE_JAR := $(REPO)/services/pic-sure-hpds/service/target/service-3.0.0.jar
SPLITTER_REPO ?= $(firstword $(wildcard $(REPO)/../pic-sure-hpds-etl $(REPO)/../../../../pic-sure-hpds-etl))
SPLITTER_JAR := $(WORK)/src/hpds-etl/target/hpds-etl.jar
IMAGE_STAMP := $(WORK)/hpds-image.stamp
HPDS_HOST ?= localhost

# what the python helpers read
export WORK_DIR := $(WORK)
export SPLITTER_JAR
export LOADER_JAR_DIR := $(LOADER_JARS_DIR)
export LOADER_XMX SPLIT_MAX_REJECT PATIENTS CONCEPTS SEED HPDS_HOST
export HPDS_LANE1_PORT HPDS_LANE2_PORT HPDS_LANE3_PORT
# what docker compose reads
export DATASET
export STORE_ROOT := $(WORK)
export PUBLIC_HOST BIND_ADDRESS HPDS_IMAGE HPDS_JAVA_OPTS HPDS_ID_BATCH_SIZE

COMPOSE = docker compose -f docker-compose.yml --env-file .env.defaults $(if $(wildcard local.env),--env-file local.env)
CSV_JAR := $(LOADER_JARS_DIR)/CSVLoader-jar-with-dependencies.jar
PIC_JAR := $(LOADER_JARS_DIR)/PICSURELoader-jar-with-dependencies.jar

ifeq ($(REBUILD),1)
.PHONY: $(PIC_JAR) $(HPDS_SERVICE_JAR) $(SPLITTER_JAR)
endif

.PHONY: help jars split load up down restart clean clean-data run compare urls _up-hpds

help:
	@sed -n '1,/^SHELL/p' Makefile | grep '^#' | sed 's/^# \{0,1\}//'

# ---------------------------------------------------------------------------------------------------------------- build

jars: $(CSV_JAR) $(PIC_JAR) $(HPDS_SERVICE_JAR) $(SPLITTER_JAR)

# both loaders, written into services/pic-sure-hpds/docker/pic-sure-hpds-etl (ignored by git)
$(PIC_JAR):
	cd $(REPO) && mvn -B -ntp -q -pl services/pic-sure-hpds/etl -am package -DskipTests

$(CSV_JAR): $(PIC_JAR)

$(HPDS_SERVICE_JAR):
	cd $(REPO) && mvn -B -ntp -q -pl services/pic-sure-hpds/service -am package -DskipTests

# the splitter is built from a pinned commit of pic-sure-hpds-etl, extracted with git archive: that checkout is never written to
$(SPLITTER_JAR):
	@test -n "$(SPLITTER_REPO)" || { echo "pic-sure-hpds-etl not found next to this checkout: set SPLITTER_REPO=<path> (or SPLITTER_JAR=<built hpds-etl.jar>)"; exit 1; }
	rm -rf $(WORK)/src/hpds-etl && mkdir -p $(WORK)/src/hpds-etl
	git -C $(SPLITTER_REPO) archive $(SPLITTER_REF) | tar -x -C $(WORK)/src/hpds-etl
	cd $(WORK)/src/hpds-etl && ./mvnw -q package -DskipTests

# the HPDS image is built from the repository's own Dockerfile with a small context (just the service jar)
$(IMAGE_STAMP): $(HPDS_SERVICE_JAR)
	@rm -rf $(WORK)/hpds-image-ctx && mkdir -p $(WORK)/hpds-image-ctx/service/target
	@cp $(HPDS_SERVICE_JAR) $(WORK)/hpds-image-ctx/service/target/
	docker build -q -f $(REPO)/services/pic-sure-hpds/docker/pic-sure-hpds/Dockerfile -t $(HPDS_IMAGE) $(WORK)/hpds-image-ctx
	@touch $@

# ---------------------------------------------------------------------------------------------------------------- data

# split DATASET=...: generate or link the input and run the splitter (combined, synth)
split:
	@set -e; for d in $(DATASETS_SEL); do python3 lane.py split $$d; done

# load DATASET=... FLOWS="csvloader-split picsureloader-split": the flows run in parallel; each writes work/<dataset>/<flow>/load.json
load:
	@set -e; for d in $(DATASETS_SEL); do \
	  flows="$(if $(filter command line,$(origin FLOWS)),$(FLOWS),$$(python3 lane.py flows $$d))"; \
	  $(MAKE) --no-print-directory -j $$(echo $$flows | wc -w) DATASET=$$d $$(for f in $$flows; do echo _lane-$$f; done); \
	done

_lane-%:
	python3 lane.py load $(DATASET) $*

# ---------------------------------------------------------------------------------------------------------------- stack

# up LAYERS=hpds: start the layers; DATASET picks which loaded stores they serve (a single dataset)
up:
	@test "$(DATASET)" != all || { echo "up serves one dataset: DATASET=mini|1000g|combined|synth"; exit 2; }
	@set -e; for l in $(LAYERS); do $(MAKE) --no-print-directory _up-$$l; done

_up-hpds: $(IMAGE_STAMP)
	$(COMPOSE) --profile '*' down --remove-orphans
	@set -e; svc=""; \
	for pair in lane1:picsureloader-combined lane2:csvloader-split lane3:picsureloader-split; do \
	  lane=$${pair%%:*}; flow=$${pair#*:}; \
	  if [ -d "$(WORK)/$(DATASET)/$$flow/store" ] && [ -n "$$(ls "$(WORK)/$(DATASET)/$$flow/store" | grep -v encryption_key)" ]; then svc="$$svc hpds-$$lane"; \
	  else echo "hpds-$$lane not started: no store for $(DATASET)/$$flow"; fi; \
	done; \
	test -n "$$svc" || { echo "no store to serve: run make load DATASET=$(DATASET)"; exit 1; }; \
	$(COMPOSE) --profile hpds up -d $$svc; \
	for s in $$svc; do \
	  n=$$(echo $$s | sed 's/hpds-lane//'); port=$$(eval echo \$$HPDS_LANE$${n}_PORT); \
	  printf "waiting for %s on port %s " $$s $$port; \
	  for i in $$(seq 1 90); do curl -fs "http://$(HPDS_HOST):$$port/actuator/health" | grep -q UP && break; printf .; sleep 2; done; echo; \
	  curl -fs "http://$(HPDS_HOST):$$port/actuator/health" | grep -q UP || { echo "$$s is not healthy: $(COMPOSE) logs $$s"; exit 1; }; \
	done

down:
	$(COMPOSE) --profile '*' down --remove-orphans

restart: down up

# ---------------------------------------------------------------------------------------------------------------- compare

# compare DATASET=mini|1000g|combined|synth|all: jars, split, load, up and compare.py for each dataset, then the report
compare: jars
	@set -e; for d in $(DATASETS_SEL); do \
	  echo "=== $$d"; \
	  $(MAKE) --no-print-directory split DATASET=$$d; \
	  $(MAKE) --no-print-directory load DATASET=$$d; \
	  $(MAKE) --no-print-directory up DATASET=$$d; \
	  python3 compare.py run $$d; \
	done; \
	python3 compare.py report $(DATASETS_SEL)

run:
	@$(MAKE) --no-print-directory compare DATASET=combined
	@$(MAKE) --no-print-directory urls

urls:
	@echo; echo "HPDS (POST /PIC-SURE/v3/query/sync, GET /actuator/health):"; \
	for pair in 1:picsureloader-combined 2:csvloader-split 3:picsureloader-split; do \
	  n=$${pair%%:*}; flow=$${pair#*:}; port=$$(eval echo \$$HPDS_LANE$${n}_PORT); \
	  if docker ps --format '{{.Names}}' | grep -qx picsure-local-hpds-lane$$n; then echo "  lane$$n $$flow: http://$(PUBLIC_HOST):$$port"; fi; \
	done; \
	echo "Report: $(WORK)/report/compare.md"

# ---------------------------------------------------------------------------------------------------------------- clean

clean: down
	rm -rf $(CURDIR)/work

# everything loaded or compared, but not the splitter build or the HPDS image
clean-data: down
	@for d in mini 1000g combined synth report; do rm -rf "$(CURDIR)/work/$$d"; done

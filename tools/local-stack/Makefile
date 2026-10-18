# Layered local PIC-SURE stack. See README.md.
#
#   make run                              build, load the combined dataset through every lane, start HPDS, compare, print the URLs
#   make run LAYERS=hpds,api              the same, plus the PIC-SURE API (query-service, operations-service, MySQL) in front of one lane's
#                                         HPDS, and the same queries sent through it (compare.py --via=api)
#   make compare DATASET=all              the same for mini, 1000g, combined, synth and edge, then one report
#   make jars | split | load | up | down | restart | clean
#
# Variables: DATASET (mini 1000g combined synth edge all; all is the five), FLOWS (loader flows, default: those that apply; a subset is a debug run and
# the result reads PASS (partial: ... not run)), LANE1 (auto|require|off, default auto: lane 1 runs when the PICSURELoader jar can read
# a combined file, else it is skipped; require makes a skip a failure), LAYERS (comma or space separated, default hpds; hpds,api), STORE
# (layer api: the lane whose HPDS the API serves, lane1|lane2|lane3, default lane3), PATIENTS CONCEPTS SEED (dataset synth),
# REBUILD=1 (rebuild the jars; with make up, the HPDS service jar and the image too). Ports and hosts: .env.defaults, local.env.
# Needs GNU make 3.81 or newer, Java 25, Maven, Python 3, Docker. The checkout path must not contain spaces.

ifneq ($(words $(CURDIR)),1)
$(error the path of this directory contains spaces ($(CURDIR)): move the checkout, make cannot handle it)
endif

SHELL := /bin/bash
.DEFAULT_GOAL := help

-include .env.defaults
-include local.env

DATASET ?= combined
LAYERS ?= hpds
STORE ?= lane3
comma := ,
LAYER_LIST := $(subst $(comma), ,$(LAYERS))
HAS_API := $(filter api,$(LAYER_LIST))
LANE1 ?= auto
DATASETS_ALL := mini 1000g combined synth edge
DATASETS_SEL := $(if $(filter all,$(DATASET)),$(DATASETS_ALL),$(DATASET))

REPO := $(abspath $(CURDIR)/../..)
WORK := $(CURDIR)/work
LOADER_JARS_DIR ?= $(REPO)/services/pic-sure-hpds/docker/pic-sure-hpds-etl
HPDS_SERVICE_JAR := $(REPO)/services/pic-sure-hpds/service/target/service-3.0.0.jar
SPLITTER_REPO ?= $(firstword $(wildcard $(REPO)/../pic-sure-hpds-etl $(REPO)/../../../../pic-sure-hpds-etl))
SPLITTER_JAR := $(WORK)/src/hpds-etl/target/hpds-etl.jar
IMAGE_STAMP := $(WORK)/hpds-image.stamp
HPDS_HOST ?= localhost
API_HOST ?= $(HPDS_HOST)
API_IMAGE_STAMP := $(WORK)/api-images.stamp
QUERY_JAR := $(REPO)/services/pic-sure-hpds-query-service/target/pic-sure-hpds-query-service-3.0.0.jar
OPS_JAR := $(REPO)/services/pic-sure-operations-service/target/pic-sure-operations-service-3.0.0.jar
# the maven reactor builds the loaders and the HPDS service, and with layer api the two API services too
MVN_MODULES := services/pic-sure-hpds/etl,services/pic-sure-hpds/service$(if $(HAS_API),$(comma)services/pic-sure-hpds-query-service$(comma)services/pic-sure-operations-service)
API_JARS := $(if $(HAS_API),$(QUERY_JAR) $(OPS_JAR))

# what the python helpers read
export WORK_DIR := $(WORK)
export SPLITTER_JAR
export LOADER_JAR_DIR := $(LOADER_JARS_DIR)
export LOADER_XMX SPLIT_MAX_REJECT PATIENTS CONCEPTS SEED HPDS_HOST
export HPDS_LANE1_PORT HPDS_LANE2_PORT HPDS_LANE3_PORT LANE1
export API_HOST API_QUERY_PORT API_OPERATIONS_PORT API_USER_ID
# what docker compose reads
export DATASET
export STORE_ROOT := $(WORK)
export PUBLIC_HOST BIND_ADDRESS HPDS_IMAGE HPDS_JAVA_OPTS HPDS_ID_BATCH_SIZE
export STORE
export API_FLYWAY_SQL := $(REPO)/services/pic-sure-operations-service/db/sql
export API_QUERY_PORT API_OPERATIONS_PORT API_MYSQL_PORT API_QUERY_IMAGE API_OPERATIONS_IMAGE API_MYSQL_IMAGE API_FLYWAY_IMAGE API_JAVA_OPTS
export API_DB_ROOT_PASSWORD API_DB_PASSWORD API_INTERNAL_TOKEN

COMPOSE = docker compose -f docker-compose.yml --env-file .env.defaults $(if $(wildcard local.env),--env-file local.env)
CSV_JAR := $(LOADER_JARS_DIR)/CSVLoader-jar-with-dependencies.jar
PIC_JAR := $(LOADER_JARS_DIR)/PICSURELoader-jar-with-dependencies.jar

# every recipe that removes something runs this first (lane.py guard-work): WORK (it can be set on the command line) must resolve to
# .../tools/local-stack/work, be that directory of this checkout and not be a symlink; otherwise it says why and the recipe stops
GUARD_WORK = python3 lane.py guard-work "$(WORK)"

ifeq ($(REBUILD),1)
.PHONY: $(SPLITTER_JAR) $(IMAGE_STAMP) $(API_IMAGE_STAMP)
endif

.PHONY: help jars _mvn split load up down restart clean clean-data run compare urls _up-hpds _up-api _rebuild-service

help:
	@sed -n '1,/^SHELL/p' Makefile | grep '^#' | sed 's/^# \{0,1\}//'

# ---------------------------------------------------------------------------------------------------------------- build

# the splitter is built only when a selected dataset needs splitting (combined, synth)
jars: FORCE := $(REBUILD)
jars: _mvn $(if $(filter combined synth,$(DATASETS_SEL)),$(SPLITTER_JAR))

# both loaders (written into services/pic-sure-hpds/docker/pic-sure-hpds-etl, ignored by git) and the HPDS service, and with layer api the
# query-service and the operations-service: one maven reactor
_mvn:
	@missing=0; for j in "$(CSV_JAR)" "$(PIC_JAR)" "$(HPDS_SERVICE_JAR)" $(foreach j,$(API_JARS),"$(j)"); do [ -f "$$j" ] || missing=1; done; \
	if [ "$(FORCE)" = 1 ] || [ $$missing = 1 ]; then \
	  echo "mvn package: $(MVN_MODULES)"; \
	  cd "$(REPO)" && mvn -B -ntp -q -pl $(MVN_MODULES) -am package -DskipTests; \
	fi

$(CSV_JAR) $(PIC_JAR) $(HPDS_SERVICE_JAR) $(API_JARS):
	@$(MAKE) --no-print-directory _mvn
	@test -f "$@" || { echo "$@ was not built"; exit 1; }

# the splitter is built from a pinned commit of pic-sure-hpds-etl, extracted with git archive: that checkout is never written to
$(SPLITTER_JAR):
	@test -n "$(SPLITTER_REPO)" || { echo "pic-sure-hpds-etl not found next to this checkout: set SPLITTER_REPO=<path> (or SPLITTER_JAR=<built hpds-etl.jar>)"; exit 1; }
	@test -n "$(SPLITTER_REF)" || { echo "SPLITTER_REF is empty (.env.defaults)"; exit 1; }
	@$(GUARD_WORK)
	rm -rf -- "$(WORK)/src/hpds-etl" && mkdir -p "$(WORK)/src/hpds-etl"
	set -o pipefail; git -C "$(SPLITTER_REPO)" archive "$(SPLITTER_REF)" | tar -x -C "$(WORK)/src/hpds-etl"
	cd "$(WORK)/src/hpds-etl" && ./mvnw -q package -DskipTests

# the HPDS image is built from the repository's own Dockerfile with a small context (just the service jar)
$(IMAGE_STAMP): $(HPDS_SERVICE_JAR)
	@$(GUARD_WORK)
	@rm -rf -- "$(WORK)/hpds-image-ctx" && mkdir -p "$(WORK)/hpds-image-ctx/service/target"
	@cp "$(HPDS_SERVICE_JAR)" "$(WORK)/hpds-image-ctx/service/target/"
	docker build -q -f "$(REPO)/services/pic-sure-hpds/docker/pic-sure-hpds/Dockerfile" -t "$(HPDS_IMAGE)" "$(WORK)/hpds-image-ctx"
	@touch "$@"

# the API images are built from the services' own Dockerfiles, each with a small context (just its jar)
$(API_IMAGE_STAMP): $(API_JARS)
	@$(GUARD_WORK)
	@rm -rf -- "$(WORK)/api-image-ctx" && mkdir -p "$(WORK)/api-image-ctx/query/target" "$(WORK)/api-image-ctx/operations/target"
	@cp "$(QUERY_JAR)" "$(WORK)/api-image-ctx/query/target/" && cp "$(OPS_JAR)" "$(WORK)/api-image-ctx/operations/target/"
	docker build -q -f "$(REPO)/services/pic-sure-hpds-query-service/Dockerfile" -t "$(API_QUERY_IMAGE)" "$(WORK)/api-image-ctx/query"
	docker build -q -f "$(REPO)/services/pic-sure-operations-service/Dockerfile" -t "$(API_OPERATIONS_IMAGE)" "$(WORK)/api-image-ctx/operations"
	@touch "$@"

# ---------------------------------------------------------------------------------------------------------------- data

# split DATASET=...: generate or link the input and run the splitter (combined, synth)
split:
	@set -e; for d in $(DATASETS_SEL); do python3 lane.py split $$d; done

# load DATASET=... FLOWS="csvloader-split picsureloader-split": the flows run in parallel; each writes work/<dataset>/<flow>/load.json
load:
	@set -e; for d in $(DATASETS_SEL); do \
	  flows="$(if $(filter command line,$(origin FLOWS)),$(FLOWS),$$(python3 lane.py flows $$d))"; \
	  python3 lane.py request $$d $$flows; \
	  $(MAKE) --no-print-directory -j $$(echo $$flows | wc -w) DATASET=$$d $$(for f in $$flows; do echo _lane-$$f; done); \
	done

_lane-%:
	python3 lane.py load $(DATASET) $*

# ---------------------------------------------------------------------------------------------------------------- stack

# up LAYERS=hpds,api: start the layers (always in the order hpds, api, then any other); DATASET picks which loaded stores they serve (a
# single dataset); STORE picks the lane whose HPDS the api layer serves
up:
	@test "$(DATASET)" != all || { echo "up serves one dataset: DATASET=mini|1000g|combined|synth|edge"; exit 2; }
	@set -e; for l in hpds api $(filter-out hpds api,$(LAYER_LIST)); do \
	  case " $(LAYER_LIST) " in *" $$l "*) $(MAKE) --no-print-directory _up-$$l;; esac; \
	done

# REBUILD=1 rebuilds the service jar and the image here (compare passes REBUILD= on: its jars step has done that once)
_up-hpds: $(if $(filter 1,$(REBUILD)),_rebuild-service) $(IMAGE_STAMP)
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

# the API in front of the HPDS of lane $(STORE) (the hpds layer is up before this runs; it recreates the whole stack, so this layer is
# always started after it). Waits for the operations-service and the query-service to report UP (compose starts mysql, flyway and them in order).
_up-api: $(if $(filter 1,$(REBUILD)),_rebuild-api) $(API_IMAGE_STAMP)
	@case "$(STORE)" in lane1|lane2|lane3) ;; *) echo "STORE=$(STORE): expected lane1, lane2 or lane3"; exit 2;; esac
	@docker ps --format '{{.Names}}' | grep -qx picsure-local-hpds-$(STORE) || { echo "layer api needs the HPDS of $(STORE) running (layer hpds; $(STORE) has no store for $(DATASET)?)"; exit 1; }
	$(COMPOSE) --profile api up -d
	@set -e; for pair in operations:$(API_OPERATIONS_PORT):operations/actuator/health query:$(API_QUERY_PORT):actuator/health; do \
	  name=$${pair%%:*}; rest=$${pair#*:}; port=$${rest%%:*}; path=$${rest#*:}; \
	  printf "waiting for api-%s on port %s " $$name $$port; \
	  for i in $$(seq 1 90); do curl -fs "http://$(API_HOST):$$port/$$path" | grep -q UP && break; printf .; sleep 2; done; echo; \
	  curl -fs "http://$(API_HOST):$$port/$$path" | grep -q UP || { echo "api-$$name is not healthy: $(COMPOSE) logs api-$$name"; exit 1; }; \
	done

_rebuild-api:
	@$(MAKE) --no-print-directory _mvn FORCE=1

_rebuild-service:
	@$(MAKE) --no-print-directory _mvn FORCE=1

down:
	$(COMPOSE) --profile '*' down --remove-orphans

restart: down up

# ---------------------------------------------------------------------------------------------------------------- compare

# compare DATASET=mini|1000g|combined|synth|all: jars, split, load, up and compare.py for each dataset, then the report
compare: jars
	@set -e; for d in $(DATASETS_SEL); do \
	  echo "=== $$d"; \
	  $(MAKE) --no-print-directory REBUILD= split DATASET=$$d; \
	  $(MAKE) --no-print-directory REBUILD= load DATASET=$$d; \
	  $(MAKE) --no-print-directory REBUILD= up DATASET=$$d; \
	  python3 compare.py run $$d $(if $(HAS_API),--via=api --store $(STORE)); \
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
	if docker ps --format '{{.Names}}' | grep -qx picsure-local-query; then \
	  echo "API (store $(STORE)): query-service http://$(PUBLIC_HOST):$(API_QUERY_PORT)  POST /hpds/auth/v3/query/sync  (header X-User-Id)"; \
	  echo "     operations-service http://$(PUBLIC_HOST):$(API_OPERATIONS_PORT)/operations  mysql 127.0.0.1:$(API_MYSQL_PORT) (db picsure, user picsure)"; \
	fi; \
	echo "Report: $(WORK)/report/compare.md"

# ---------------------------------------------------------------------------------------------------------------- clean

# the guard runs before anything, `down` included
clean:
	@$(GUARD_WORK)
	@$(MAKE) --no-print-directory down
	rm -rf -- "$(WORK)"

# everything loaded or compared, but not the splitter build or the HPDS image
clean-data:
	@$(GUARD_WORK)
	@$(MAKE) --no-print-directory down
	@for d in $(DATASETS_ALL) report; do rm -rf -- "$(WORK)/$$d"; done

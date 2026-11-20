# Layered local PIC-SURE stack. See README.md.
#
#   make run                              build, load the combined dataset through every lane, start HPDS, compare, print the URLs
#   make run LAYERS=hpds,api              the same, plus the PIC-SURE API (query-service, operations-service, MySQL) in front of one lane's
#                                         HPDS, and the same queries sent through it (compare.py --via=api)
#   make run LAYERS=hpds,api,dict         ... and the dictionary service (Postgres + picsure-dictionary) filled with a synthetic dictionary
#   make qa                               the headless QA battery (qa.py) against the stack that is up; exit 0 PASS, 1 FAIL, 2 inconclusive
#   make up DATASET=dir HPDS_DIR=<partition root> KEY_FILE=<key file> [LAYERS=hpds,api,dict DICT_DUMP=<dictionary_db_*.tar>]
#                                         serve an existing partition set, no loader runs
#   make compare DATASET=all              the same for mini, 1000g, combined, synth and edge, then one report
#   make qa-scenarios                     the three QA scenarios on the combined dataset: phenotype only, phenotype + genomic (BDC genomic settings),
#                                         and the same with an empty genomic directory (which must FAIL with its cause)
#   make jars | split | load | up | down | restart | clean | test
#
# Variables: DATASET (mini 1000g combined synth edge all; all is the five), FLOWS (loader flows, default: those that apply; a subset is a debug run and
# the result reads PASS (partial: ... not run)), LANE1 (auto|require|off, default auto: lane 1 runs when the PICSURELoader jar can read
# a combined file, else it is skipped; require makes a skip a failure), LAYERS (comma or space separated, default hpds; hpds, api, dict), STORE
# (layer api: the lane whose HPDS the API serves, lane1|lane2|lane3, default lane3), PATIENTS CONCEPTS SEED (dataset synth),
# REBUILD=1 (rebuild the jars; with make up, the HPDS service jar and the image too). Ports and hosts: .env.defaults, local.env.
# Service code: SERVICES_REF (default csvloader-partition-names) is the commit whose HPDS, query-service, operations-service and dictionary the
# stack serves; HPDS_REF, QUERY_REF, DICT_REF override it per service and LOADER_REF the CSVLoader of lane 2. A ref is a local branch,
# origin/<ref>, upstream/<ref> or a sha. The commit is extracted with git archive into work/src/services-<sha>/ and built there.
# Genomic data of the authorized HPDS (DATASET=dir): GENOMIC=fixture (default: a small genomic store built from public VCFs, laid out
# all/<group>/<chromosome>/), empty (the genomic directory with no dataset folder: HPDS cannot start, QA must FAIL) or off (phenotype only, no genomic
# processor). GENOMIC_SPLIT_DIR (a directory of <partition>-allConcepts.csv) maps the fixture's samples to patients of the phenotype data;
# GENOMIC_PREFLIGHT=off starts HPDS on a genomic directory that fails the pre-flight (it then dies at start, which qa reports). SCENARIO_DATASET
# (combined) is the dataset of qa-scenarios (a well-formed one with patients in two consents; mini has a deliberately malformed partition).
# Dataset dir: HPDS_DIR (partition root), KEY_FILE. Open-mode HPDS: OPEN (1 = start it, default), OPEN_COLUMNMETA_DIR (per-partition
# columnMeta.javabin overrides), STIGVARS (a file of paths removed from the open columnMeta of a synthetic dataset). Layer dict: DICT_DUMP
# (a dictionary_db_*.tar to restore instead of the synthetic dictionary). QA: QA_MANIFEST, QA_REQUIRE, QA_COLUMNMETA_ROOT, QA_ARGS (see README).
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
ifneq ($(HAS_API),)
ifeq ($(filter hpds,$(LAYER_LIST)),)
$(error LAYERS=$(LAYERS): the api layer needs the hpds layer (it queries a lane's HPDS); use LAYERS=hpds,api)
endif
endif
HAS_DICT := $(filter dict,$(LAYER_LIST))
LANE1 ?= auto
DATASETS_ALL := mini 1000g combined synth edge
DATASETS_SEL := $(if $(filter all,$(DATASET)),$(DATASETS_ALL),$(DATASET))

# DATASET=dir serves an existing partition set (HPDS_DIR, KEY_FILE) through one HPDS, container hpds-dir, and the API's STORE is that HPDS
HPDS_DIR ?=
KEY_FILE ?=
ifeq ($(DATASET),dir)
override STORE := dir
endif
# real data stays on this machine: a stack that serves a real partition set or restores a real dictionary dump binds loopback only
ifneq ($(filter dir,$(DATASET))$(DICT_DUMP),)
ifneq ($(BIND_ADDRESS),127.0.0.1)
$(error DATASET=dir and DICT_DUMP serve controlled-access data: BIND_ADDRESS must be 127.0.0.1 (it is "$(BIND_ADDRESS)"), see README, "Real data stays in the boundary")
endif
endif
OPEN ?= 1
OPEN_COLUMNMETA_DIR ?=
# synthetic datasets: the listed paths are removed from the open columnMeta of every partition (the open copy of a stigmatized variable list)
STIGVARS ?= $(if $(filter dir,$(DATASET)),,$(CURDIR)/fixtures/qa/stigvars.txt)
DICT_DUMP ?=
QA_MANIFEST ?=
QA_REQUIRE ?=
QA_COLUMNMETA_ROOT ?=
QA_ARGS ?=
QA_REPORT ?= $(CURDIR)/work/report/qa

# genomic data of the authorized HPDS (hpds-dir only): the BDC settings of profile bdc-auth-dev, set here explicitly (the profile also names a dev
# dictionary host and an S3 bucket that do not exist locally)
GENOMIC ?= fixture
GENOMIC_SPLIT_DIR ?=
GENOMIC_PREFLIGHT ?= check
SCENARIO_DATASET ?= combined
ifeq ($(filter $(GENOMIC),fixture empty off),)
$(error GENOMIC=$(GENOMIC): one of fixture, empty, off)
endif
GENOMIC_ON := $(if $(filter dir,$(DATASET)),$(filter fixture empty,$(GENOMIC)))
GENOMIC_WORK := $(CURDIR)/work/genomic/$(GENOMIC)
HPDS_GENOMIC_OPTS := $(if $(GENOMIC_ON),-Dhpds.genomicProcessor.impl=localPatientDistributed -DHPDS_GENOMIC_DATA_DIRECTORY=/opt/local/hpds/all/)
GENOMIC_ROOT := $(if $(GENOMIC_ON),$(GENOMIC_WORK)/all)
# the genomic mount is read-write (HPDS writes its variant index caches into it) only when it holds genomic data
GENOMIC_ACCESS := $(if $(GENOMIC_ON),rw,ro)
export HPDS_GENOMIC_OPTS GENOMIC_ROOT GENOMIC_ACCESS

REPO := $(abspath $(CURDIR)/../..)
WORK := $(CURDIR)/work
# PICSURELoader (lanes 1 and 3) is built from this checkout: it is not on the deployed ref, and it only produces synthetic input
LOADER_JARS_DIR ?= $(REPO)/services/pic-sure-hpds/docker/pic-sure-hpds-etl
PIC_JAR := $(LOADER_JARS_DIR)/PICSURELoader-jar-with-dependencies.jar
SPLITTER_REPO ?= $(firstword $(wildcard $(REPO)/../pic-sure-hpds-etl $(REPO)/../../../../pic-sure-hpds-etl))
SPLITTER_JAR := $(WORK)/src/hpds-etl/target/hpds-etl.jar
HPDS_HOST ?= localhost
API_HOST ?= $(HPDS_HOST)
# the service code of the stack: one commit (SERVICES_REF), per service overridable; services_ref.py resolves and builds it, see README
SERVICES_REF ?= csvloader-partition-names
SERVICE_NAMES := $(strip hpds $(if $(HAS_API),query operations) $(if $(HAS_DICT),dict) $(if $(filter-out dir,$(DATASET)),loader))
# label of an image the stack built: the sha of the services it holds
LABEL_SHA := local-stack.services-sha
LABEL_REF := local-stack.services-ref
LABEL_VIA := local-stack.services-via
# a shell test: image $(1) carries sha $(2) (and this is not a REBUILD)
image_current = [ "$(REBUILD)" != 1 ] && [ "$$(docker image inspect -f '{{ index .Config.Labels "$(LABEL_SHA)" }}' "$(1)" 2>/dev/null)" = "$(2)" ]

# what the python helpers read
export WORK_DIR := $(WORK)
export SERVICES_REF HPDS_REF QUERY_REF DICT_REF LOADER_REF
export STACK_SERVICES := $(filter hpds query dict,$(SERVICE_NAMES))
export SPLITTER_JAR
export LOADER_JAR_DIR := $(LOADER_JARS_DIR)
export LOADER_XMX SPLIT_MAX_REJECT PATIENTS CONCEPTS SEED HPDS_HOST
export HPDS_LANE1_PORT HPDS_LANE2_PORT HPDS_LANE3_PORT LANE1 HPDS_DIR_PORT HPDS_OPEN_PORT DICT_API_PORT
export API_HOST API_QUERY_PORT API_OPERATIONS_PORT API_USER_ID
# what docker compose reads
export DATASET
export STORE_ROOT := $(WORK)
export PUBLIC_HOST BIND_ADDRESS HPDS_IMAGE HPDS_JAVA_OPTS HPDS_ID_BATCH_SIZE
export STORE
export API_QUERY_PORT API_OPERATIONS_PORT API_MYSQL_PORT API_QUERY_IMAGE API_OPERATIONS_IMAGE API_MYSQL_IMAGE API_FLYWAY_IMAGE API_JAVA_OPTS
export API_DB_ROOT_PASSWORD API_DB_PASSWORD API_INTERNAL_TOKEN

# which store the hpds layer serves, as the compose file mounts it: the partition root (SERVE_ROOT), the key file and, for the open-mode
# HPDS, OPEN_ROOT (the same root, or a copy with the open columnMeta of each partition)
STORE_FLOW := $(if $(filter lane1,$(STORE)),picsureloader-combined,$(if $(filter lane2,$(STORE)),csvloader-split,picsureloader-split))
ifeq ($(DATASET),dir)
SERVE_ROOT := $(if $(HPDS_DIR),$(abspath $(HPDS_DIR)))
SERVE_KEY := $(if $(KEY_FILE),$(abspath $(KEY_FILE)))
else
SERVE_ROOT := $(WORK)/$(DATASET)/$(STORE_FLOW)/store
SERVE_KEY := $(SERVE_ROOT)/encryption_key
endif
GEN_OVERRIDES := $(if $(and $(filter-out dir,$(DATASET)),$(STIGVARS)),$(WORK)/$(DATASET)/open-columnmeta)
OPEN_OVERRIDES := $(if $(OPEN_COLUMNMETA_DIR),$(abspath $(OPEN_COLUMNMETA_DIR)),$(GEN_OVERRIDES))
OPEN_ROOT := $(if $(OPEN_OVERRIDES),$(WORK)/$(DATASET)/open-root,$(SERVE_ROOT))
API_OPEN_HOST := hpds-$(if $(filter 1,$(OPEN)),open,$(STORE))
export HPDS_DIR_PORT HPDS_OPEN_PORT SERVE_ROOT SERVE_KEY OPEN_ROOT API_OPEN_HOST
# layer dict
DICT_WORK := $(WORK)/$(DATASET)/dict
DICT_LOAD_MODE := $(if $(DICT_DUMP),dump,sql)
DICT_DUMP_FILE := $(if $(DICT_DUMP),$(abspath $(DICT_DUMP)),$(CURDIR)/dict/load.sh)
export DICT_WORK DICT_LOAD_MODE DICT_DUMP_FILE DICT_API_PORT DICT_DB_PORT DICT_IMAGE DICT_POSTGRES_IMAGE DICT_DB_PASSWORD DICT_JAVA_OPTS

COMPOSE = docker compose -f docker-compose.yml --env-file .env.defaults $(if $(wildcard local.env),--env-file local.env)

# every recipe that removes something runs this first (lane.py guard-work): WORK (it can be set on the command line) must resolve to
# .../tools/local-stack/work, be that directory of this checkout and not be a symlink; otherwise it says why and the recipe stops
GUARD_WORK = python3 lane.py guard-work "$(WORK)"

ifeq ($(REBUILD),1)
.PHONY: $(SPLITTER_JAR)
endif

.PHONY: help jars _jars _prepare _checkout-loaders split load up down restart clean clean-data run compare urls qa qa-scenarios test _up-hpds _up-api _up-dict _image-hpds _image-api _image-dict _hpds-lanes _hpds-dir _genomic-prepare _hpds-open _hpds-open-start _open-columnmeta

help:
	@sed -n '1,/^SHELL/p' Makefile | grep '^#' | sed 's/^# \{0,1\}//'

# ---------------------------------------------------------------------------------------------------------------- build

# the splitter is built only when a selected dataset needs splitting (combined, synth)
jars: _jars $(if $(filter combined synth,$(DATASETS_SEL)),$(SPLITTER_JAR))

_jars:
	@$(MAKE) --no-print-directory _prepare SVC="$(SERVICE_NAMES)"
	@$(if $(filter-out dir,$(DATASET)),$(MAKE) --no-print-directory _checkout-loaders)

# SVC=<names>: resolve the refs of those services (hpds query operations dict loader), extract each commit into work/src/services-<sha>/ and build the
# jars there; a commit that is already built is reused, REBUILD=1 builds again. Records what it resolved in work/services.json.
_prepare:
	@$(GUARD_WORK)
	@python3 services_ref.py prepare $(if $(filter 1,$(REBUILD)),--force) $(SVC)

# PICSURELoader, for lanes 1 and 3, from this checkout (it only produces the synthetic input; the HPDS and the CSVLoader come from the refs)
_checkout-loaders:
	@if [ "$(REBUILD)" = 1 ] || [ ! -f "$(PIC_JAR)" ]; then \
	  echo "mvn package (PICSURELoader, from this checkout)"; \
	  cd "$(REPO)" && mvn -B -ntp -q -pl services/pic-sure-hpds/etl -am package -DskipTests; \
	fi
	@test -f "$(PIC_JAR)" || { echo "$(PIC_JAR) was not built"; exit 1; }

# the splitter is built from a pinned commit of pic-sure-hpds-etl, extracted with git archive: that checkout is never written to
$(SPLITTER_JAR):
	@test -n "$(SPLITTER_REPO)" || { echo "pic-sure-hpds-etl not found next to this checkout: set SPLITTER_REPO=<path> (or SPLITTER_JAR=<built hpds-etl.jar>)"; exit 1; }
	@test -n "$(SPLITTER_REF)" || { echo "SPLITTER_REF is empty (.env.defaults)"; exit 1; }
	@$(GUARD_WORK)
	rm -rf -- "$(WORK)/src/hpds-etl" && mkdir -p "$(WORK)/src/hpds-etl"
	set -o pipefail; git -C "$(SPLITTER_REPO)" archive "$(SPLITTER_REF)" | tar -x -C "$(WORK)/src/hpds-etl"
	cd "$(WORK)/src/hpds-etl" && ./mvnw -q package -DskipTests

# The images are built from the services' own Dockerfiles (those of the extracted commit), each with a small context (just its jar), and
# labelled with the commit. An image whose label is the commit of the jar is not built again.
_image-hpds:
	@$(MAKE) --no-print-directory _prepare SVC=hpds
	@sha=$$(python3 services_ref.py get hpds sha); ref=$$(python3 services_ref.py get hpds ref); via=$$(python3 services_ref.py get hpds matched); src=$$(python3 services_ref.py get hpds src); \
	if $(call image_current,$(HPDS_IMAGE),$$sha); then echo "$(HPDS_IMAGE): services $$sha"; else \
	  rm -rf -- "$(WORK)/hpds-image-ctx" && mkdir -p "$(WORK)/hpds-image-ctx/service/target" && \
	  cp "$$(python3 services_ref.py get hpds jar)" "$(WORK)/hpds-image-ctx/service/target/" && \
	  docker build -q --label "$(LABEL_SHA)=$$sha" --label "$(LABEL_REF)=$$ref" --label "$(LABEL_VIA)=$$via" -f "$$src/services/pic-sure-hpds/docker/pic-sure-hpds/Dockerfile" -t "$(HPDS_IMAGE)" "$(WORK)/hpds-image-ctx"; \
	fi

_image-api:
	@$(MAKE) --no-print-directory _prepare SVC="query operations"
	@set -e; for svc in query\|$(API_QUERY_IMAGE)\|pic-sure-hpds-query-service operations\|$(API_OPERATIONS_IMAGE)\|pic-sure-operations-service; do \
	  IFS='|' read -r name image dir <<< "$$svc"; \
	  sha=$$(python3 services_ref.py get $$name sha); ref=$$(python3 services_ref.py get $$name ref); via=$$(python3 services_ref.py get $$name matched); src=$$(python3 services_ref.py get $$name src); \
	  if $(call image_current,$$image,$$sha); then echo "$$image: services $$sha"; else \
	    rm -rf -- "$(WORK)/api-image-ctx/$$name" && mkdir -p "$(WORK)/api-image-ctx/$$name/target" && \
	    cp "$$(python3 services_ref.py get $$name jar)" "$(WORK)/api-image-ctx/$$name/target/" && \
	    docker build -q --label "$(LABEL_SHA)=$$sha" --label "$(LABEL_REF)=$$ref" --label "$(LABEL_VIA)=$$via" -f "$$src/services/$$dir/Dockerfile" -t "$$image" "$(WORK)/api-image-ctx/$$name"; \
	  fi; \
	done

_image-dict:
	@$(MAKE) --no-print-directory _prepare SVC=dict
	@sha=$$(python3 services_ref.py get dict sha); ref=$$(python3 services_ref.py get dict ref); via=$$(python3 services_ref.py get dict matched); src=$$(python3 services_ref.py get dict src); \
	if $(call image_current,$(DICT_IMAGE),$$sha); then echo "$(DICT_IMAGE): services $$sha"; else \
	  rm -rf -- "$(WORK)/dict-image-ctx" && mkdir -p "$(WORK)/dict-image-ctx/target" && \
	  cp "$$(python3 services_ref.py get dict jar)" "$(WORK)/dict-image-ctx/target/" && \
	  docker build -q --label "$(LABEL_SHA)=$$sha" --label "$(LABEL_REF)=$$ref" --label "$(LABEL_VIA)=$$via" -f "$$src/services/picsure-dictionary/Dockerfile" -t "$(DICT_IMAGE)" "$(WORK)/dict-image-ctx"; \
	fi

# ---------------------------------------------------------------------------------------------------------------- data

# split DATASET=...: generate or link the input and run the splitter (combined, synth)
split:
	@set -e; for d in $(DATASETS_SEL); do python3 lane.py split $$d; done

# load DATASET=... FLOWS="csvloader-split picsureloader-split": the flows run in parallel; each writes work/<dataset>/<flow>/load.json
load:
	@set -e; for d in $(DATASETS_SEL); do \
	  flows="$(if $(filter command line,$(origin FLOWS)),$(FLOWS),$$(python3 lane.py flows $$d))"; \
	  python3 lane.py request $$d $$flows; \
	  case " $$flows " in *" csvloader-split "*) $(MAKE) --no-print-directory _prepare SVC=loader;; esac; \
	  $(MAKE) --no-print-directory -j $$(echo $$flows | wc -w) DATASET=$$d $$(for f in $$flows; do echo _lane-$$f; done); \
	done

# the CSVLoader of lane 2 is the one of LOADER_REF (default SERVICES_REF), not the one built from this checkout
# (a CSVLoader that cannot be found stops the recipe: lane 2 never falls back to a jar built from the checkout)
_lane-%:
	@csv=""; \
	if [ "$*" = csvloader-split ]; then \
	  csv="$$(python3 services_ref.py get loader csv)" || exit 1; \
	  test -f "$$csv" || { echo "lane 2: no CSVLoader of LOADER_REF / SERVICES_REF (got '$$csv'): run make jars"; exit 1; }; \
	fi; \
	echo "python3 lane.py load $(DATASET) $*"; \
	CSVLOADER_JAR="$$csv" python3 lane.py load $(DATASET) $*

# ---------------------------------------------------------------------------------------------------------------- stack

# up LAYERS=hpds,api,dict: start the layers (always in the order hpds, api, dict, then any other); DATASET picks which loaded stores they serve (a
# single dataset, or dir); STORE picks the lane whose HPDS the api layer serves
up:
	@test "$(DATASET)" != all || { echo "up serves one dataset: DATASET=mini|1000g|combined|synth|edge|dir"; exit 2; }
	@set -e; for l in hpds api dict $(filter-out hpds api dict,$(LAYER_LIST)); do \
	  case " $(LAYER_LIST) " in *" $$l "*) $(MAKE) --no-print-directory _up-$$l;; esac; \
	done

# waits for the HPDS service $(1) on port $(2)
define wait_hpds
printf "waiting for $(1) on port $(2) "; \
for i in $$(seq 1 90); do curl -fs "http://$(HPDS_HOST):$(2)/actuator/health" | grep -q UP && break; \
  [ "$$(docker inspect -f '{{.State.Running}}' picsure-local-$(1) 2>/dev/null)" = false ] && { printf " exited"; break; }; printf .; sleep 2; done; echo; \
curl -fs "http://$(HPDS_HOST):$(2)/actuator/health" | grep -q UP || { echo "$(1) is not healthy: $(COMPOSE) logs $(1)"; exit 1; }
endef

# REBUILD=1 rebuilds the service jar and the image here (compare passes REBUILD= on: its jars step has done that once)
_up-hpds: _image-hpds
	$(COMPOSE) --profile '*' down --remove-orphans
	@$(MAKE) --no-print-directory $(if $(filter dir,$(DATASET)),_hpds-dir,_hpds-lanes)
	@if [ "$(OPEN)" = 1 ]; then $(MAKE) --no-print-directory _hpds-open; fi

_hpds-lanes:
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
	  $(call wait_hpds,$$s,$$port) \
	done

# DATASET=dir: HPDS_DIR is the partition root, KEY_FILE the key; no loader runs
_hpds-dir:
	@python3 serve.py check-dir "$(HPDS_DIR)" "$(KEY_FILE)"
	@$(MAKE) --no-print-directory _genomic-prepare
	$(COMPOSE) --profile hpds up -d hpds-dir
	@$(call wait_hpds,hpds-dir,$(HPDS_DIR_PORT))

# the genomic directory of hpds-dir (GENOMIC=fixture|empty), then the pre-flight on it: the same check qa makes, before HPDS is started on a
# directory it cannot start on (GENOMIC_PREFLIGHT=off skips it, to see HPDS fail)
_genomic-prepare:
ifeq ($(GENOMIC_ON),fixture)
	@$(GUARD_WORK)
	@$(MAKE) --no-print-directory _prepare SVC=loader
	@python3 genomic.py build fixture --out "$(GENOMIC_WORK)" --loader-jar "$$(python3 services_ref.py get loader vcf)" $(if $(GENOMIC_SPLIT_DIR),--split-dir "$(GENOMIC_SPLIT_DIR)")
else ifeq ($(GENOMIC_ON),empty)
	@$(GUARD_WORK)
	@python3 genomic.py build empty --out "$(GENOMIC_WORK)"
endif
ifneq ($(GENOMIC_ON),)
	@$(if $(filter off,$(GENOMIC_PREFLIGHT)),echo "genomic pre-flight skipped (GENOMIC_PREFLIGHT=off)",python3 genomic.py check "$(GENOMIC_ROOT)")
endif

# the open-mode HPDS: the same partitions, authorization filter off. A synthetic dataset gets an open columnMeta without the STIGVARS paths
# (RemoveConceptFromMetadata, as the release flow does); OPEN_COLUMNMETA_DIR=<dir>/<partition>/columnMeta.javabin supplies the open files
# for any dataset; with neither, the open HPDS serves the same files with the filter off.
_hpds-open:
	@if [ -d "$(SERVE_ROOT)" ]; then $(MAKE) --no-print-directory _hpds-open-start; \
	else echo "open HPDS not started: no store of $(STORE) for $(DATASET) ($(SERVE_ROOT))"; test "$(DATASET)" != dir; fi

_hpds-open-start:
	@if [ -z "$(OPEN_COLUMNMETA_DIR)" ] && [ -n "$(GEN_OVERRIDES)" ]; then $(MAKE) --no-print-directory _open-columnmeta; fi
	@# a marker from an earlier run names a loader that this open root does not use: qa would count it
	@if [ -n "$(OPEN_COLUMNMETA_DIR)" ] && [ -n "$(GEN_OVERRIDES)" ]; then rm -f -- "$(GEN_OVERRIDES).jar"; fi
	@if [ -n "$(OPEN_OVERRIDES)" ]; then python3 serve.py open-root "$(SERVE_ROOT)" "$(OPEN_OVERRIDES)" "$(OPEN_ROOT)"; \
	else echo "open root: the served partitions, filter off, no columnMeta override"; fi
	$(COMPOSE) --profile hpds up -d hpds-open
	@$(call wait_hpds,hpds-open,$(HPDS_OPEN_PORT))

# one container, one pass over the partitions: RemoveConceptFromMetadata reads and writes /opt/local/hpds/columnMeta.javabin
_open-columnmeta:
	@$(GUARD_WORK)
	@$(MAKE) --no-print-directory _prepare SVC=loader
	@test -f "$(STIGVARS)" || { echo "STIGVARS=$(STIGVARS) is not a file"; exit 1; }
	@rm -rf -- "$(GEN_OVERRIDES)" "$(GEN_OVERRIDES).jar" && mkdir -p "$(GEN_OVERRIDES)"
	@python3 services_ref.py get loader rm > "$(GEN_OVERRIDES).jar" || exit 1
	@docker run --rm --user "$$(id -u):$$(id -g)" --tmpfs /opt/local/hpds:uid=$$(id -u),gid=$$(id -g),mode=755 --entrypoint sh \
	  -v "$(SERVE_ROOT):/in:ro" -v "$(GEN_OVERRIDES):/out" -v "$(STIGVARS):/stigvars.txt:ro" -v "$$(python3 services_ref.py get loader rm):/rm.jar:ro" "$(HPDS_IMAGE)" -c ' \
	  set -e; for d in /in/*/; do p=$$(basename "$$d"); [ -f "$$d/columnMeta.javabin" ] || continue; \
	  cp "$$d/columnMeta.javabin" /opt/local/hpds/columnMeta.javabin; cp /stigvars.txt /opt/local/hpds/conceptsToRemove.txt; \
	  java -Xmx512m -cp /rm.jar edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.util.RemoveConceptFromMetadata >/dev/null; \
	  mkdir -p "/out/$$p"; cp /opt/local/hpds/columnMeta.javabin "/out/$$p/columnMeta.javabin"; done'
	@echo "open columnMeta: $$(ls "$(GEN_OVERRIDES)" | wc -l | tr -d ' ') partitions without the paths of $(notdir $(STIGVARS))"

# the API in front of the HPDS of lane $(STORE) (or of hpds-dir). The hpds layer is up before this runs; it recreates the whole stack, so this
# layer is always started after it. With the open-mode HPDS up, the query-service's open backend is that HPDS. Waits for the
# operations-service and the query-service to report UP (compose starts mysql, flyway and them in order).
_up-api: _image-api
	@case "$(STORE)" in lane1|lane2|lane3|dir) ;; *) echo "STORE=$(STORE): expected lane1, lane2 or lane3"; exit 2;; esac
	@docker ps --format '{{.Names}}' | grep -qx picsure-local-hpds-$(STORE) || { echo "layer api needs the HPDS of $(STORE) running (layer hpds; $(STORE) has no store for $(DATASET)?)"; exit 1; }
	@# the schema migrations are those of the operations-service commit that is served; without them flyway has nothing to run
	@src="$$(python3 services_ref.py get operations src)" || exit 1; sql="$$src/services/pic-sure-operations-service/db/sql"; \
	test -n "$$src" && test -d "$$sql" || { echo "no operations-service migrations at '$$sql': run make jars LAYERS=$(LAYERS)"; exit 1; }; \
	echo "flyway migrations: $$sql"; \
	API_FLYWAY_SQL="$$sql" $(COMPOSE) --profile api up -d
	@set -e; for pair in operations:$(API_OPERATIONS_PORT):operations/actuator/health query:$(API_QUERY_PORT):actuator/health; do \
	  name=$${pair%%:*}; rest=$${pair#*:}; port=$${rest%%:*}; path=$${rest#*:}; \
	  printf "waiting for api-%s on port %s " $$name $$port; \
	  for i in $$(seq 1 90); do curl -fs "http://$(API_HOST):$$port/$$path" | grep -q UP && break; printf .; sleep 2; done; echo; \
	  curl -fs "http://$(API_HOST):$$port/$$path" | grep -q UP || { echo "api-$$name is not healthy: $(COMPOSE) logs api-$$name"; exit 1; }; \
	done

# Postgres, then the dictionary: a synthetic SQL fixture generated from the dataset's own ground truth (dictgen.py), or the dump DICT_DUMP.
# The layer is independent of the others (it needs only the dataset's input for the synthetic dictionary) and replaces its own containers.
_up-dict: _image-dict
	@test "$(DATASET)" != dir || test -n "$(DICT_DUMP)" || { echo "layer dict with DATASET=dir needs DICT_DUMP=<dictionary_db_*.tar> (there is no input to derive a dictionary from)"; exit 2; }
	@if [ -n "$(DICT_DUMP)" ]; then test -f "$(DICT_DUMP)" || { echo "DICT_DUMP=$(DICT_DUMP) is not a file"; exit 2; }; \
	else python3 dictgen.py build $(DATASET); fi
	$(COMPOSE) --profile dict down --remove-orphans
	$(COMPOSE) --profile dict up -d
	@set -e; printf "waiting for dict-api on port $(DICT_API_PORT) "; \
	for i in $$(seq 1 90); do curl -fs "http://$(API_HOST):$(DICT_API_PORT)/actuator/health" | grep -q UP && break; printf .; sleep 2; done; echo; \
	curl -fs "http://$(API_HOST):$(DICT_API_PORT)/actuator/health" | grep -q UP || { echo "dict-api is not healthy: $(COMPOSE) logs dict-load dict-api"; exit 1; }

down:
	$(COMPOSE) --profile '*' down --remove-orphans

restart: down up

# ---------------------------------------------------------------------------------------------------------------- compare

# compare DATASET=mini|1000g|combined|synth|all: jars, split, load, up and compare.py for each dataset, then the report
compare: jars
	@test "$(DATASET)" != dir || { echo "DATASET=dir has no input to compare with: use make up DATASET=dir ..., then make qa"; exit 2; }
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
	for pair in dir:$(HPDS_DIR_PORT):"served directory" open:$(HPDS_OPEN_PORT):"open mode, filter off"; do \
	  n=$${pair%%:*}; rest=$${pair#*:}; port=$${rest%%:*}; what=$${rest#*:}; \
	  if docker ps --format '{{.Names}}' | grep -qx picsure-local-hpds-$$n; then echo "  hpds-$$n ($$what): http://$(PUBLIC_HOST):$$port"; fi; \
	done; \
	if docker ps --format '{{.Names}}' | grep -qx picsure-local-dict-api; then \
	  echo "dictionary: http://$(PUBLIC_HOST):$(DICT_API_PORT)  POST /concepts  postgres 127.0.0.1:$(DICT_DB_PORT) (db picsure, user picsure, schema dict)"; \
	fi; \
	if docker ps --format '{{.Names}}' | grep -qx picsure-local-query; then \
	  echo "API (store $(STORE)): query-service http://$(PUBLIC_HOST):$(API_QUERY_PORT)  POST /hpds/auth/v3/query/sync  (header X-User-Id)"; \
	  echo "     operations-service http://$(PUBLIC_HOST):$(API_OPERATIONS_PORT)/operations  mysql 127.0.0.1:$(API_MYSQL_PORT) (db picsure, user picsure)"; \
	fi; \
	echo "Report: $(WORK)/report/compare.md"

# ---------------------------------------------------------------------------------------------------------------- qa

# the QA battery against the stack that is up: the HPDS of $(STORE), and whichever of the open-mode HPDS, the query-service and the dictionary
# is running. QA_REQUIRE=open,api,dict,stigvar makes a missing one a setup problem (exit 2) instead of a skipped check. QA_MANIFEST="<file> ..." names
# loader manifests (patients / observations per partition) as the source of truth; a synthetic dataset also uses its own ground truth.
# qa.py exits 0 = all PASS, 1 = any FAIL, 2 = inconclusive or setup problem (make itself reports any failure as 2: the last output line
# says QA PASS, FAIL or INCONCLUSIVE). Reports: work/report/qa/qa.json and qa.tsv. Counts only.
QA_HPDS_PORT = $(if $(filter dir,$(STORE)),$(HPDS_DIR_PORT),$(HPDS_LANE$(subst lane,,$(STORE))_PORT))
qa:
	@python3 qa.py --hpds "http://$(HPDS_HOST):$(QA_HPDS_PORT)" --hpds-container "picsure-local-hpds-$(STORE)" --store "$(SERVE_ROOT)" --user-id "$(API_USER_ID)" \
	  $(if $(and $(GEN_OVERRIDES),$(if $(OPEN_COLUMNMETA_DIR),,1)),--loader-jar-file "$(GEN_OVERRIDES).jar") \
	  $(if $(filter-out dir,$(DATASET)),--dataset $(DATASET)) \
	  $(foreach m,$(QA_MANIFEST),--manifest "$(m)") \
	  $(if $(STIGVARS),--stigvars "$(STIGVARS)") \
	  $(if $(QA_REQUIRE),--require "$(QA_REQUIRE)") \
	  $(if $(QA_COLUMNMETA_ROOT),--columnmeta-root "$(QA_COLUMNMETA_ROOT)") \
	  $$(docker ps --format '{{.Names}}' | grep -qx picsure-local-hpds-open && echo --open http://$(HPDS_HOST):$(HPDS_OPEN_PORT)) \
	  $$(docker ps --format '{{.Names}}' | grep -qx picsure-local-query && echo --api http://$(API_HOST):$(API_QUERY_PORT)) \
	  $$(docker ps --format '{{.Names}}' | grep -qx picsure-local-dict-api && echo --dict http://$(API_HOST):$(DICT_API_PORT)) \
	  $(if $(GENOMIC_ON),--genomic-dir "$(GENOMIC_ROOT)" --genomic-manifest "$(GENOMIC_WORK)/genomic.json") \
	  --report-dir "$(QA_REPORT)" $(QA_ARGS)

# The three scenarios on a small phenotype dataset (SCENARIO_DATASET, loaded by the CSVLoader of the ref), each started and checked on its own:
#   off      phenotype only: no genomic processor (what the stack ran before)          expected: QA PASS
#   fixture  the BDC genomic settings and a genomic store built from the public VCFs  expected: QA PASS
#   empty    the same settings, the genomic directory present with no dataset folder   expected: QA FAIL naming that cause (HPDS cannot start)
# Prints the result line of each and exits 1 unless each came out as expected. Reports: work/report/qa-<scenario>/.
qa-scenarios:
	@$(GUARD_WORK)
	@$(if $(filter combined synth,$(SCENARIO_DATASET)),$(MAKE) --no-print-directory $(SPLITTER_JAR))
	@$(MAKE) --no-print-directory REBUILD= split DATASET=$(SCENARIO_DATASET)
	@$(MAKE) --no-print-directory REBUILD= load DATASET=$(SCENARIO_DATASET) FLOWS=csvloader-split
	@set -u; store="$(WORK)/$(SCENARIO_DATASET)/csvloader-split/store"; split="$(WORK)/$(SCENARIO_DATASET)/split"; mkdir -p "$(WORK)/report"; \
	vars="DATASET=dir HPDS_DIR=$$store KEY_FILE=$$store/encryption_key OPEN=0 GENOMIC_SPLIT_DIR=$$split"; bad=0; : > "$(WORK)/report/scenarios.txt"; \
	for s in off fixture empty; do \
	  echo "=== scenario $$s"; extra=""; [ $$s = empty ] && extra="GENOMIC_PREFLIGHT=off"; \
	  $(MAKE) --no-print-directory up $$vars GENOMIC=$$s $$extra > "$(WORK)/report/up-$$s.log" 2>&1 || tail -3 "$(WORK)/report/up-$$s.log"; \
	  $(MAKE) --no-print-directory qa $$vars GENOMIC=$$s QA_ARGS="--dataset $(SCENARIO_DATASET)" QA_REPORT="$(WORK)/report/qa-$$s" > "$(WORK)/report/qa-$$s.log" 2>&1; rc=$$?; \
	  line=$$(grep '^QA ' "$(WORK)/report/qa-$$s.log" | tail -1); grep '^scenario:' "$(WORK)/report/qa-$$s.log" | head -1; echo "$$line"; \
	  python3 genomic.py expect $$s "$(WORK)/report/qa-$$s/qa.json" "$(WORK)/report/qa-$$s.log" || { bad=1; echo "  scenario $$s: NOT as expected (qa exit $$rc): $(WORK)/report/qa-$$s.log"; }; \
	  printf '%s\texit %s\t%s\n' "$$s" "$$rc" "$$line" >> "$(WORK)/report/scenarios.txt"; \
	done; $(MAKE) --no-print-directory down > /dev/null 2>&1; \
	if [ $$bad = 0 ]; then echo "scenarios: as expected (off PASS, fixture PASS, empty FAIL with its cause)"; else echo "scenarios: NOT as expected"; fi; exit $$bad

# the unit tests of the helpers (no Docker)
test:
	python3 -m unittest discover -s tests

# ---------------------------------------------------------------------------------------------------------------- clean

# the guard runs before anything, `down` included
clean:
	@$(GUARD_WORK)
	@$(MAKE) --no-print-directory down
	@python3 genomic.py clear-all
	rm -rf -- "$(WORK)"

# everything loaded or compared, but not the splitter build or the HPDS image. work/genomic holds patient ids of the run (vcfIndex-*.tsv, genomic.json)
# and files HPDS wrote as root: it goes through genomic.py, which removes it from a container when the host cannot
clean-data:
	@$(GUARD_WORK)
	@$(MAKE) --no-print-directory down
	@python3 genomic.py clear-all
	@for d in $(DATASETS_ALL) report; do rm -rf -- "$(WORK)/$$d"; done

#!/usr/bin/env bash
#
# Build the PIC-SURE monorepo reactor from the checkout alone.
#
# The root POM imports edu.harvard.hms.dbmi.avillach:pic-sure-bom, whose source
# is this repository's platform/pom.xml. Maven resolves an imported BOM while
# building the root *model*, before any reactor module is available — so on a
# cold runner it would try to download the BOM from a package repository. Fork
# tokens cannot read the HMS-owned packages, which is what produced the 401s.
#
# Installing the standalone BOM first makes the reactor self-contained: no
# token env var, no warm cache, no private-package access.
#
# Usage: scripts/ci/run-maven-reactor.sh <maven args...>
#   e.g. scripts/ci/run-maven-reactor.sh --update-snapshots verify
#        scripts/ci/run-maven-reactor.sh -pl services/pic-sure-logging -am verify
#
# MAVEN_OPTS is inherited by both invocations, so a caller-pinned
# -Dmaven.repo.local applies to the BOM install and the reactor build alike.

set -euo pipefail

if [ "$#" -eq 0 ]; then
    echo "usage: ${0##*/} <maven args...>" >&2
    exit 2
fi

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
cd -- "${REPO_ROOT}"

# Stage 1: the version catalog, installed standalone so the root model resolves.
mvn -B -f platform/pom.xml install

# Stage 2: the requested reactor build, from the repository root.
mvn -B "$@"

#!/usr/bin/env python3

import hashlib
import http.client
import json
import os
import shutil
import signal
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.request
from pathlib import Path

import contract


OLD_BACKEND_COMMIT = "9251d64f607acc198d95c7d53294807cc56efa82"
FINAL_BACKEND_COMMIT = "9c17b0caecbee1b7f2231ca974b8b8b59ba7f211"
OLD_FRONTEND_COMMIT = "e49ae2d07cfb76cdbe9186161c3d726ae76ba416"
FINAL_FRONTEND_COMMIT = "7b69aa960ff98f97c1a2d026b7137b0e3dcdf603"
AIO_COMMIT = "05b1a77512dc0921570f0d442853fdcee75b8131"
BDC_COMMIT = "5d2ba9f59f161ace5e807c82a0580518a9d44d16"

BACKEND_TREES = {
    "old": "d6195f4acced760904d1e0d025dc86c4983fa64f",
    "final": "c211efbbe69944c791b2d7f897b9d05b1593e71d",
}
FRONTEND_TREES = {
    "old": "419ef5cf7ff8f9981218976e93a14f51ea17b8f2",
    "final": "e4506d9e5bca3a42da2e5436750c8951da2076ee",
}
BACKEND_COMMITS = {"old": OLD_BACKEND_COMMIT, "final": FINAL_BACKEND_COMMIT}
FRONTEND_COMMITS = {"old": OLD_FRONTEND_COMMIT, "final": FINAL_FRONTEND_COMMIT}

OPERATIONS_URL = "https://github.com/hms-dbmi/pic-sure.git"
FRONTEND_URL = "https://github.com/hms-dbmi/PIC-SURE-Frontend.git"
AIO_URL = "https://github.com/hms-dbmi/PIC-SURE-Migrations.git"
BDC_URL = "https://github.com/hms-dbmi/pic-sure-bdc-infrastructure.git"

MYSQL_IMAGE = "mysql:8.0.43@sha256:ccf4fed7ff4b886aeb3573a1f5d5b509525ecff55a2d1e2653c27a5abdded309"
FLYWAY_IMAGE = "flyway/flyway:11.7.2@sha256:8ace7d9825bb3ad1d6e14ee27b3a830b638ac841ba424b99b2d92aa65a99d484"
BUILD_IMAGE = "maven:3-amazoncorretto-25@sha256:de7a3e517efac1b933af6ceb375974a061ba71c908ea51a18bd937716a8ade93"
RUNTIME_IMAGE = "amazoncorretto:25@sha256:397edfaaa0fdfc95001d4c4a4ab82174073277a5d630fd9375c94dca25b5991d"
BROWSER_IMAGE = "mcr.microsoft.com/playwright:v1.60.0-noble@sha256:9bd26ad900bb5e0f4dee75839e957a89ae89c2b7ab1e76050e559790e946b948"
JDK_RUNTIME = 'openjdk version "25.0.4" 2026-07-21 LTS'
FRONTEND_NODE_IMAGE = "node:24.19.0-alpine3.23@sha256:244cc2b53f46f9e876304391d17682b0ddae9ac33491f4857e25e35a36ba7995"
FRONTEND_HTTPD_IMAGE = "httpd:2.4.68-alpine3.23@sha256:4a15e9c73f25334bc03cfb3c692c9adfc103bb46ca89cee1f0b9a5fcbc7b21f6"
FRONTEND_NODE_VERSION = "v24.19.0"
FRONTEND_DOCKERFILE_SHA256 = "23a550f373f07475efd8a838161e5e031e8706b14640a8a13d44de9ef0c9938e"
FRONTEND_LOCKFILE_SHA256 = "47fe7fcc0c0d775ad771ceca0f28327d019d2816639e88699eeae62256a2d2bc"
TICKET17_MATRIX_SHA256 = "a211596a81df2488caad8a9ffefe881aff9804fda7a6199e3968cbdf1535614d"
ROLLOUT_CONTRACT_BEFORE_TICKET18_SHA256 = "ce4b2f3b448e254f2017e88a2649136e9d9edbec4ca4a187274d3509458ea23f"

DEFAULT_COMMAND_TIMEOUT_SECONDS = 300
BUILD_COMMAND_TIMEOUT_SECONDS = 1800
GIT_ARCHIVE_TIMEOUT_SECONDS = 90
SYNTHETIC_ROOT_PASSWORD = "banner-feed-proof"
ADMIN_HEADERS = {
    "Content-Type": "application/json",
    "X-User-Id": "banner-feed-admin",
    "X-User-Privileges": "ADMIN",
}

FIXTURES = {
    "all": {
        "uuid": "11111111-1111-4111-8111-111111111111",
        "title": "T18 11111111 all pages",
        "html": "<p>T18 all pages marker</p>",
        "targets": [{"kind": "ALL"}],
    },
    "login": {
        "uuid": "22222222-2222-4222-8222-222222222222",
        "title": "T18 22222222 login target",
        "html": "<p>T18 login target marker</p>",
        "targets": [{"kind": "EXACT", "path": "/login"}],
    },
    "not-login": {
        "uuid": "33333333-3333-4333-8333-333333333333",
        "title": "T18 33333333 not-login target",
        "html": "<p>T18 not-login target marker</p>",
        "targets": [{"kind": "EXACT", "path": "/not-login"}],
    },
    "scheduled": {
        "uuid": "44444444-4444-4444-8444-444444444444",
        "title": "T18 44444444 scheduled subtree",
        "html": "<p>T18 scheduled target marker</p>",
        "targets": [{"kind": "SUBTREE", "path": "/scheduled"}],
    },
}

ENV_INPUT = """VITE_APPLICATION_NAME=Banner compatibility proof
VITE_ORIGIN=http://frontend
VITE_CONFIG_MODE=override
VITE_API_CONFIG_FEATURES=
VITE_API_CONFIG_SETTINGS=
VITE_API_CONFIG_BRANDING=
VITE_MAX_CONFIG_RETRIES=0
"""
ENV_INPUT_SHA256 = hashlib.sha256(ENV_INPUT.encode("utf-8")).hexdigest()

VHOST_INPUT = """<VirtualHost *:80>
    ServerName frontend
    ProxyRequests Off
    ProxyPreserveHost On
    ProxyPass /picsure/ http://gateway:8080/
    ProxyPassReverse /picsure/ http://gateway:8080/
    ProxyPass / http://127.0.0.1:3000/
    ProxyPassReverse / http://127.0.0.1:3000/
    ErrorLog /dev/stderr
    CustomLog /dev/stdout combined
</VirtualHost>
"""
VHOST_INPUT_SHA256 = hashlib.sha256(VHOST_INPUT.encode("utf-8")).hexdigest()


class Harness:

    def __init__(self, repository_root, temp_root, run_id, selection):
        self.repository_root = Path(repository_root)
        self.test_dir = self.repository_root / "tests" / "banner-feed-compatibility"
        self.ticket17_dir = self.repository_root / "tests" / "operations-binary-compatibility"
        self.temp_root = Path(temp_root)
        self.run_id = run_id
        self.selection = selection
        self.label = f"org.pic-sure.banner-feed-compatibility={run_id}"
        self.network = f"banner-feed-compat-{run_id}"
        self.expected_by_cell = {row["cell"]: row for row in contract.load_matrix(self.test_dir / "matrix.tsv")}
        self.observations = []
        self.operations_source = None
        self.frontend_source = None
        self.aio_source = None
        self.bdc_source = None
        self.backend_exports = {}
        self.frontend_exports = {}
        self.jars = {}
        self.jar_sha256 = {}
        self.frontend_images = {}
        self.frontend_image_ids = {}
        self.browser_probe_image = f"banner-feed-browser-proof:{run_id.lower()}"
        self.mysql_container = None
        self.operations_container = None
        self.gateway_container = None
        self.frontend_container = None
        self.operations_url = None
        self.gateway_url = None
        self.frontend_url = None

    def run(self):
        self.require_tools_and_runtime()
        self.prepare_sources()
        self.verify_static_inputs()
        self.prepare_exports()
        self.build_backend_binaries()
        self.build_frontend_images()
        self.build_browser_probe()
        self.create_network()
        selected = contract.REQUIRED_CELLS if self.selection == "all" else [self.selection]
        observed_path = self.temp_root / "observed-matrix.tsv"
        for cell in selected:
            print(f"== feed matrix phase: {cell} ==", flush=True)
            try:
                observation = getattr(self, "cell_" + cell.replace("-", "_"))()
                self.observations.append(observation)
                contract.write_observed_matrix(observed_path, self.observations)
                self.require_observation_matches(observation)
            except Exception as error:
                cleanup_errors = self.cleanup_cell_resources()
                self.write_failure_diagnostics(observed_path, cell, error, cleanup_errors)
                raise
            cleanup_errors = self.cleanup_cell_resources()
            if cleanup_errors:
                error = contract.ContractError("cell cleanup failed: " + "; ".join(cleanup_errors))
                self.write_failure_diagnostics(observed_path, cell, error, cleanup_errors)
                raise error

        if self.selection == "all":
            print("== Ticket 17 composition phase ==", flush=True)
            self.run_ticket17_composition()
        self.write_provenance()
        print(f"Observed matrix: {observed_path}")
        print(f"PASS: {', '.join(selected)}", flush=True)

    def require_tools_and_runtime(self):
        missing = [tool for tool in ("docker", "git") if shutil.which(tool) is None]
        if missing:
            raise contract.ContractError(f"missing required tools: {', '.join(missing)}")
        runtime = self.command(
            ["docker", "run", "--rm", RUNTIME_IMAGE, "java", "-version"],
            capture=True,
            merge_stderr=True,
        )
        first_line = runtime.stdout.splitlines()[0] if runtime.stdout.splitlines() else ""
        if first_line != JDK_RUNTIME:
            raise contract.ContractError(f"JDK runtime drift: expected {JDK_RUNTIME!r}, got {first_line!r}")

    def prepare_sources(self):
        self.operations_source = self.prepare_multi_commit_source(
            os.environ.get("OPERATIONS_COMPAT_SOURCE_ROOT"),
            OPERATIONS_URL,
            list(BACKEND_COMMITS.values()),
            self.temp_root / "sources" / "pic-sure",
            "backend source",
        )
        self.frontend_source = self.prepare_multi_commit_source(
            os.environ.get("FRONTEND_COMPAT_SOURCE_ROOT"),
            FRONTEND_URL,
            list(FRONTEND_COMMITS.values()),
            self.temp_root / "sources" / "frontend",
            "frontend source",
        )
        self.aio_source = self.prepare_exact_source(
            os.environ.get("AIO_PROOF_SOURCE_ROOT"), AIO_URL, AIO_COMMIT,
            self.temp_root / "sources" / "aio", "Ticket 15 AIO proof",
        )
        self.bdc_source = self.prepare_exact_source(
            os.environ.get("BDC_PROOF_SOURCE_ROOT"), BDC_URL, BDC_COMMIT,
            self.temp_root / "sources" / "bdc", "Ticket 16 BDC/AIM proof",
        )

    def prepare_multi_commit_source(self, override, url, commits, destination, label):
        if override:
            source = Path(override).resolve()
            contract.require_clean_repository(source, label)
            for commit in commits:
                contract.require_git_commit(source, label, commit)
            return source
        self.fetch_repository(url, commits, destination, label)
        return destination

    def prepare_exact_source(self, override, url, commit, destination, label):
        if override:
            source = Path(override).resolve()
            contract.require_repository_head(source, label, commit)
            return source
        self.fetch_repository(url, [commit], destination, label)
        contract.require_repository_head(destination, label, commit)
        return destination

    def fetch_repository(self, url, commits, destination, label):
        destination.parent.mkdir(parents=True, exist_ok=True)
        self.command(["git", "init", "--quiet", destination])
        self.command(["git", "-C", destination, "remote", "add", "origin", url])
        for commit in commits:
            result = self.command(
                ["git", "-C", destination, "fetch", "--quiet", "--depth", "1", "origin", commit],
                check=False,
                capture=True,
            )
            if result.returncode != 0:
                raise contract.ContractError(
                    f"{label} exact commit {commit} is not publicly reachable. Publish the prerequisite commit "
                    f"or provide its clean exact source root. Git error: {result.stderr.strip()}"
                )
        self.command(["git", "-C", destination, "checkout", "--quiet", "--detach", commits[-1]])

    def verify_static_inputs(self):
        for generation, commit in BACKEND_COMMITS.items():
            contract.require_git_tree(self.operations_source, f"{generation} backend", commit, BACKEND_TREES[generation])
        for generation, commit in FRONTEND_COMMITS.items():
            contract.require_git_tree(self.frontend_source, f"{generation} frontend", commit, FRONTEND_TREES[generation])
        ticket17_matrix = self.ticket17_dir / "matrix.tsv"
        actual_ticket17 = contract.sha256_file(ticket17_matrix)
        if actual_ticket17 != TICKET17_MATRIX_SHA256:
            raise contract.ContractError(
                f"Ticket 17 matrix checksum mismatch: expected {TICKET17_MATRIX_SHA256}, got {actual_ticket17}"
            )
        if not (self.repository_root / ".github" / "banner-rollout-contract.json").is_file():
            raise contract.ContractError("missing shared banner rollout contract")
        contract.require_repository_head(self.aio_source, "Ticket 15 AIO proof", AIO_COMMIT)
        contract.require_repository_head(self.bdc_source, "Ticket 16 BDC/AIM proof", BDC_COMMIT)

    def prepare_exports(self):
        for generation, commit in BACKEND_COMMITS.items():
            self.backend_exports[generation] = self.export_commit(
                self.operations_source, commit, self.temp_root / "exports" / f"backend-{generation}",
                f"{generation} backend",
            )
        for generation, commit in FRONTEND_COMMITS.items():
            export = self.export_commit(
                self.frontend_source, commit, self.temp_root / "exports" / f"frontend-{generation}",
                f"{generation} frontend",
            )
            dockerfile_sha = contract.sha256_file(export / "Dockerfile")
            lock_sha = contract.sha256_file(export / "pnpm-lock.yaml")
            if dockerfile_sha != FRONTEND_DOCKERFILE_SHA256 or lock_sha != FRONTEND_LOCKFILE_SHA256:
                raise contract.ContractError(
                    f"{generation} production input drift: Dockerfile={dockerfile_sha}, lockfile={lock_sha}"
                )
            dockerfile = (export / "Dockerfile").read_text(encoding="utf-8")
            for base_image in (FRONTEND_NODE_IMAGE, FRONTEND_HTTPD_IMAGE):
                if f"FROM {base_image}" not in dockerfile:
                    raise contract.ContractError(
                        f"{generation} production Dockerfile does not use pinned base image {base_image}"
                    )
            (export / ".env").write_text(ENV_INPUT, encoding="utf-8")
            if contract.sha256_file(export / ".env") != ENV_INPUT_SHA256:
                raise contract.ContractError(f"{generation} generated .env checksum drift")
            self.frontend_exports[generation] = export

        vhost = self.temp_root / "generated" / "httpd-vhosts.conf"
        vhost.parent.mkdir(parents=True, exist_ok=True)
        vhost.write_text(VHOST_INPUT, encoding="utf-8")
        if contract.sha256_file(vhost) != VHOST_INPUT_SHA256:
            raise contract.ContractError("generated HTTP vhost checksum drift")

    def export_commit(self, repository, commit, destination, label):
        destination.mkdir(parents=True, exist_ok=True)
        archive = self.temp_root / f"{label.replace(' ', '-')}.tar"
        with archive.open("wb") as handle:
            try:
                result = subprocess.run(
                    ["git", "-C", repository, "archive", commit],
                    check=False,
                    stdout=handle,
                    stderr=subprocess.PIPE,
                    timeout=GIT_ARCHIVE_TIMEOUT_SECONDS,
                )
            except subprocess.TimeoutExpired as error:
                raise contract.ContractError(f"{label} Git archive timed out") from error
        if result.returncode != 0:
            raise contract.ContractError(
                f"could not export {label}: {result.stderr.decode('utf-8', errors='replace')}"
            )
        with tarfile.open(archive) as tar:
            root = destination.resolve()
            for member in tar.getmembers():
                target = (destination / member.name).resolve()
                if target != root and root not in target.parents:
                    raise contract.ContractError(f"unsafe path in {label} Git archive: {member.name}")
            tar.extractall(destination)
        return destination

    def build_backend_binaries(self):
        m2 = Path(os.environ.get("BANNER_FEED_M2_ROOT", self.temp_root / "m2")).resolve()
        m2.mkdir(parents=True, exist_ok=True)
        for generation, export in self.backend_exports.items():
            timestamp = self.command(
                ["git", "-C", self.operations_source, "show", "-s", "--format=%cI", BACKEND_COMMITS[generation]],
                capture=True,
            ).stdout.strip()
            self.command(
                [
                    "docker", "run", "--rm", "--label", self.label,
                    "--user", f"{os.getuid()}:{os.getgid()}",
                    "-e", "HOME=/tmp/banner-feed-home", "-e", "MAVEN_CONFIG=/m2",
                    "-v", f"{export}:/source", "-v", f"{m2}:/m2", "-w", "/source", BUILD_IMAGE,
                    "mvn", "-q", "-B", "-Dmaven.repo.local=/m2", f"-Dproject.build.outputTimestamp={timestamp}",
                    "-DskipTests", "-pl", "services/pic-sure-operations-service,services/pic-sure-gateway", "-am", "package",
                ],
                timeout=BUILD_COMMAND_TIMEOUT_SECONDS,
            )
            operations = export / "services" / "pic-sure-operations-service" / "target" / "pic-sure-operations-service-3.0.0.jar"
            gateway = export / "services" / "pic-sure-gateway" / "target" / "pic-sure-gateway-3.0.0.jar"
            if not operations.is_file() or not gateway.is_file():
                raise contract.ContractError(f"missing real {generation} Operations or Gateway jar")
            self.jars[(generation, "operations")] = operations
            self.jars[(generation, "gateway")] = gateway
            self.jar_sha256[(generation, "operations")] = contract.sha256_file(operations)
            self.jar_sha256[(generation, "gateway")] = contract.sha256_file(gateway)
            print(
                f"built backend {generation}: operations={self.jar_sha256[(generation, 'operations')]} "
                f"gateway={self.jar_sha256[(generation, 'gateway')]}",
                flush=True,
            )

    def build_frontend_images(self):
        for generation, export in self.frontend_exports.items():
            image = f"banner-feed-frontend-{generation}:{FRONTEND_TREES[generation][:12]}"
            self.command(
                ["docker", "build", "--label", self.label, "--tag", image, "--file", export / "Dockerfile", export],
                capture=True,
                timeout=BUILD_COMMAND_TIMEOUT_SECONDS,
            )
            image_id = self.command(["docker", "image", "inspect", "--format", "{{.Id}}", image], capture=True).stdout.strip()
            node_version = self.command(
                ["docker", "run", "--rm", "--entrypoint", "node", image, "--version"],
                capture=True,
            ).stdout.strip()
            if node_version != FRONTEND_NODE_VERSION:
                raise contract.ContractError(
                    f"{generation} frontend Node drift: expected {FRONTEND_NODE_VERSION}, got {node_version}"
                )
            self.frontend_images[generation] = image
            self.frontend_image_ids[generation] = image_id
            print(
                f"built production frontend {generation}: image={image_id} .env={ENV_INPUT_SHA256} "
                f"vhost={VHOST_INPUT_SHA256}",
                flush=True,
            )

    def build_browser_probe(self):
        self.command(
            [
                "docker", "build", "--label", self.label, "--tag", self.browser_probe_image,
                "--file", self.test_dir / "browser.Dockerfile", self.test_dir,
            ],
            capture=True,
            timeout=BUILD_COMMAND_TIMEOUT_SECONDS,
        )

    def create_network(self):
        self.command(["docker", "network", "create", "--label", self.label, self.network])

    def start_mysql(self):
        self.stop_mysql()
        name = f"banner-feed-compat-{self.run_id}-mysql"
        self.command(
            [
                "docker", "run", "-d", "--name", name, "--label", self.label,
                "--network", self.network, "--network-alias", "mysql",
                "-e", f"MYSQL_ROOT_PASSWORD={SYNTHETIC_ROOT_PASSWORD}",
                "-e", "MYSQL_DATABASE=picsure", MYSQL_IMAGE,
            ]
        )
        self.mysql_container = name
        deadline = time.monotonic() + 90
        last_error = "not attempted"
        while time.monotonic() < deadline:
            result = self.command(
                [
                    "docker", "exec", "-e", f"MYSQL_PWD={SYNTHETIC_ROOT_PASSWORD}", name,
                    "mysqladmin", "ping", "-h127.0.0.1", "-uroot", "--silent",
                ],
                check=False,
                capture=True,
            )
            if result.returncode == 0:
                self.apply_forward_schema()
                return
            last_error = result.stderr.strip() or result.stdout.strip()
            time.sleep(1)
        raise contract.ContractError(f"MySQL did not become ready within 90 seconds: {last_error}")

    def apply_forward_schema(self):
        migration_dir = self.temp_root / "migration"
        migration_dir.mkdir(parents=True, exist_ok=True)
        for name in (
            "V10__CREATE_BANNER_OCCURRENCE.sql",
            "V11__CREATE_BANNER_VERSION.sql",
            "V12__CREATE_BANNER_PRIORITY_ALLOCATOR.sql",
        ):
            shutil.copyfile(self.aio_source / "Baseline" / "picsure" / name, migration_dir / name)
        self.command(
            [
                "docker", "run", "--rm", "--label", self.label, "--network", self.network,
                "-v", f"{migration_dir}:/flyway/sql:ro", FLYWAY_IMAGE,
                "-url=jdbc:mysql://mysql:3306/picsure", "-user=root",
                f"-password={SYNTHETIC_ROOT_PASSWORD}", "-connectRetries=30", "migrate",
            ]
        )
        tables = self.mysql_query(
            "SELECT table_name FROM information_schema.tables WHERE table_schema='picsure' "
            "AND table_name LIKE 'banner_%' ORDER BY table_name"
        ).splitlines()
        if tables != ["banner_occurrence", "banner_priority_allocator", "banner_version"]:
            raise contract.ContractError(f"forward banner schema drift: {tables}")

    def start_backend(self, generation):
        self.stop_backend()
        operations_name = f"banner-feed-compat-{self.run_id}-operations-{generation}"
        self.command(
            [
                "docker", "run", "-d", "--name", operations_name, "--label", self.label,
                "--network", self.network, "--network-alias", "operations", "-p", "127.0.0.1::8080",
                "-e", "SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/picsure?serverTimezone=UTC",
                "-e", "SPRING_DATASOURCE_USERNAME=root",
                "-e", f"SPRING_DATASOURCE_PASSWORD={SYNTHETIC_ROOT_PASSWORD}",
                "-e", "PICSURE_ACTUATOR_EXPOSURE=health",
                "-e", "PICSURE_ACTUATOR_REQUIRE_TOKEN=false",
                "-v", f"{self.jars[(generation, 'operations')]}:/application.jar:ro", RUNTIME_IMAGE,
                "java", "-jar", "/application.jar", "--logging.level.root=WARN",
            ]
        )
        self.operations_container = operations_name
        self.operations_url = self.container_base_url(operations_name, "/operations")
        self.wait_for_http(
            self.operations_url + "/actuator/health",
            lambda code, body: code == 200 and json.loads(body).get("status") == "UP",
            operations_name,
            f"{generation} Operations",
        )

        gateway_name = f"banner-feed-compat-{self.run_id}-gateway-{generation}"
        self.command(
            [
                "docker", "run", "-d", "--name", gateway_name, "--label", self.label,
                "--network", self.network, "--network-alias", "gateway", "-p", "127.0.0.1::8080",
                "-e", "OPERATIONS_SERVICE_URL=http://operations:8080",
                "-e", "PICSURE_ACTUATOR_EXPOSURE=health",
                "-e", "PICSURE_ACTUATOR_REQUIRE_TOKEN=false",
                "-e", "GATEWAY_OPEN_ACCESS_ENABLED=false",
                "-v", f"{self.jars[(generation, 'gateway')]}:/application.jar:ro", RUNTIME_IMAGE,
                "java", "-jar", "/application.jar", "--logging.level.root=WARN",
            ]
        )
        self.gateway_container = gateway_name
        self.gateway_url = self.container_base_url(gateway_name)
        self.wait_for_http(
            self.gateway_url + "/actuator/health",
            lambda code, body: code == 200 and json.loads(body).get("status") == "UP",
            gateway_name,
            f"{generation} Gateway",
        )

    def start_frontend(self, generation):
        self.stop_frontend()
        name = f"banner-feed-compat-{self.run_id}-frontend-{generation}"
        vhost = self.temp_root / "generated" / "httpd-vhosts.conf"
        self.command(
            [
                "docker", "run", "-d", "--name", name, "--label", self.label,
                "--network", self.network, "--network-alias", "frontend", "-p", "127.0.0.1::80",
                "--no-healthcheck",
                "-v", f"{vhost}:/usr/local/apache2/conf/extra/httpd-vhosts.conf:ro",
                self.frontend_images[generation],
            ]
        )
        self.frontend_container = name
        self.frontend_url = self.container_base_url(name, port="80/tcp")
        self.wait_for_http(
            self.frontend_url + "/login",
            lambda code, body: code == 200 and "<!doctype html>" in body.lower(),
            name,
            f"{generation} production frontend /login",
        )

    def container_base_url(self, container, suffix="", port="8080/tcp"):
        mapping = self.command(["docker", "port", container, port], capture=True).stdout.strip()
        if not mapping or ":" not in mapping:
            raise contract.ContractError(f"container {container} has no mapped {port}")
        host_port = mapping.rsplit(":", 1)[1]
        return f"http://127.0.0.1:{host_port}{suffix}"

    def wait_for_http(self, url, predicate, container, label):
        deadline = time.monotonic() + 120
        last_error = "not attempted"
        while time.monotonic() < deadline:
            code, body = self.http("GET", url)
            try:
                if predicate(code, body):
                    return
            except (json.JSONDecodeError, TypeError, ValueError):
                pass
            last_error = f"HTTP {code}: {body[:300]}"
            running = self.command(
                ["docker", "inspect", "-f", "{{.State.Running}}", container],
                check=False,
                capture=True,
            )
            if running.returncode != 0 or running.stdout.strip() != "true":
                self.capture_logs(container)
                raise contract.ContractError(f"{label} exited before readiness: {last_error}")
            time.sleep(1)
        self.capture_logs(container)
        raise contract.ContractError(f"{label} did not become ready within 120 seconds: {last_error}")

    def seed_fixture(self):
        created = {}
        for name in ("all", "login", "not-login", "scheduled"):
            fixture = FIXTURES[name]
            payload = self.payload(fixture["html"], fixture["title"], fixture["targets"])
            if name == "scheduled":
                payload["startAt"] = "2098-01-01T00:00:00Z"
                payload["endAt"] = "2098-01-02T00:00:00Z"
            created[name] = self.json_request(
                "POST", self.operations_url + "/banners", payload, expected=201, headers=ADMIN_HEADERS
            )
        self.canonicalize_fixture(created)
        management = self.json_request("GET", self.operations_url + "/banners", headers=ADMIN_HEADERS)
        observed = {item["uuid"]: item for item in management}
        expected_ids = {fixture["uuid"] for fixture in FIXTURES.values()}
        if set(observed) != expected_ids:
            raise contract.ContractError(f"fixed fixture UUID mismatch: {sorted(observed)}")
        expected_lifecycle = {
            FIXTURES["all"]["uuid"]: "ACTIVE",
            FIXTURES["login"]["uuid"]: "ACTIVE",
            FIXTURES["not-login"]["uuid"]: "ACTIVE",
            FIXTURES["scheduled"]["uuid"]: "SCHEDULED",
        }
        if {uuid: item["lifecycle"] for uuid, item in observed.items()} != expected_lifecycle:
            raise contract.ContractError("fixture lifecycle drift after deterministic timestamp normalization")

    @staticmethod
    def payload(html, title, targets):
        return {
            "htmlContent": html,
            "title": title,
            "appearance": "PRIMARY",
            "icon": "INFORMATION",
            "dismissible": False,
            "audience": "EVERYONE",
            "placement": "SITE_TOP",
            "pageTargets": targets,
            "startAt": None,
            "endAt": None,
        }

    def canonicalize_fixture(self, created):
        statements = ["SET FOREIGN_KEY_CHECKS=0"]
        for index, name in enumerate(("all", "login", "not-login", "scheduled"), start=1):
            old_uuid = created[name]["uuid"]
            fixed_uuid = FIXTURES[name]["uuid"]
            version_uuid = f"{index + 4:08d}-5555-4555-8555-{index:012d}"
            statements.extend(
                [
                    f"UPDATE banner_version SET banner_uuid=UNHEX(REPLACE('{fixed_uuid}','-','')),"
                    f"uuid=UNHEX(REPLACE('{version_uuid}','-','')) WHERE banner_uuid=UNHEX(REPLACE('{old_uuid}','-',''))",
                    f"UPDATE banner_occurrence SET uuid=UNHEX(REPLACE('{fixed_uuid}','-','')) "
                    f"WHERE uuid=UNHEX(REPLACE('{old_uuid}','-',''))",
                ]
            )
        statements.extend(
            [
                "UPDATE banner_occurrence SET created_at='2026-08-01 00:00:00.000000',"
                "updated_at='2026-08-01 00:01:00.000000',published_at='2026-08-01 00:01:00.000000',"
                "start_at='2026-08-01 00:00:00.000000',end_at=NULL",
                f"UPDATE banner_occurrence SET start_at='2098-01-01 00:00:00.000000',"
                f"end_at='2098-01-02 00:00:00.000000' WHERE uuid=UNHEX(REPLACE('{FIXTURES['scheduled']['uuid']}','-',''))",
                "UPDATE banner_version SET effective_at='2026-08-01 00:01:00.000000',"
                "start_at='2026-08-01 00:00:00.000000',end_at=NULL",
                f"UPDATE banner_version SET start_at='2098-01-01 00:00:00.000000',"
                f"end_at='2098-01-02 00:00:00.000000' WHERE banner_uuid=UNHEX(REPLACE('{FIXTURES['scheduled']['uuid']}','-',''))",
                "SET FOREIGN_KEY_CHECKS=1",
            ]
        )
        self.mysql_execute(";\n".join(statements) + ";")

    def prepare_cell(self, backend_generation):
        self.start_mysql()
        self.start_backend("final")
        self.seed_fixture()
        if backend_generation == "old":
            self.start_backend("old")

    def cell_final_backend_old_frontend(self):
        cell = "final-backend-old-frontend"
        self.prepare_cell("final")
        self.start_frontend("old")
        browser = self.run_browser(
            cell,
            "/login",
            "/picsure/operations/banners/active",
            200,
            ["all"],
            forbidden_feed="/picsure/operations/banners/active/v2",
        )
        return self.observation(cell, browser)

    def cell_final_backend_final_frontend(self):
        cell = "final-backend-final-frontend"
        self.prepare_cell("final")
        self.start_frontend("final")
        browser = self.run_browser(
            cell,
            "/login",
            "/picsure/operations/banners/active/v2",
            200,
            ["all", "login"],
            forbidden_feed="/picsure/operations/banners/active",
            expected_feed_names=["all", "login", "not-login"],
        )
        return self.observation(cell, browser)

    def cell_old_backend_final_frontend(self):
        cell = "old-backend-final-frontend"
        self.prepare_cell("old")
        self.start_frontend("final")
        browser = self.run_browser(
            cell,
            "/login",
            "/picsure/operations/banners/active/v2",
            401,
            [],
            forbidden_feed="/picsure/operations/banners/active",
            expect_region=False,
            expected_feed_names=None,
        )
        return self.observation(cell, browser)

    def cell_old_backend_old_frontend_unsafe(self):
        cell = "old-backend-old-frontend-unsafe"
        self.prepare_cell("old")
        self.start_frontend("old")
        browser = self.run_browser(
            cell,
            "/not-login",
            "/picsure/operations/banners/active",
            200,
            ["all", "login", "not-login"],
            forbidden_feed="/picsure/operations/banners/active/v2",
            expected_feed_names=["all", "login", "not-login"],
        )
        return self.observation(cell, browser)

    def cell_supported_rollback_sequence(self):
        cell = "supported-rollback-sequence"
        cell_dir = self.temp_root / cell
        cell_dir.mkdir(parents=True, exist_ok=True)
        self.prepare_cell("final")
        self.start_frontend("final")
        initial = self.run_browser(
            cell + "-initial",
            "/login",
            "/picsure/operations/banners/active/v2",
            200,
            ["all", "login"],
            forbidden_feed="/picsure/operations/banners/active",
            expected_feed_names=["all", "login", "not-login"],
        )

        events = ["FREEZE_BANNER_MANAGEMENT_WRITES"]
        rejected_gate = False
        try:
            self.require_backend_rollback_allowed()
        except contract.ContractError as error:
            if "targeted banners remain Active or Scheduled" not in str(error):
                raise
            rejected_gate = True
        if not rejected_gate:
            raise contract.ContractError("backend rollback gate did not reject active targeted banners")

        self.start_frontend("old")
        events.append("ROLL_BACK_FRONTEND")
        frontend_rollback = self.run_browser(
            cell + "-frontend-first",
            "/login",
            "/picsure/operations/banners/active",
            200,
            ["all"],
            forbidden_feed="/picsure/operations/banners/active/v2",
            expected_feed_names=["all"],
        )

        management = self.json_request("GET", self.operations_url + "/banners", headers=ADMIN_HEADERS)
        targeted = [item for item in management if item["pageTargets"] != [{"kind": "ALL"}]]
        to_disable = [item for item in targeted if item["lifecycle"] in {"ACTIVE", "SCHEDULED"}]
        expected_targeted_ids = {FIXTURES[name]["uuid"] for name in ("login", "not-login", "scheduled")}
        if {item["uuid"] for item in to_disable} != expected_targeted_ids:
            raise contract.ContractError(
                f"rollback disable set drift: {[item['uuid'] for item in to_disable]}"
            )
        for item in to_disable:
            disabled = self.json_request(
                "POST", self.operations_url + f"/banners/{item['uuid']}/disable",
                expected=200,
                headers=ADMIN_HEADERS,
            )
            if disabled["status"] != "DISABLED":
                raise contract.ContractError(f"rollback disable did not return DISABLED for {item['uuid']}")
        events.append("DISABLE_ACTIVE_AND_SCHEDULED_TARGETED_BANNERS_BEFORE_LEGACY_ACTIVE_FEED_BACKEND")

        self.require_backend_rollback_allowed()
        final_legacy = self.json_request("GET", self.gateway_url + "/operations/banners/active")
        final_v2 = self.json_request("GET", self.gateway_url + "/operations/banners/active/v2")
        expected_retained = [FIXTURES["all"]["uuid"]]
        if [item["uuid"] for item in final_legacy] != expected_retained or [item["uuid"] for item in final_v2] != expected_retained:
            raise contract.ContractError("final feeds did not retain only the deliberate All-pages banner after disable")

        self.stop_frontend()
        self.start_backend("old")
        events.append("ROLL_BACK_OPERATIONS_AND_GATEWAY")
        events.append("KEEP_BANNER_MANAGEMENT_WRITES_FROZEN_BELOW_TARGETING_CAPABLE_BACKEND")
        self.start_frontend("old")
        final_browser = self.run_browser(
            cell + "-final",
            "/not-login",
            "/picsure/operations/banners/active",
            200,
            ["all"],
            forbidden_feed="/picsure/operations/banners/active/v2",
            expected_feed_names=["all"],
        )
        database_state = self.database_lifecycle_state()
        expected_state = [
            [FIXTURES["all"]["uuid"], "PUBLISHED", "ALL"],
            [FIXTURES["login"]["uuid"], "DISABLED", "EXACT"],
            [FIXTURES["not-login"]["uuid"], "DISABLED", "EXACT"],
            [FIXTURES["scheduled"]["uuid"], "DISABLED", "SUBTREE"],
        ]
        if database_state != expected_state:
            raise contract.ContractError(f"rollback database lifecycle drift: {database_state}")

        rollback = {
            "initial": initial,
            "frontendRollback": frontend_rollback,
            "final": final_browser,
            "backendGateRejectedBeforeDisable": rejected_gate,
            "events": events,
            "databaseState": database_state,
            "managementWritesFrozenBelowTargetingBackend": True,
            "allPagesOutageChoice": "RETAIN_ALL_PAGES",
        }
        (cell_dir / "rollback-order.json").write_text(
            json.dumps(rollback, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        return self.observation(cell, rollback)

    def require_backend_rollback_allowed(self):
        management = self.json_request("GET", self.operations_url + "/banners", headers=ADMIN_HEADERS)
        blocking = [
            item["uuid"]
            for item in management
            if item["pageTargets"] != [{"kind": "ALL"}] and item["lifecycle"] in {"ACTIVE", "SCHEDULED"}
        ]
        if blocking:
            raise contract.ContractError(
                "targeted banners remain Active or Scheduled; backend targeting boundary crossing is forbidden: "
                + ",".join(sorted(blocking))
            )

    def run_browser(
        self,
        label,
        browser_path,
        expected_feed_path,
        expected_status,
        expected_rendered_names,
        *,
        forbidden_feed,
        expect_region=True,
        expected_feed_names=None,
    ):
        cell_dir = self.temp_root / label
        cell_dir.mkdir(parents=True, exist_ok=True)
        config = {
            "browserPath": browser_path,
            "expectedFeedPath": expected_feed_path,
            "expectedStatus": expected_status,
            "markerUniverse": [FIXTURES[name]["title"] for name in FIXTURES],
        }
        config_path = cell_dir / "browser-config.json"
        result_path = cell_dir / "browser-result.json"
        config_path.write_text(json.dumps(config, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        container_name = f"banner-feed-compat-{self.run_id}-browser-{hashlib.sha256(label.encode()).hexdigest()[:10]}"
        self.command(
            [
                "docker", "run", "--rm", "--name", container_name, "--label", self.label,
                "--network", self.network, "--ipc", "host",
                "-v", f"{cell_dir}:/results", self.browser_probe_image,
                "/results/browser-config.json", "/results/browser-result.json",
            ],
            timeout=120,
        )
        if not result_path.is_file():
            raise contract.ContractError(f"browser probe did not write {result_path}")
        result = json.loads(result_path.read_text(encoding="utf-8"))
        requests = result.get("requestedFeedUrls", [])
        if expected_feed_path not in requests or forbidden_feed in requests:
            raise contract.ContractError(
                f"browser feed request drift for {label}: expected={expected_feed_path}, forbidden={forbidden_feed}, got={requests}"
            )
        responses = [item for item in result.get("feedResponses", []) if item.get("url") == expected_feed_path]
        if not responses or responses[0].get("status") != expected_status:
            raise contract.ContractError(
                f"browser feed status drift for {label}: expected HTTP {expected_status}, got={responses}"
            )
        expected_markers = [FIXTURES[name]["title"] for name in expected_rendered_names]
        if result.get("renderedMarkers") != expected_markers or result.get("regionPresent") is not expect_region:
            raise contract.ContractError(
                f"browser render drift for {label}: expected markers={expected_markers}, region={expect_region}, got={result}"
            )
        if result.get("retriesDisabled") is not True:
            raise contract.ContractError("browser probe did not record retriesDisabled=true")
        if expected_feed_names is not None:
            try:
                payload = json.loads(responses[0]["body"])
            except (KeyError, json.JSONDecodeError) as error:
                raise contract.ContractError(f"browser feed returned invalid JSON for {label}: {responses[0]}") from error
            expected_ids = [FIXTURES[name]["uuid"] for name in expected_feed_names]
            actual_ids = [item["uuid"] for item in payload]
            if actual_ids != expected_ids:
                raise contract.ContractError(
                    f"browser feed membership/order drift for {label}: expected={expected_ids}, got={actual_ids}"
                )
            typed_targets = [item["pageTargets"] for item in payload]
            if expected_feed_path.endswith("/v2") and expected_feed_names:
                expected_targets = [FIXTURES[name]["targets"] for name in expected_feed_names]
                if typed_targets != expected_targets:
                    raise contract.ContractError(
                        f"v2 typed target drift for {label}: expected={expected_targets}, got={typed_targets}"
                    )
        return result

    def database_lifecycle_state(self):
        rows = self.mysql_query(
            "SELECT BIN_TO_UUID(uuid),status,JSON_UNQUOTE(JSON_EXTRACT(page_targets,'$[0].kind')) "
            "FROM banner_occurrence ORDER BY priority"
        ).splitlines()
        return [row.split("\t") for row in rows]

    def observation(self, cell, detail):
        expected = self.expected_by_cell[cell]
        row = dict(expected)
        row["observed_sha256"] = contract.semantic_sha256(detail)
        return row

    def require_observation_matches(self, observation):
        expected = self.expected_by_cell[observation["cell"]]
        if observation != expected:
            differences = [
                f"{key}: expected {expected[key]!r}, got {observation[key]!r}"
                for key in contract.MATRIX_HEADER
                if observation[key] != expected[key]
            ]
            raise contract.ContractError(
                f"matrix drift for {observation['cell']}: " + "; ".join(differences)
            )

    def run_ticket17_composition(self):
        matrix_checksum = contract.sha256_file(self.ticket17_dir / "matrix.tsv")
        if matrix_checksum != TICKET17_MATRIX_SHA256:
            raise contract.ContractError("Ticket 17 matrix changed before composition")
        environment = os.environ.copy()
        environment.update(
            {
                "OPERATIONS_COMPAT_SOURCE_ROOT": str(self.operations_source),
                "AIO_PROOF_SOURCE_ROOT": str(self.aio_source),
                "BDC_PROOF_SOURCE_ROOT": str(self.bdc_source),
                "PYTHONDONTWRITEBYTECODE": "1",
            }
        )
        if "BANNER_FEED_M2_ROOT" in os.environ:
            environment["COMPAT_M2_ROOT"] = os.environ["BANNER_FEED_M2_ROOT"]
        result = self.command(
            [self.ticket17_dir / "test.sh", "all"],
            check=False,
            capture=True,
            merge_stderr=True,
            timeout=7200,
            env=environment,
        )
        (self.temp_root / "ticket17.log").write_text(result.stdout, encoding="utf-8")
        ticket17_result = {
            "command": "tests/operations-binary-compatibility/test.sh all",
            "matrixSha256": matrix_checksum,
            "sourceHead": FINAL_BACKEND_COMMIT,
            "status": result.returncode,
            "passed": result.returncode == 0,
        }
        (self.temp_root / "ticket17-result.json").write_text(
            json.dumps(ticket17_result, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        if result.returncode != 0:
            raise contract.ContractError(
                f"Ticket 17 authoritative all entrypoint failed with status {result.returncode}; see ticket17.log"
            )

    def write_provenance(self):
        provenance = {
            "sourcePins": {
                "oldBackend": {"commit": OLD_BACKEND_COMMIT, "tree": BACKEND_TREES["old"]},
                "finalBackend": {"commit": FINAL_BACKEND_COMMIT, "tree": BACKEND_TREES["final"]},
                "oldFrontend": {"commit": OLD_FRONTEND_COMMIT, "tree": FRONTEND_TREES["old"]},
                "finalFrontend": {"commit": FINAL_FRONTEND_COMMIT, "tree": FRONTEND_TREES["final"]},
                "aioProof": AIO_COMMIT,
                "bdcAimProof": BDC_COMMIT,
            },
            "jarSha256": {
                f"{generation}-{service}": checksum
                for (generation, service), checksum in sorted(self.jar_sha256.items())
            },
            "frontend": {
                "productionDockerfileSha256": FRONTEND_DOCKERFILE_SHA256,
                "lockfileSha256": FRONTEND_LOCKFILE_SHA256,
                "generatedEnvSha256": ENV_INPUT_SHA256,
                "generatedVhostSha256": VHOST_INPUT_SHA256,
                "imageIds": self.frontend_image_ids,
                "nodeVersion": FRONTEND_NODE_VERSION,
            },
            "images": {
                "mysql": MYSQL_IMAGE,
                "flyway": FLYWAY_IMAGE,
                "javaBuild": BUILD_IMAGE,
                "javaRuntime": RUNTIME_IMAGE,
                "chromiumPlaywright": BROWSER_IMAGE,
                "frontendNodeBuilder": FRONTEND_NODE_IMAGE,
                "frontendHttpdRuntime": FRONTEND_HTTPD_IMAGE,
            },
            "ticket17MatrixSha256": TICKET17_MATRIX_SHA256,
            "rolloutContractBeforeTicket18Sha256": ROLLOUT_CONTRACT_BEFORE_TICKET18_SHA256,
            "deploymentEngineParityClaimed": False,
            "syntheticDataOnly": True,
        }
        (self.temp_root / "provenance.json").write_text(
            json.dumps(provenance, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )

    def cleanup_cell_resources(self):
        errors = []
        for label, cleanup in (
            ("stop_frontend", self.stop_frontend),
            ("stop_backend", self.stop_backend),
            ("stop_mysql", self.stop_mysql),
        ):
            try:
                cleanup()
            except Exception as error:
                errors.append(f"{label}: {error}")
        return errors

    def write_failure_diagnostics(self, observed_path, failed_cell, error, cleanup_errors):
        try:
            contract.write_observed_matrix(observed_path, self.observations)
            detail = {
                "failed_cell": failed_cell,
                "completed_cells": [row["cell"] for row in self.observations],
                "error_type": type(error).__name__,
                "error": str(error),
                "cleanup_errors": cleanup_errors,
            }
            (self.temp_root / "failed-cell.json").write_text(
                json.dumps(detail, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
        except Exception as diagnostic_error:
            print(f"Could not write Ticket 18 failure diagnostics: {diagnostic_error}", file=sys.stderr)

    def stop_frontend(self):
        if self.frontend_container:
            name = self.frontend_container
            self.capture_logs(name)
            self.command(["docker", "container", "rm", "--force", name], check=False, capture=True)
            self.frontend_container = None
            self.frontend_url = None

    def stop_backend(self):
        if self.gateway_container:
            name = self.gateway_container
            self.capture_logs(name)
            self.command(["docker", "container", "rm", "--force", name], check=False, capture=True)
            self.gateway_container = None
            self.gateway_url = None
        if self.operations_container:
            name = self.operations_container
            self.capture_logs(name)
            self.command(["docker", "container", "rm", "--force", name], check=False, capture=True)
            self.operations_container = None
            self.operations_url = None

    def stop_mysql(self):
        if self.mysql_container:
            name = self.mysql_container
            self.capture_logs(name)
            self.command(["docker", "container", "rm", "--force", name], check=False, capture=True)
            self.mysql_container = None

    def capture_logs(self, container):
        result = self.command(
            ["docker", "logs", container],
            check=False,
            capture=True,
            merge_stderr=True,
        )
        log_path = self.temp_root / "logs" / f"{container}.log"
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_path.write_text(result.stdout, encoding="utf-8")

    def mysql_query(self, sql):
        if not self.mysql_container:
            raise contract.ContractError("MySQL is not running")
        result = self.command(
            [
                "docker", "exec", "-e", f"MYSQL_PWD={SYNTHETIC_ROOT_PASSWORD}", self.mysql_container,
                "mysql", "-N", "-B", "-r", "-h127.0.0.1", "-uroot", "picsure", "-e", sql,
            ],
            capture=True,
        )
        return result.stdout.rstrip("\n")

    def mysql_execute(self, sql):
        self.mysql_query(sql)

    @staticmethod
    def http(method, url, payload=None, headers=None):
        data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(url, data=data, method=method, headers=headers or {})
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return response.status, response.read().decode("utf-8")
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode("utf-8")
        except (urllib.error.URLError, TimeoutError, http.client.RemoteDisconnected):
            return 0, "connection unavailable"

    def json_request(self, method, url, payload=None, expected=200, headers=None):
        code, body = self.http(method, url, payload, headers)
        if code != expected:
            raise contract.ContractError(f"{method} {url} expected HTTP {expected}, got {code}: {body}")
        try:
            return json.loads(body)
        except json.JSONDecodeError as error:
            raise contract.ContractError(f"{method} {url} returned invalid JSON: {error}") from error

    @staticmethod
    def command(
        args,
        check=True,
        capture=False,
        merge_stderr=False,
        timeout=DEFAULT_COMMAND_TIMEOUT_SECONDS,
        env=None,
    ):
        command = [str(value) for value in args]
        stderr = subprocess.STDOUT if merge_stderr else (subprocess.PIPE if capture else None)
        try:
            result = subprocess.run(
                command,
                check=False,
                stdout=subprocess.PIPE if capture else None,
                stderr=stderr,
                text=True,
                timeout=timeout,
                env=env,
            )
        except subprocess.TimeoutExpired as error:
            raise contract.ContractError(
                f"command timed out after {timeout} seconds: {' '.join(command)}"
            ) from error
        if check and result.returncode != 0:
            stdout = result.stdout.strip() if result.stdout else ""
            stderr_text = "" if merge_stderr or not result.stderr else result.stderr.strip()
            detail = "\n".join(value for value in (stdout, stderr_text) if value)
            raise contract.ContractError(
                f"command failed ({result.returncode}): {' '.join(command)}\n{detail}"
            )
        return result


def main():
    if len(sys.argv) != 4:
        raise contract.ContractError("usage: run.py <repository-root> <temporary-root> <all|cell>")
    repository_root, temp_root, selection = sys.argv[1:]
    if selection != "all" and selection not in contract.REQUIRED_CELLS:
        raise contract.ContractError("selection must be all or one of: " + ", ".join(contract.REQUIRED_CELLS))
    run_id = Path(temp_root).name.removeprefix("banner-feed-")[:32]
    harness = Harness(repository_root, temp_root, run_id, selection)

    def stop(_signum, _frame):
        harness.cleanup_cell_resources()
        raise KeyboardInterrupt

    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)
    harness.run()


if __name__ == "__main__":
    try:
        main()
    except contract.ContractError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1) from error

#!/usr/bin/env python3

import concurrent.futures
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


PRE_VERSION_COMMIT = "97d772913aa147207f9ddcf16f8c2cfdf5ede646"
PRE_ALLOCATOR_COMMIT = "e9e457cd285e185bdfb78d54b166fe5ded161335"
FINAL_COMMIT = "4fbeb285cd584ff993ce93d3830e85aad1a14490"
AIO_COMMIT = "05b1a77512dc0921570f0d442853fdcee75b8131"
BDC_COMMIT = "5d2ba9f59f161ace5e807c82a0580518a9d44d16"

OPERATIONS_URL = "https://github.com/hms-dbmi/pic-sure.git"
AIO_URL = "https://github.com/hms-dbmi/PIC-SURE-Migrations.git"
BDC_URL = "https://github.com/hms-dbmi/pic-sure-bdc-infrastructure.git"

MYSQL_IMAGE = "mysql:8.0.43@sha256:ccf4fed7ff4b886aeb3573a1f5d5b509525ecff55a2d1e2653c27a5abdded309"
FLYWAY_IMAGE = "flyway/flyway:11.7.2@sha256:8ace7d9825bb3ad1d6e14ee27b3a830b638ac841ba424b99b2d92aa65a99d484"
BUILD_IMAGE = "maven:3-amazoncorretto-25@sha256:de7a3e517efac1b933af6ceb375974a061ba71c908ea51a18bd937716a8ade93"
RUNTIME_IMAGE = "amazoncorretto:25@sha256:397edfaaa0fdfc95001d4c4a4ab82174073277a5d630fd9375c94dca25b5991d"
AUDIT_PROBE_IMAGE = "busybox@sha256:dc2d74b28e4cf8984fa52af1f39bc7c3d9c73760b41a74d629f5d11b1ab28616"
JDK_RUNTIME = 'openjdk version "25.0.4" 2026-07-21 LTS'
DEFAULT_COMMAND_TIMEOUT_SECONDS = 300
BUILD_COMMAND_TIMEOUT_SECONDS = 900
GIT_ARCHIVE_TIMEOUT_SECONDS = 60

SYNTHETIC_ROOT_PASSWORD = "operations-banner-proof"
ADMIN_HEADERS = {
    "Content-Type": "application/json",
    "X-User-Id": "compat-admin",
    "X-User-Privileges": "ADMIN",
}

GENERATIONS = {
    "pre-version": PRE_VERSION_COMMIT,
    "pre-allocator": PRE_ALLOCATOR_COMMIT,
    "final": FINAL_COMMIT,
}

SOURCE_TREES = {
    "pre-version": "4ffedcfefdaffa9601b5353678117be27a93744b",
    "pre-allocator": "c5d7eb378b3eec01392506e40e3498f60a85610e",
    "final": "e1a40c4253ee1bd80bfbce2046c7fe98e2c0ab5f",
}

EXPECTED_BINARY_CHECKSUMS = {
    "pre-version": "3be4a3e80743d8089b7f9115b2381db78d7861d6e4f4b58cfcadde5f9d9ea712",
    "pre-allocator": "cdd40e6a26a074c9d89e10eb0a1a576c297b6281f4b919ce94d9dfee5d39832e",
    "final": "a3ab20886ba666b01cd97aaa1f250e414f139b2809006e68df82ea3b0f784ddf",
}

AIO_FEATURE_PATHS = [
    "Baseline/auth/V6__ADD_BANNER_MANAGEMENT_ACCESS_RULE.sql",
    "Baseline/auth/V7__EXPAND_BANNER_MANAGEMENT_ACCESS_RULE.sql",
    "Baseline/auth/V8__AUTHORIZE_BANNER_REORDER.sql",
    "Baseline/auth/V9__ALLOW_BANNER_DISABLE_ROUTE.sql",
    "Baseline/auth/V10__ALLOW_BANNER_ARCHIVE_ROUTE.sql",
    "Baseline/auth/V11__ALLOW_BANNER_RESTORE_ROUTE.sql",
    "Baseline/picsure/V10__CREATE_BANNER_OCCURRENCE.sql",
    "Baseline/picsure/V11__CREATE_BANNER_VERSION.sql",
    "Baseline/picsure/V12__CREATE_BANNER_PRIORITY_ALLOCATOR.sql",
]

BDC_FEATURE_PATHS = [
    f"app-infrastructure/db/{tenant}/{section}/{filename}"
    for tenant in ("bdc", "aim-ahead")
    for section, filename in (
        ("auth", "V22__Add_Banner_Management_Access_Rule.sql") if tenant == "bdc" else ("auth", "V24__Add_Banner_Management_Access_Rule.sql"),
        ("auth", "V23__Expand_Banner_Management_Access_Rule.sql") if tenant == "bdc" else ("auth", "V25__Expand_Banner_Management_Access_Rule.sql"),
        ("auth", "V24__Authorize_Banner_Reorder.sql") if tenant == "bdc" else ("auth", "V26__Authorize_Banner_Reorder.sql"),
        ("auth", "V25__Allow_Banner_Disable_Route.sql") if tenant == "bdc" else ("auth", "V27__Allow_Banner_Disable_Route.sql"),
        ("auth", "V26__Allow_Banner_Archive_Route.sql") if tenant == "bdc" else ("auth", "V28__Allow_Banner_Archive_Route.sql"),
        ("auth", "V27__Allow_Banner_Restore_Route.sql") if tenant == "bdc" else ("auth", "V29__Allow_Banner_Restore_Route.sql"),
        ("picsure", "V9__CREATE_BANNER_OCCURRENCE.sql"),
        ("picsure", "V10__CREATE_BANNER_VERSION.sql"),
        ("picsure", "V11__CREATE_BANNER_PRIORITY_ALLOCATOR.sql"),
    )
]


class Harness:

    def __init__(self, repository_root, temp_root, run_id, selection):
        self.repository_root = Path(repository_root)
        self.test_dir = self.repository_root / "tests" / "operations-binary-compatibility"
        self.temp_root = Path(temp_root)
        self.run_id = run_id
        self.selection = selection
        self.label = f"org.pic-sure.operations-compatibility={run_id}"
        self.network = f"operations-compat-{run_id}"
        self.mysql_container = None
        self.audit_container = None
        self.audit_request_file = None
        self.app_containers = {}
        self.jars = {}
        self.jar_checksums = {}
        self.build_timestamps = {}
        self.migration_checksum_text = None
        self.observations = []
        self.expected_by_cell = {
            row["cell"]: row for row in contract.load_matrix(self.test_dir / "matrix.tsv")
        }
        self.operations_source = None
        self.aio_source = None
        self.bdc_source = None

    def run(self):
        self.require_tools()
        self.require_runtime_pin()
        self.prepare_sources()
        self.verify_migration_contracts()
        self.build_binaries()
        self.create_network()
        selected = contract.REQUIRED_CELLS if self.selection == "all" else [self.selection]
        for cell in selected:
            print(f"== {cell} ==", flush=True)
            observation = getattr(self, "cell_" + cell.replace("-", "_"))()
            contract.validate_observation(observation)
            self.observations.append(observation)
            self.stop_all_apps()
            self.stop_mysql()

        observed_path = self.temp_root / "observed-matrix.tsv"
        contract.write_matrix(observed_path, self.observations)
        expected = [self.expected_by_cell[cell] for cell in selected]
        contract.require_observations_match(expected, self.observations)
        print(f"Observed matrix: {observed_path}")
        print(f"PASS: {', '.join(selected)}")

    def require_tools(self):
        missing = [tool for tool in ("docker", "git") if shutil.which(tool) is None]
        if missing:
            raise contract.ContractError(f"missing required tools: {', '.join(missing)}")

    def require_runtime_pin(self):
        result = self.command(
            ["docker", "run", "--rm", RUNTIME_IMAGE, "java", "-version"],
            capture=True,
            merge_stderr=True,
        )
        first_line = result.stdout.splitlines()[0] if result.stdout.splitlines() else ""
        if first_line != JDK_RUNTIME:
            raise contract.ContractError(
                f"JDK runtime drift: expected {JDK_RUNTIME!r}, got {first_line!r}"
            )

    def prepare_sources(self):
        operations_override = os.environ.get("OPERATIONS_COMPAT_SOURCE_ROOT")
        if operations_override:
            self.operations_source = Path(operations_override).resolve()
            contract.require_clean_repository(self.operations_source, "Operations source override")
            for commit in GENERATIONS.values():
                contract.require_git_commit(self.operations_source, commit)
        else:
            self.operations_source = self.temp_root / "sources" / "operations"
            self.fetch_repository(
                OPERATIONS_URL,
                list(GENERATIONS.values()),
                self.operations_source,
                "Pinned Operations commits are not all reachable from a published ref. Integration must preserve the exact "
                "historical commits through a merge commit or durable ref; a squash/rebase merge followed by branch deletion "
                "makes this proof unavailable. Publish the prerequisite commits or set OPERATIONS_COMPAT_SOURCE_ROOT to one "
                "clean Git repository containing every exact commit.",
            )

        self.aio_source = self.prepare_exact_checkout(
            os.environ.get("AIO_PROOF_SOURCE_ROOT"),
            AIO_URL,
            AIO_COMMIT,
            self.temp_root / "sources" / "aio",
            "Ticket 15 AIO contract",
            "Publish Ticket 15 first, or set AIO_PROOF_SOURCE_ROOT to a clean checkout at its exact commit.",
        )
        self.bdc_source = self.prepare_exact_checkout(
            os.environ.get("BDC_PROOF_SOURCE_ROOT"),
            BDC_URL,
            BDC_COMMIT,
            self.temp_root / "sources" / "bdc",
            "Ticket 16 BDC/AIM-AHEAD contract",
            "Publish Ticket 16 first, or set BDC_PROOF_SOURCE_ROOT to a clean checkout at its exact commit.",
        )

    def prepare_exact_checkout(self, override, url, commit, destination, label, failure_message):
        if override:
            source = Path(override).resolve()
            contract.require_repository_head(source, label, commit)
            return source
        self.fetch_repository(url, [commit], destination, failure_message)
        contract.require_repository_head(destination, label, commit)
        return destination

    def fetch_repository(self, url, commits, destination, failure_message):
        destination.parent.mkdir(parents=True, exist_ok=True)
        self.command(["git", "init", "--quiet", str(destination)])
        self.command(["git", "-C", str(destination), "remote", "add", "origin", url])
        for commit in commits:
            result = self.command(
                ["git", "-C", str(destination), "fetch", "--quiet", "--depth", "1", "origin", commit],
                check=False,
                capture=True,
            )
            if result.returncode != 0:
                raise contract.ContractError(f"{failure_message}\nGit error: {result.stderr.strip()}")
        self.command(["git", "-C", str(destination), "checkout", "--quiet", "--detach", commits[-1]])

    def verify_migration_contracts(self):
        aio_manifest = self.aio_source / "tests" / "aio-deployment-migration" / "feature-sql.sha256"
        bdc_manifest = self.bdc_source / "tests" / "deployment-migration" / "feature-sql.sha256"
        aio_hashes = self.verify_manifest(self.aio_source, aio_manifest, AIO_FEATURE_PATHS)
        bdc_hashes = self.verify_manifest(self.bdc_source, bdc_manifest, BDC_FEATURE_PATHS)
        aio_values = [aio_hashes[path] for path in AIO_FEATURE_PATHS]
        bdc_values = [bdc_hashes[path] for path in BDC_FEATURE_PATHS]
        if bdc_values[:9] != aio_values or bdc_values[9:] != aio_values:
            raise contract.ContractError("Ticket 15 and Ticket 16 banner SQL checksums differ")
        self.migration_checksum_text = ",".join(aio_values[6:])

    def verify_manifest(self, root, manifest, expected_paths):
        if not manifest.is_file():
            raise contract.ContractError(f"missing migration checksum manifest: {manifest}")
        entries = {}
        for line_number, line in enumerate(manifest.read_text(encoding="utf-8").splitlines(), start=1):
            try:
                checksum, relative = line.split("\t", 1)
            except ValueError as error:
                raise contract.ContractError(
                    f"malformed migration checksum manifest row {line_number} in {manifest}"
                ) from error
            entries[relative] = checksum
        if list(entries) != expected_paths:
            raise contract.ContractError(
                f"migration checksum manifest path drift in {manifest}: expected {expected_paths}, got {list(entries)}"
            )
        for relative, expected in entries.items():
            actual = contract.sha256_file(root / relative)
            if actual != expected:
                raise contract.ContractError(
                    f"migration checksum mismatch for {relative}: expected {expected}, got {actual}"
                )
        return entries

    def build_binaries(self):
        needed = {
            generation
            for cell in (contract.REQUIRED_CELLS if self.selection == "all" else [self.selection])
            for generation in self.generations_for_cell(cell)
        }
        m2 = Path(os.environ.get("COMPAT_M2_ROOT", self.temp_root / "m2")).resolve()
        m2.mkdir(parents=True, exist_ok=True)
        for generation in GENERATIONS:
            if generation not in needed:
                continue
            commit = GENERATIONS[generation]
            source_tree = self.command(
                ["git", "-C", str(self.operations_source), "rev-parse", f"{commit}^{{tree}}"],
                capture=True,
            ).stdout.strip()
            if source_tree != SOURCE_TREES[generation]:
                raise contract.ContractError(
                    f"{generation} source tree mismatch: expected {SOURCE_TREES[generation]}, got {source_tree}"
                )
            export = self.temp_root / "exports" / generation
            export.mkdir(parents=True, exist_ok=True)
            archive = self.temp_root / f"{generation}.tar"
            with archive.open("wb") as handle:
                try:
                    result = subprocess.run(
                        ["git", "-C", self.operations_source, "archive", commit],
                        check=False,
                        stdout=handle,
                        stderr=subprocess.PIPE,
                        timeout=GIT_ARCHIVE_TIMEOUT_SECONDS,
                    )
                except subprocess.TimeoutExpired as error:
                    raise contract.ContractError(
                        f"Operations Git archive timed out after {GIT_ARCHIVE_TIMEOUT_SECONDS} seconds for {commit}"
                    ) from error
            if result.returncode != 0:
                raise contract.ContractError(
                    f"could not export Operations {commit}: {result.stderr.decode('utf-8', errors='replace')}"
                )
            with tarfile.open(archive) as tar:
                for member in tar.getmembers():
                    destination = (export / member.name).resolve()
                    if export.resolve() not in destination.parents and destination != export.resolve():
                        raise contract.ContractError(f"unsafe path in Git archive: {member.name}")
                tar.extractall(export)
            timestamp = self.command(
                ["git", "-C", str(self.operations_source), "show", "-s", "--format=%cI", commit],
                capture=True,
            ).stdout.strip()
            uid = str(os.getuid())
            gid = str(os.getgid())
            self.command(
                [
                    "docker", "run", "--rm", "--label", self.label, "--user", f"{uid}:{gid}",
                    "-e", "HOME=/tmp/compat-home", "-e", "MAVEN_CONFIG=/m2", "-v", f"{export}:/source",
                    "-v", f"{m2}:/m2", "-w", "/source", BUILD_IMAGE,
                    "mvn", "-q", "-B", "-Dmaven.repo.local=/m2",
                    f"-Dproject.build.outputTimestamp={timestamp}", "-DskipTests",
                    "-pl", "services/pic-sure-operations-service", "-am", "package",
                ],
                timeout=BUILD_COMMAND_TIMEOUT_SECONDS,
            )
            jar = export / "services" / "pic-sure-operations-service" / "target" / "pic-sure-operations-service-3.0.0.jar"
            if not jar.is_file():
                raise contract.ContractError(f"missing built {generation} jar: {jar}")
            checksum = contract.sha256_file(jar)
            contract.require_checksum(generation, EXPECTED_BINARY_CHECKSUMS[generation], checksum)
            self.jars[generation] = jar
            self.jar_checksums[generation] = checksum
            self.build_timestamps[generation] = timestamp
            print(f"{generation} {commit} {source_tree} {timestamp} {checksum}")

    @staticmethod
    def generations_for_cell(cell):
        return {
            "lazy-version-recovery": {"pre-version", "final"},
            "allocator-recovery": {"pre-allocator", "final"},
            "overlapping-writers": {"pre-allocator", "final"},
            "rollback-pre-version": {"pre-version", "final"},
            "rollback-pre-allocator": {"pre-allocator", "final"},
            "final-http-contract": {"final"},
            "occurrence-only-rejection": {"final"},
        }[cell]

    def create_network(self):
        self.command(["docker", "network", "create", "--label", self.label, self.network])

    def start_mysql(self, schema):
        self.stop_mysql()
        name = f"operations-compat-{self.run_id}-mysql"
        self.command(
            [
                "docker", "run", "-d", "--name", name, "--label", self.label,
                "--network", self.network, "--network-alias", "mysql",
                "-e", f"MYSQL_ROOT_PASSWORD={SYNTHETIC_ROOT_PASSWORD}",
                "-e", "MYSQL_DATABASE=picsure", MYSQL_IMAGE,
            ]
        )
        self.mysql_container = name
        for _ in range(90):
            result = self.command(
                [
                    "docker", "exec", "-e", f"MYSQL_PWD={SYNTHETIC_ROOT_PASSWORD}", name,
                    "mysqladmin", "ping", "-h127.0.0.1", "-uroot", "--silent",
                ],
                check=False,
                capture=True,
            )
            if result.returncode == 0:
                break
            time.sleep(1)
        else:
            raise contract.ContractError("MySQL did not become ready within 90 seconds")
        self.apply_schema(schema)

    def apply_schema(self, schema):
        migration_dir = self.temp_root / "migration-cells" / schema
        migration_dir.mkdir(parents=True, exist_ok=True)
        names = ["V10__CREATE_BANNER_OCCURRENCE.sql"]
        if schema == "forward":
            names.extend(["V11__CREATE_BANNER_VERSION.sql", "V12__CREATE_BANNER_PRIORITY_ALLOCATOR.sql"])
        for name in names:
            shutil.copyfile(self.aio_source / "Baseline" / "picsure" / name, migration_dir / name)
        self.command(
            [
                "docker", "run", "--rm", "--label", self.label, "--network", self.network,
                "-v", f"{migration_dir}:/flyway/sql:ro", FLYWAY_IMAGE,
                "-url=jdbc:mysql://mysql:3306/picsure", "-user=root",
                f"-password={SYNTHETIC_ROOT_PASSWORD}", "-connectRetries=30", "migrate",
            ]
        )
        tables = set(
            self.mysql_query(
                "SELECT table_name FROM information_schema.tables "
                "WHERE table_schema='picsure' AND table_name LIKE 'banner_%' ORDER BY table_name"
            ).splitlines()
        )
        if schema == "forward":
            contract.require_forward_schema(tables)
        elif tables != {"banner_occurrence"}:
            raise contract.ContractError(f"occurrence-only schema drift: {sorted(tables)}")

    def start_app(self, generation, suffix=None):
        suffix = suffix or generation
        name = f"operations-compat-{self.run_id}-{suffix}"
        jar = self.jars[generation]
        arguments = [
            "docker", "run", "-d", "--name", name, "--label", self.label,
            "--network", self.network, "-p", "127.0.0.1::8080",
            "-e", "SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/picsure?serverTimezone=UTC",
            "-e", "SPRING_DATASOURCE_USERNAME=root",
            "-e", f"SPRING_DATASOURCE_PASSWORD={SYNTHETIC_ROOT_PASSWORD}",
            "-e", "PICSURE_ACTUATOR_EXPOSURE=health",
            "-e", "PICSURE_ACTUATOR_REQUIRE_TOKEN=false",
        ]
        if self.audit_container:
            arguments.extend(
                [
                    "-e", "LOGGING_SERVICE_URL=http://audit:8081/cgi-bin",
                    "-e", "LOGGING_API_KEY=synthetic-audit-key",
                ]
            )
        arguments.extend(
            [
                "-v", f"{jar}:/application.jar:ro", RUNTIME_IMAGE,
                "java", "-jar", "/application.jar", "--logging.level.root=WARN",
                "--logging.level.edu.harvard.dbmi.avillach.logging=INFO",
            ]
        )
        self.command(arguments)
        self.app_containers[suffix] = name
        port_text = self.command(["docker", "port", name, "8080/tcp"], capture=True).stdout.strip()
        port = port_text.rsplit(":", 1)[1]
        base_url = f"http://127.0.0.1:{port}/operations"
        last_error = "not attempted"
        for _ in range(90):
            code, body = self.http("GET", base_url + "/actuator/health")
            if code == 200:
                return base_url
            last_error = f"HTTP {code}: {body}"
            running = self.command(
                ["docker", "inspect", "-f", "{{.State.Running}}", name],
                check=False,
                capture=True,
            )
            if running.returncode != 0 or running.stdout.strip() != "true":
                self.capture_logs(name)
                raise contract.ContractError(f"{generation} exited before readiness")
            time.sleep(1)
        self.capture_logs(name)
        raise contract.ContractError(f"{generation} did not become ready: {last_error}")

    def stop_app(self, suffix):
        name = self.app_containers.pop(suffix, None)
        if name:
            self.capture_logs(name)
            self.command(["docker", "container", "rm", "--force", name], check=False, capture=True)

    def stop_all_apps(self):
        for suffix in list(self.app_containers):
            self.stop_app(suffix)
        self.stop_audit_probe()

    def start_audit_probe(self):
        audit_root = self.temp_root / "audit-probe"
        script = audit_root / "cgi-bin" / "audit"
        script.parent.mkdir(parents=True, exist_ok=True)
        script.write_text(
            "#!/bin/sh\ncat >> /www/requests\nprintf 'Status: 204 No Content\\r\\n\\r\\n'\n",
            encoding="utf-8",
        )
        script.chmod(0o755)
        self.audit_request_file = self.prepare_audit_request_file(audit_root)
        name = f"operations-compat-{self.run_id}-audit"
        self.command(
            [
                "docker", "run", "-d", "--name", name, "--label", self.label,
                "--network", self.network, "--network-alias", "audit",
                "-v", f"{audit_root}:/www", AUDIT_PROBE_IMAGE,
                "httpd", "-f", "-p", "8081", "-h", "/www",
            ]
        )
        self.audit_container = name
        for _ in range(20):
            result = self.command(
                [
                    "docker", "exec", name, "wget", "-qO-", "--post-data=control",
                    "http://127.0.0.1:8081/cgi-bin/audit",
                ],
                check=False,
                capture=True,
            )
            if result.returncode == 0 and self.audit_request_file.read_text(encoding="utf-8") == "control":
                self.audit_request_file.write_text("", encoding="utf-8")
                break
            time.sleep(0.1)
        else:
            raise contract.ContractError("synthetic audit probe did not accept its control request")

    @staticmethod
    def prepare_audit_request_file(audit_root):
        request_file = Path(audit_root) / "requests"
        request_file.write_text("", encoding="utf-8")
        request_file.chmod(0o666)
        return request_file

    def stop_audit_probe(self):
        if self.audit_container:
            self.command(
                ["docker", "container", "rm", "--force", self.audit_container],
                check=False,
                capture=True,
            )
            self.audit_container = None

    def stop_mysql(self):
        if self.mysql_container:
            self.command(
                ["docker", "container", "rm", "--force", self.mysql_container],
                check=False,
                capture=True,
            )
            self.mysql_container = None

    def capture_logs(self, container):
        result = self.command(["docker", "logs", container], check=False, capture=True, merge_stderr=True)
        (self.temp_root / f"{container}.log").write_text(result.stdout, encoding="utf-8")

    def mysql_query(self, sql):
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

    def raw_banner_checksum(self):
        result = self.command(
            [
                "docker", "exec", "-e", f"MYSQL_PWD={SYNTHETIC_ROOT_PASSWORD}", self.mysql_container,
                "mysqldump", "-h127.0.0.1", "-uroot", "--skip-comments", "--compact",
                "--no-create-info", "--hex-blob", "--skip-extended-insert", "--order-by-primary",
                "picsure", "banner_occurrence", "banner_version",
            ],
            capture=True,
        )
        return hashlib.sha256(result.stdout.encode("utf-8")).hexdigest()

    @staticmethod
    def semantic_checksum(value):
        data = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        return hashlib.sha256(data).hexdigest()

    @staticmethod
    def http(method, url, payload=None, headers=None):
        data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(url, data=data, method=method, headers=headers or {})
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
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
    def payload(html, title, *, audience="EVERYONE", targets=None, start=None, end=None, dismissible=True):
        return {
            "htmlContent": html,
            "title": title,
            "appearance": "PRIMARY",
            "icon": "INFORMATION",
            "dismissible": dismissible,
            "audience": audience,
            "placement": "SITE_TOP",
            "pageTargets": targets or [{"kind": "ALL"}],
            "startAt": start,
            "endAt": end,
        }

    def publish(self, base_url, payload, expected=201):
        return self.json_request("POST", base_url + "/banners", payload, expected, ADMIN_HEADERS)

    def observation(self, cell, generation, result, checksum, boundary, failure_mode, schema="forward"):
        generations = generation.split("->") if "->" in generation else generation.split("+")
        generations = [item for item in generations if item in GENERATIONS]
        binary_commits = ",".join(f"{item}@{GENERATIONS[item]}" for item in generations)
        binary_source_trees = ",".join(f"{item}@{SOURCE_TREES[item]}" for item in generations)
        binary_checksums = ",".join(f"{item}@{self.jar_checksums[item]}" for item in generations)
        build_timestamps = ",".join(f"{item}@{self.build_timestamps[item]}" for item in generations)
        return {
            "deployment_scope": "AIO,BDC,AIM-AHEAD",
            "cell": cell,
            "binary_generation": generation,
            "binary_commit": binary_commits,
            "binary_source_tree": binary_source_trees,
            "binary_sha256": binary_checksums,
            "migration_source_commits": f"AIO@{AIO_COMMIT},BDC-AIM@{BDC_COMMIT}",
            "migration_sql_sha256": self.migration_checksum_text,
            "mysql_image": MYSQL_IMAGE,
            "flyway_image": FLYWAY_IMAGE,
            "build_image": BUILD_IMAGE,
            "build_output_timestamp": build_timestamps,
            "runtime_image": RUNTIME_IMAGE,
            "jdk_runtime": JDK_RUNTIME,
            "schema_cell": schema,
            "result": result,
            "preserved_data_sha256": checksum,
            "supported_boundary": boundary,
            "failure_mode": failure_mode,
        }

    def cell_lazy_version_recovery(self):
        self.start_mysql("forward")
        old = self.start_app("pre-version")
        original_payload = self.payload(
            "<p>Original bytes:  two spaces</p>", "Old publisher",
            start="2098-01-01T00:00:00Z", end="2099-01-01T00:00:00Z",
        )
        old_headers = dict(ADMIN_HEADERS)
        old_headers["X-User-Id"] = "pre-version-admin"
        original = self.json_request("POST", old + "/banners", original_payload, 201, old_headers)
        uuid = original["uuid"]
        if self.mysql_query("SELECT COUNT(*) FROM banner_version") != "0":
            raise contract.ContractError("pre-version publication unexpectedly created a version row")
        original_db = self.mysql_query(
            "SELECT HEX(html_content),IFNULL(HEX(title),''),appearance,icon,dismissible,audience,placement,"
            "CAST(page_targets AS CHAR),IFNULL(CAST(start_at AS CHAR),''),"
            "IFNULL(CAST(end_at AS CHAR),''),presentation_hash,HEX(published_by),CAST(published_at AS CHAR) "
            f"FROM banner_occurrence WHERE uuid=UNHEX(REPLACE('{uuid}','-',''))"
        ).split("\t")
        self.stop_app("pre-version")

        final = self.start_app("final")
        changed_payload = self.payload(
            "<p>Corrected bytes:  two spaces</p>",
            "Corrected",
            audience="SIGNED_IN",
            targets=[{"kind": "EXACT", "path": "/research"}],
            dismissible=False,
        )
        changed = self.json_request("PUT", final + f"/banners/{uuid}", changed_payload, 200, ADMIN_HEADERS)
        if changed["uuid"] != uuid:
            raise contract.ContractError("lazy version recovery changed the occurrence UUID")
        changed_db = self.mysql_query(
            "SELECT HEX(html_content),IFNULL(HEX(title),''),appearance,icon,dismissible,audience,placement,"
            "CAST(page_targets AS CHAR),IFNULL(CAST(start_at AS CHAR),''),"
            "IFNULL(CAST(end_at AS CHAR),''),presentation_hash,CAST(updated_at AS CHAR),HEX(updated_by) "
            f"FROM banner_occurrence WHERE uuid=UNHEX(REPLACE('{uuid}','-',''))"
        ).split("\t")
        version_rows = self.mysql_query(
            "SELECT version_number,HEX(html_content),IFNULL(HEX(title),''),appearance,icon,dismissible,audience,placement,"
            "CAST(page_targets AS CHAR),IFNULL(CAST(start_at AS CHAR),''),"
            "IFNULL(CAST(end_at AS CHAR),''),presentation_hash,CAST(effective_at AS CHAR),HEX(actor) "
            f"FROM banner_version WHERE banner_uuid=UNHEX(REPLACE('{uuid}','-','')) ORDER BY version_number"
        ).splitlines()
        if len(version_rows) != 2:
            raise contract.ContractError(f"expected lazy versions 1 and 2, got {len(version_rows)}")
        version_one = version_rows[0].split("\t")
        version_two = version_rows[1].split("\t")
        if version_one[0] != "1" or version_one[1:12] != original_db[:11]:
            raise contract.ContractError("lazy version 1 does not preserve the exact original snapshot bytes")
        if original_db[8] != original_db[12] or version_one[12] != original_db[12]:
            raise contract.ContractError("lazy version 1 does not preserve the old publication timestamp path")
        if bytes.fromhex(version_one[13]).decode() != "pre-version-admin":
            raise contract.ContractError("lazy version 1 does not preserve publisher timestamp and actor")
        if version_two[0] != "2" or version_two[1:12] != changed_db[:11]:
            raise contract.ContractError("version 2 does not preserve the exact edited occurrence bytes")
        if version_two[12] != changed_db[11] or bytes.fromhex(version_two[13]).decode() != "compat-admin":
            raise contract.ContractError("version 2 does not preserve the final update timestamp and actor")
        version_one_semantic = version_one[:12]
        version_one_semantic[9] = "old_publication_start_at"
        version_two_semantic = version_two[:12]
        version_two_semantic[9] = "old_publication_start_at"
        semantic = {
            "uuid_preserved": True,
            "version1": version_one_semantic + ["published_at", "pre-version-admin"],
            "version2": version_two_semantic + ["updated_at", "compat-admin"],
        }
        checksum = self.semantic_checksum(semantic)
        return self.observation(
            "lazy-version-recovery", "pre-version->final", "PASS", checksum, "supported",
            "single-writer handoff lazily created exact immutable versions 1 and 2",
        )

    def cell_allocator_recovery(self):
        self.start_mysql("forward")
        old = self.start_app("pre-allocator")
        first = self.publish(old, self.payload("<p>Legacy priority one</p>", "Legacy one"))
        second = self.publish(old, self.payload("<p>Legacy priority two</p>", "Legacy two"))
        old_priorities = [first["priority"], second["priority"]]
        self.stop_app("pre-allocator")
        final = self.start_app("final")
        recovered = self.publish(final, self.payload("<p>Allocator owner</p>", "Final owner"))
        priorities = [int(value) for value in self.mysql_query("SELECT priority FROM banner_occurrence ORDER BY priority").splitlines()]
        allocator = self.mysql_query("SELECT id,next_priority FROM banner_priority_allocator")
        if old_priorities != [1, 2] or priorities != [1, 2, 3] or recovered["priority"] != 3 or allocator != "1\t4":
            raise contract.ContractError(
                f"allocator recovery mismatch: old={old_priorities}, all={priorities}, recovered={recovered['priority']}, allocator={allocator}"
            )
        checksum = self.semantic_checksum({"priorities": priorities, "allocator": [1, 4]})
        return self.observation(
            "allocator-recovery", "pre-allocator->final", "PASS", checksum, "supported",
            "single-writer handoff preserved priorities and reconciled one allocator singleton above the live maximum",
        )

    def cell_overlapping_writers(self):
        self.start_mysql("forward")
        old = self.start_app("pre-allocator", "overlap-old")
        final = self.start_app("final", "overlap-final")
        gate_name = f"operationscompat{self.run_id}"
        # The trigger pauses the old insert after its MAX(priority) read. The final writer can then commit the same
        # priority through the allocator, making the unsupported mixed-writer state deterministic.
        self.mysql_execute(
            "CREATE TRIGGER operations_compat_overlap_gate BEFORE INSERT ON banner_occurrence "
            "FOR EACH ROW SET @operations_compat_gate = "
            f"IF(NEW.title='Overlap old', GET_LOCK('{gate_name}', 30), 1)"
        )
        gate_holder = self.acquire_overlap_gate(gate_name)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            old_future = executor.submit(
                self.http,
                "POST",
                old + "/banners",
                self.payload("<p>Overlapping old writer</p>", "Overlap old"),
                ADMIN_HEADERS,
            )
            try:
                self.wait_for_overlap_gate_waiter(gate_name)
                final_result = self.http(
                    "POST",
                    final + "/banners",
                    self.payload("<p>Overlapping final writer</p>", "Overlap final"),
                    ADMIN_HEADERS,
                )
                final_state = self.mysql_query(
                    "SELECT priority,title FROM banner_occurrence ORDER BY priority,uuid"
                ).splitlines()
                allocator_before_release = self.mysql_query(
                    "SELECT id,next_priority FROM banner_priority_allocator"
                )
            finally:
                self.mysql_execute(f"KILL {gate_holder}")
            old_result = old_future.result(timeout=15)
        codes = sorted([old_result[0], final_result[0]])
        if codes != [201, 201]:
            raise contract.ContractError(f"overlap cell expected both real writers to execute, got HTTP {codes}")
        if final_state != ["1\tOverlap final"] or allocator_before_release != "1\t2":
            raise contract.ContractError(
                "final writer did not commit allocator priority 1 while the old writer was paused after its maximum-priority read: "
                f"rows={final_state}, allocator={allocator_before_release}"
            )
        overlap_state = self.mysql_query(
            "SELECT priority,title FROM banner_occurrence ORDER BY title"
        ).splitlines()
        overlap_rows = [row.split("\t") for row in overlap_state]
        expected_overlap = [["1", "Overlap final"], ["1", "Overlap old"]]
        allocator_after_overlap = self.mysql_query("SELECT id,next_priority FROM banner_priority_allocator")
        if overlap_rows != expected_overlap or allocator_after_overlap != "1\t2":
            raise contract.ContractError(
                "mixed writers did not expose the expected duplicate priority with a bypassed allocator: "
                f"rows={overlap_rows}, allocator={allocator_after_overlap}"
            )
        self.stop_app("overlap-old")
        self.stop_app("overlap-final")
        pre_recovery_max = int(self.mysql_query("SELECT MAX(priority) FROM banner_occurrence"))
        recovery = self.start_app("final", "recovery-final")
        recovered = self.publish(recovery, self.payload("<p>Recovered single writer</p>", "Recovered"))
        allocator = self.mysql_query("SELECT id,next_priority FROM banner_priority_allocator")
        if recovered["priority"] <= pre_recovery_max or allocator != f"1\t{recovered['priority'] + 1}":
            raise contract.ContractError("single-writer recovery did not reconcile the allocator above the live maximum")
        checksum = self.semantic_checksum(
            {
                "http_codes": codes,
                "overlap_rows": overlap_rows,
                "allocator_after_overlap": [1, 2],
                "recovered_priority": recovered["priority"],
                "allocator_after_recovery": [int(value) for value in allocator.split("\t")],
            }
        )
        return self.observation(
            "overlapping-writers", "pre-allocator+final", "UNSUPPORTED_EXPECTED", checksum, "unsupported",
            "mixed writers bypass one allocator; stop management traffic, stop old writers, start final only, perform one allocator-owning publication, then reopen traffic",
        )

    def acquire_overlap_gate(self, gate_name):
        self.command(
            [
                "docker", "exec", "-d", "-e", f"MYSQL_PWD={SYNTHETIC_ROOT_PASSWORD}", self.mysql_container,
                "mysql", "-N", "-B", "-h127.0.0.1", "-uroot", "picsure", "-e",
                f"SELECT GET_LOCK('{gate_name}',0); DO SLEEP(60)",
            ]
        )
        for _ in range(50):
            holder = self.mysql_query(f"SELECT IS_USED_LOCK('{gate_name}')")
            if holder and holder != "NULL":
                return int(holder)
            time.sleep(0.1)
        raise contract.ContractError("could not acquire the deterministic mixed-writer gate")

    def wait_for_overlap_gate_waiter(self, gate_name):
        for _ in range(50):
            waiters = self.mysql_query(
                "SELECT COUNT(*) FROM performance_schema.metadata_locks "
                f"WHERE OBJECT_TYPE='USER LEVEL LOCK' AND OBJECT_NAME='{gate_name}' AND LOCK_STATUS='PENDING'"
            )
            if waiters == "1":
                return
            time.sleep(0.1)
        raise contract.ContractError("old writer did not reach the deterministic mixed-writer gate")

    def prepare_final_rollback_fixture(self):
        final = self.start_app("final")
        all_pages = self.publish(final, self.payload("<p>Rollback all pages</p>", "Rollback all"))
        targeted = self.publish(
            final,
            self.payload(
                "<p>Rollback targeted</p>", "Rollback targeted", audience="SIGNED_OUT",
                targets=[{"kind": "EXACT", "path": "/about"}],
            ),
        )
        edited = self.payload("<p>Rollback all pages edited</p>", "Rollback all edited")
        self.json_request("PUT", final + f"/banners/{all_pages['uuid']}", edited, 200, ADMIN_HEADERS)
        self.stop_app("final")
        self.assert_rollback_fixture_provenance()
        self.canonicalize_rollback_fixture(all_pages["uuid"], targeted["uuid"])

    def assert_rollback_fixture_provenance(self):
        occurrences = self.mysql_query(
            "SELECT HEX(html_content),HEX(title),created_by,updated_by,published_by "
            "FROM banner_occurrence ORDER BY priority"
        ).splitlines()
        expected_occurrences = [
            "3C703E526F6C6C6261636B20616C6C207061676573206564697465643C2F703E\t"
            "526F6C6C6261636B20616C6C20656469746564\tcompat-admin\tcompat-admin\tcompat-admin",
            "3C703E526F6C6C6261636B2074617267657465643C2F703E\t"
            "526F6C6C6261636B207461726765746564\tcompat-admin\tcompat-admin\tcompat-admin",
        ]
        if occurrences != expected_occurrences:
            raise contract.ContractError("final rollback fixture did not retain exact occurrence bytes and actors")
        versions = self.mysql_query(
            "SELECT v.version_number,HEX(v.html_content),HEX(v.title),v.actor,"
            "v.start_at=o.start_at,"
            "v.effective_at=CASE WHEN v.version_number=1 THEN o.published_at ELSE o.updated_at END "
            "FROM banner_version v JOIN banner_occurrence o ON o.uuid=v.banner_uuid "
            "ORDER BY o.priority,v.version_number"
        ).splitlines()
        expected_versions = [
            "1\t3C703E526F6C6C6261636B20616C6C2070616765733C2F703E\t526F6C6C6261636B20616C6C\tcompat-admin\t1\t1",
            "2\t3C703E526F6C6C6261636B20616C6C207061676573206564697465643C2F703E\t526F6C6C6261636B20616C6C20656469746564\tcompat-admin\t1\t1",
            "1\t3C703E526F6C6C6261636B2074617267657465643C2F703E\t526F6C6C6261636B207461726765746564\tcompat-admin\t1\t1",
        ]
        if versions != expected_versions:
            raise contract.ContractError(
                "final rollback fixture did not retain exact immutable version bytes or timestamp provenance"
            )

    def canonicalize_rollback_fixture(self, all_pages_uuid, targeted_uuid):
        first_uuid = "11111111-1111-4111-8111-111111111111"
        second_uuid = "22222222-2222-4222-8222-222222222222"
        statements = f"""
SET FOREIGN_KEY_CHECKS=0;
UPDATE banner_version SET banner_uuid=UNHEX(REPLACE('{first_uuid}','-','')) WHERE banner_uuid=UNHEX(REPLACE('{all_pages_uuid}','-',''));
UPDATE banner_version SET banner_uuid=UNHEX(REPLACE('{second_uuid}','-','')) WHERE banner_uuid=UNHEX(REPLACE('{targeted_uuid}','-',''));
UPDATE banner_occurrence SET uuid=UNHEX(REPLACE('{first_uuid}','-','')) WHERE uuid=UNHEX(REPLACE('{all_pages_uuid}','-',''));
UPDATE banner_occurrence SET uuid=UNHEX(REPLACE('{second_uuid}','-','')) WHERE uuid=UNHEX(REPLACE('{targeted_uuid}','-',''));
UPDATE banner_occurrence SET created_at='2026-01-01 00:00:00.000000',updated_at='2026-01-01 00:02:00.000000',
 published_at='2026-01-01 00:01:00.000000',start_at='2026-01-01 00:01:00.000000',end_at=NULL;
UPDATE banner_version SET uuid=UNHEX(REPLACE(CONCAT('33333333-3333-4333-8333-',LPAD(version_number,12,'0')),'-','')),
 start_at='2026-01-01 00:01:00.000000',end_at=NULL,
 effective_at=CASE version_number WHEN 1 THEN '2026-01-01 00:01:00.000000' ELSE '2026-01-01 00:02:00.000000' END
WHERE banner_uuid=UNHEX(REPLACE('{first_uuid}','-',''));
UPDATE banner_version SET uuid=UNHEX(REPLACE('44444444-4444-4444-8444-444444444444','-','')),
 start_at='2026-01-01 00:01:00.000000',end_at=NULL,effective_at='2026-01-01 00:01:00.000000'
WHERE banner_uuid=UNHEX(REPLACE('{second_uuid}','-',''));
SET FOREIGN_KEY_CHECKS=1;
"""
        self.mysql_execute(statements)

    def rollback_cell(self, generation, cell):
        self.start_mysql("forward")
        self.prepare_final_rollback_fixture()
        before = self.raw_banner_checksum()
        old = self.start_app(generation)
        managed = self.json_request("GET", old + "/banners", headers=ADMIN_HEADERS)
        active = self.json_request("GET", old + "/banners/active")
        if len(managed) != 2 or len(active) != 2:
            raise contract.ContractError(f"{generation} could not read every retained final occurrence")
        self.stop_app(generation)
        after = self.raw_banner_checksum()
        contract.require_preserved_checksum(cell, before, after)
        return self.observation(
            cell, f"final->{generation}", "PASS", after, "supported",
            "old mappings read the additive forward schema without changing occurrence or immutable-version bytes",
        )

    def cell_rollback_pre_version(self):
        return self.rollback_cell("pre-version", "rollback-pre-version")

    def cell_rollback_pre_allocator(self):
        return self.rollback_cell("pre-allocator", "rollback-pre-allocator")

    def cell_final_http_contract(self):
        self.start_mysql("forward")
        final = self.start_app("final")

        draft = self.json_request(
            "POST", final + "/banners/saved", self.payload("<p>Draft bytes</p>", "Draft"), 201, ADMIN_HEADERS
        )
        updated_draft = self.payload("<p>Updated draft bytes</p>", "Updated draft")
        self.json_request("PUT", final + f"/banners/{draft['uuid']}", updated_draft, 200, ADMIN_HEADERS)
        published_draft = self.json_request(
            "POST", final + f"/banners/{draft['uuid']}/publish", updated_draft, 200, ADMIN_HEADERS
        )
        if published_draft["uuid"] != draft["uuid"] or published_draft["status"] != "PUBLISHED":
            raise contract.ContractError("draft publication did not preserve its occurrence")

        all_pages = self.publish(final, self.payload("<p>Legacy feed</p>", "All pages"))
        targeted_payload = self.payload(
            "<p>Targeted bytes</p>", "Targeted", audience="SIGNED_IN",
            targets=[{"kind": "EXACT", "path": "/research"}, {"kind": "SUBTREE", "path": "/studies"}],
        )
        targeted = self.publish(final, targeted_payload)
        original_hash = targeted["presentationHash"]
        schedule_only = dict(targeted_payload)
        schedule_only["endAt"] = "2099-01-01T00:00:00Z"
        scheduled_edit = self.json_request(
            "PUT", final + f"/banners/{targeted['uuid']}", schedule_only, 200, ADMIN_HEADERS
        )
        if scheduled_edit["presentationHash"] != original_hash:
            raise contract.ContractError("schedule-only edit changed the dismissal presentation hash")
        material = dict(schedule_only)
        material["htmlContent"] = "<p>Targeted bytes changed</p>"
        material_edit = self.json_request(
            "PUT", final + f"/banners/{targeted['uuid']}", material, 200, ADMIN_HEADERS
        )
        if material_edit["presentationHash"] == original_hash:
            raise contract.ContractError("material edit did not change the dismissal presentation hash")

        scheduled = self.publish(
            final,
            self.payload(
                "<p>Scheduled</p>", "Scheduled", start="2098-01-01T00:00:00Z", end="2098-01-02T00:00:00Z"
            ),
        )
        if scheduled["lifecycle"] != "SCHEDULED":
            raise contract.ContractError("future HTTP publication was not scheduled")

        expiring = self.publish(final, self.payload("<p>Expiry seam</p>", "Expiry seam"))
        self.mysql_execute(
            "UPDATE banner_occurrence SET start_at='2020-01-01 00:00:00.000000',end_at='2020-01-02 00:00:00.000000' "
            f"WHERE uuid=UNHEX(REPLACE('{expiring['uuid']}','-',''))"
        )
        managed = self.json_request("GET", final + "/banners", headers=ADMIN_HEADERS)
        lifecycle_by_uuid = {item["uuid"]: item["lifecycle"] for item in managed}
        if lifecycle_by_uuid.get(expiring["uuid"]) != "EXPIRED":
            raise contract.ContractError("deterministic database time seam did not produce EXPIRED lifecycle")

        legacy = self.json_request("GET", final + "/banners/active")
        targeted_feed = self.json_request("GET", final + "/banners/active/v2")
        legacy_ids = [item["uuid"] for item in legacy]
        targeted_ids = [item["uuid"] for item in targeted_feed]
        expected_legacy_ids = [published_draft["uuid"], all_pages["uuid"]]
        expected_targeted_ids = expected_legacy_ids + [targeted["uuid"]]
        if legacy_ids != expected_legacy_ids or targeted_ids != expected_targeted_ids:
            raise contract.ContractError(
                "legacy and v2 active feeds did not preserve exact membership and visitor order: "
                f"legacy={legacy_ids}, v2={targeted_ids}"
            )

        disabled = self.json_request("POST", final + f"/banners/{all_pages['uuid']}/disable", expected=200, headers=ADMIN_HEADERS)
        restored = self.json_request(
            "POST", final + f"/banners/{all_pages['uuid']}/restore",
            self.payload("<p>Restored bytes</p>", "Restored"), 201, ADMIN_HEADERS,
        )
        if disabled["status"] != "DISABLED" or restored["restoredFromUuid"] != all_pages["uuid"]:
            raise contract.ContractError("disable/restore lifecycle did not preserve source provenance")
        saved_for_archive = self.json_request(
            "POST", final + "/banners/saved", self.payload("<p>Archive me</p>", "Archive me"), 201, ADMIN_HEADERS
        )
        archived = self.json_request(
            "POST", final + f"/banners/{saved_for_archive['uuid']}/archive", expected=200, headers=ADMIN_HEADERS
        )
        if archived["status"] != "ARCHIVED":
            raise contract.ContractError("archive did not return the authoritative archived state")

        orderable = self.json_request("GET", final + "/banners", headers=ADMIN_HEADERS)
        orderable_ids = [
            item["uuid"]
            for item in sorted(
                (item for item in orderable if item["lifecycle"] in {"ACTIVE", "SCHEDULED"}),
                key=lambda item: (item["priority"], item["uuid"]),
            )
        ]
        submitted = list(reversed(orderable_ids[:2])) + [expiring["uuid"]]
        expected_reordered_ids = submitted[:2] + [uuid for uuid in orderable_ids if uuid not in submitted]
        reordered = self.json_request(
            "PUT", final + "/banners/order", {"bannerUuids": submitted}, 200, ADMIN_HEADERS
        )
        reordered_ids = [item["uuid"] for item in reordered]
        priorities = [item["priority"] for item in reordered]
        if reordered_ids != expected_reordered_ids or expiring["uuid"] in reordered_ids:
            raise contract.ContractError(
                "tolerant reorder did not apply the requested reverse order, exclude the expired member, and append omitted members: "
                f"expected={expected_reordered_ids}, got={reordered_ids}"
            )
        if priorities != list(range(1, len(priorities) + 1)):
            raise contract.ContractError(f"tolerant reorder did not compact the queue: {priorities}")

        visitor_v2 = self.json_request("GET", final + "/banners/active/v2")
        visitor_legacy = self.json_request("GET", final + "/banners/active")
        active_ids = {published_draft["uuid"], targeted["uuid"], restored["uuid"]}
        all_pages_active_ids = {published_draft["uuid"], restored["uuid"]}
        expected_visitor_v2 = [uuid for uuid in expected_reordered_ids if uuid in active_ids]
        expected_visitor_legacy = [uuid for uuid in expected_reordered_ids if uuid in all_pages_active_ids]
        visitor_v2_ids = [item["uuid"] for item in visitor_v2]
        visitor_legacy_ids = [item["uuid"] for item in visitor_legacy]
        if visitor_v2_ids != expected_visitor_v2 or visitor_legacy_ids != expected_visitor_legacy:
            raise contract.ContractError(
                "visitor feeds did not follow the canonical reordered queue: "
                f"legacy={visitor_legacy_ids}, v2={visitor_v2_ids}"
            )

        fixture_names = {
            published_draft["uuid"]: "published-draft",
            all_pages["uuid"]: "all-pages",
            targeted["uuid"]: "targeted",
            scheduled["uuid"]: "scheduled",
            expiring["uuid"]: "expired",
            restored["uuid"]: "restored",
        }
        named = lambda uuids: [fixture_names[uuid] for uuid in uuids]

        version_counts = self.mysql_query(
            "SELECT BIN_TO_UUID(banner_uuid),COUNT(*) FROM banner_version GROUP BY banner_uuid ORDER BY BIN_TO_UUID(banner_uuid)"
        ).splitlines()
        checksum = self.semantic_checksum(
            {
                "draft_same_uuid": True,
                "schedule": scheduled["lifecycle"],
                "expired": lifecycle_by_uuid[expiring["uuid"]],
                "hash_schedule_stable": True,
                "hash_material_changed": True,
                "legacy_feed_before_reorder": named(legacy_ids),
                "v2_feed_before_reorder": named(targeted_ids),
                "restore_source": True,
                "archive": archived["status"],
                "submitted_order": named(submitted),
                "canonical_reorder": named(reordered_ids),
                "priorities": priorities,
                "legacy_feed_after_reorder": named(visitor_legacy_ids),
                "v2_feed_after_reorder": named(visitor_v2_ids),
                "version_counts": sorted(line.split("\t")[1] for line in version_counts),
            }
        )
        return self.observation(
            "final-http-contract", "final", "PASS", checksum, "supported",
            "complete management and anonymous feed contract passed with deterministic database timestamp fixtures and no lifecycle sleeps",
        )

    def cell_occurrence_only_rejection(self):
        self.start_mysql("occurrence-only")
        self.start_audit_probe()
        final = self.start_app("final")
        health_code, health_body = self.http("GET", final + "/actuator/health")
        if health_code != 200 or json.loads(health_body).get("status") != "UP":
            raise contract.ContractError("generic database health did not come up on occurrence-only schema")
        code, body = self.http(
            "POST", final + "/banners", self.payload("<p>Must roll back</p>", "Rejected generation"), ADMIN_HEADERS
        )
        if code < 500:
            raise contract.ContractError(f"functional banner probe should reject occurrence-only schema, got HTTP {code}: {body}")
        occurrence_count = self.mysql_query("SELECT COUNT(*) FROM banner_occurrence")
        tables = self.mysql_query(
            "SELECT table_name FROM information_schema.tables WHERE table_schema='picsure' AND table_name LIKE 'banner_%' ORDER BY table_name"
        ).splitlines()
        if occurrence_count != "0" or tables != ["banner_occurrence"]:
            raise contract.ContractError(
                f"failed functional probe left partial state: occurrences={occurrence_count}, tables={tables}"
            )
        app_name = self.app_containers["final"]
        self.capture_logs(app_name)
        log_text = (self.temp_root / f"{app_name}.log").read_text(encoding="utf-8")
        if "logging-client: configured for http://audit:8081/cgi-bin" not in log_text:
            raise contract.ContractError("occurrence-only rejection did not enable the synthetic audit probe")
        if "banner_priority_allocator" not in log_text or "doesn't exist" not in log_text:
            raise contract.ContractError("occurrence-only rejection did not record the missing allocator failure")
        for _ in range(20):
            if self.audit_request_file.exists() and self.audit_request_file.stat().st_size:
                break
            time.sleep(0.1)
        if self.audit_request_file.exists() and self.audit_request_file.stat().st_size:
            raise contract.ContractError("failed occurrence-only transaction emitted an after-commit audit event")
        checksum = self.semantic_checksum(
            {"generic_health": "UP", "functional_http": code, "occurrences": 0, "tables": tables, "audit_after_commit": False}
        )
        return self.observation(
            "occurrence-only-rejection", "final", "REJECTED_EXPECTED", checksum, "unsupported",
            "generic health was UP; real publish returned 5xx because banner_priority_allocator was absent; transaction rolled back with zero occurrences, no version table, and no after-commit audit",
            schema="occurrence-only",
        )

    @staticmethod
    def command(args, check=True, capture=False, merge_stderr=False, timeout=DEFAULT_COMMAND_TIMEOUT_SECONDS):
        stderr = subprocess.STDOUT if merge_stderr else (subprocess.PIPE if capture else None)
        command = [str(arg) for arg in args]
        try:
            result = subprocess.run(
                command,
                check=False,
                stdout=subprocess.PIPE if capture else None,
                stderr=stderr,
                text=True,
                timeout=timeout,
            )
        except subprocess.TimeoutExpired as error:
            raise contract.ContractError(
                f"command timed out after {timeout} seconds: {' '.join(command)}"
            ) from error
        if check and result.returncode != 0:
            stdout = result.stdout.strip() if result.stdout else ""
            stderr_text = "" if merge_stderr or not result.stderr else result.stderr.strip()
            detail = "\n".join(value for value in (stdout, stderr_text) if value)
            raise contract.ContractError(f"command failed ({result.returncode}): {' '.join(map(str, args))}\n{detail}")
        return result


def main():
    if len(sys.argv) != 4:
        raise contract.ContractError("usage: run.py <repository-root> <temporary-root> <all|cell>")
    repository_root, temp_root, selection = sys.argv[1:]
    if selection != "all" and selection not in contract.REQUIRED_CELLS:
        raise contract.ContractError(
            "selection must be all or one of: " + ", ".join(contract.REQUIRED_CELLS)
        )
    run_id = Path(temp_root).name.removeprefix("operations-binary-")[:32]
    harness = Harness(repository_root, temp_root, run_id, selection)

    def stop(_signum, _frame):
        harness.stop_all_apps()
        harness.stop_mysql()
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

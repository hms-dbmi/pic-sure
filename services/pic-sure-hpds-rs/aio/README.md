# hpds-rs beside the all-in-one

Scripts that run the Rust HPDS (`hpds-rs`) next to the Java `hpds` container of a local
all-in-one stack, and move the query service between the two. The Java HPDS keeps running
untouched, so the gateway health check stays green and both engines can be compared live.

Every script reads `DOCKER_CONFIG_DIR` from the environment and stops with a clear message when
it is unset. Every script except `status.sh` takes `--dry-run`, which prints each docker
command and file write without running it. Read-only `docker inspect` queries still run.
Docker, `jq` and bash are required. `lib.sh` is shared code and is never run.

## Where to run it

Run these scripts on the host that runs the all-in-one, in Docker Desktop, not inside Jenkins.
They create bind mounts from `DOCKER_CONFIG_DIR`, and a script run in a Jenkins container sees
that path inside the container, not on the Docker host. On Linux the ETL containers and the
store files run as your user; if your user is not UID 1000, the files the all-in-one's
containers own (UID 1000) may be unreadable or unwritable to you, so check ownership of
`hpds/` and `hpds-rs/` before `load.sh`.

## Workflow

```bash
export DOCKER_CONFIG_DIR=/path/to/local_all_in_one   # the directory start-picsure.sh uses
aio/build-images.sh                                  # 1. build the images
aio/load.sh migrate                                  # 2. load a store (or: aio/load.sh csv)
aio/up.sh                                            # 3. start hpds-rs
aio/switch.sh rust                                   # 4. point the query service at it
aio/status.sh                                        #    see what is running
aio/switch.sh java                                   #    roll back
aio/down.sh                                          # 5. remove hpds-rs
```

Recommended order: run `load.sh migrate` (or `csv`) before Start PIC-SURE. A migration reads the
whole Java store and builds the Rust one while the Java HPDS holds its own copy in memory, so
doing it first, before `start-picsure.sh` brings the stack up, avoids memory pressure on the
Docker VM. Then start the AIO, then `up.sh`.

| Script | Writes |
|---|---|
| `build-images.sh [--java-etl]` | Images `hms-dbmi/pic-sure-hpds-rs:LATEST` and `hms-dbmi/pic-sure-hpds-rs-etl:LATEST`. With `--java-etl`, `hms-dbmi/pic-sure-hpds-etl:migrate`, only when no local Java ETL image already holds `ExportForMigration`. The all-in-one's own `hms-dbmi/pic-sure-hpds-etl:LATEST` is never retagged. |
| `load.sh migrate` | `hpds-rs/` (`pheno.blocks`, `pheno.index`, `columnMeta.csv`, `etl-report.json`, `encryption_key` mode 600). Converts `hpds/` through `bin/migrate-javabins` with `FORCE=1`. `hpds/` is mounted read-only and never written. |
| `load.sh csv` | The same files in `hpds-rs/`, built by `hpds-etl load --mode sequential` (the all-in-one's multi-file job, rollup on) over `hpds_input/*.csv`, mounted read-only. It builds in `hpds-rs/.csv-next/` first and moves the files into place, `pheno.index` last. |
| `up.sh` | Container `hpds-rs`, replaced safely (see below); `hpds-rs/hpds-rs.env` on the first run only; directories `hpds-rs-results/` and `log/hpds-rs-logs/`. |
| `switch.sh rust` | `query/query.env.pre-rust` (once, never overwritten; an existing `query.env.java-backup` from an earlier version is read and renamed to it), the two URL lines of `query/query.env`, and a recreated `pic-sure-hpds-query-service` container. |
| `switch.sh java` | The two URL lines of `query/query.env` (each restored from the backup on its own), the backup renamed to `query.env.pre-rust.used`, and a recreated query service. `--file-only` restores the file and touches no container. |
| `status.sh` | Nothing. |
| `down.sh` | Removes the container, after switching the query service back (or restoring `query.env` alone when no query service container points at hpds-rs). Leaves the store, results, logs and Java `hpds` alone. |

Both load modes copy the all-in-one's `hpds/encryption_key` into `hpds-rs/` with mode 600 and refuse
to replace a different key already there. If `hpds-rs` is running when a load finishes, it is
recreated, because its bind mount would otherwise keep serving the replaced files.

`up.sh` needs the `picsure` and `hpds` networks, so start the AIO first. It creates the container,
attaches it to both networks, then starts it. It mounts `hpds-rs/` at `/opt/local/hpds`
read-only, the results directory at `/var/lib/hpds-rs/results` and the logs at
`/var/log/hpds-rs`, uses `--restart always`, and waits for the container healthcheck (store
open with at least one concept). When `hpds-rs` already exists, the replacement is created as
`hpds-rs-new`, started and proven healthy first; only then are the names swapped. If the
replacement does not come up it is removed and the old one keeps running, which matters while
the query service points at `hpds-rs`. The two containers share the results directory during
the overlap.

After the swap the old container stays up as `hpds-rs-old` for `HPDS_RS_SWAP_GRACE_SECONDS`
(default 60) and is then stopped and removed. The query service is a JVM, and it caches the
address `hpds-rs` resolved to for about 30 seconds. Removing the old container right away left
that cached address pointing at nothing, and queries failed with 502 "Connect timed out" until
the cache expired. The rename takes the old container out of Docker DNS, so new lookups reach
the replacement, but it keeps its address and still answers anyone holding it.

`hpds-rs.env` is written once from `hpds/hpds.env`: `ID_BATCH_SIZE`, `SMALL_TASK_THREADS`,
`LARGE_TASK_THREADS` and `hpds.requireAuthorizationFilter` (which becomes
`HPDS_REQUIRE_AUTHORIZATION_FILTER`, `true` when the Java file does not set it), plus
`HPDS_CACHE_BYTES` at 4 GiB and `RUST_LOG=info`. Edit it and run `up.sh` again to apply a
change; `up.sh` never rewrites it.

`hpds-rs-results/` sits beside the store, not inside `hpds-rs/`, because the store is mounted
read-only and the results directory is the one place the container writes query output. Keeping
it outside also means a reload that replaces the store files never touches or deletes results.

## Switching the query service

The query service reads `HPDS_AUTH_URL` and `HPDS_OPEN_URL` from `query.env` only when its
container is created. `switch.sh` therefore edits the two lines (every other line is kept byte for
byte) and recreates the container itself, without Start PIC-SURE:

1. It reads `docker inspect` of the running container: image, restart policy, networks and
   aliases, mounts, ports and labels. A container with settings it does not reproduce (a changed
   entrypoint, command, user, healthcheck, resource limits, devices, capabilities, tmpfs, init,
   ulimits, links, static addresses, hostname, sysctls, stop settings, log configuration) is
   refused, and the message says to run Start PIC-SURE instead.
2. The new environment is the old container's own variables (those the image does not define)
   overridden by every key in `query.env`.
3. Every check runs and the new container is created under a temporary name before `query.env` is
   touched, so a refusal or a configuration docker rejects changes nothing. Then the file is
   edited, the old container is stopped and renamed aside, and the new one takes the name and
   starts. If it does not come up healthy, it is removed, the old container is renamed back and
   started, and the URL lines (and a backup made by this run) are restored, so the file matches
   the running container. The old container is removed only after the new one is healthy. The
   merged environment, which holds secrets, lives in a temporary file removed on every exit path.

`switch.sh rust` refuses unless `hpds-rs` is healthy. `status.sh` reads the engine from the
environment of the running container, not the file, and flags a mismatch.

`test/functional.sh` runs all of this against throwaway `-t` containers and networks, a fake
query service and a scratch `DOCKER_CONFIG_DIR`. It checks that `docker inspect` of the query
service is identical before and after a switch except for the environment and ids, and that a
rejected or unhealthy replacement leaves the original running and `query.env` unchanged, that `up.sh` keeps the old `hpds-rs` when the new one is unhealthy, and that `down.sh` restores the file with no query service container. It removes everything it creates.

It tests the `LATEST` images unless `HPDS_RS_IMAGE` and `RUST_ETL_IMAGE` name others, and
`FUNCTIONAL_PREFIX` puts a prefix on every container, network and test image it creates, so a
run against a tagged build (`FUNCTIONAL_PREFIX=v4-` with the `:v4` images) keeps its own names.
`FIXTURE` and `SOURCE_CSV` point it at the 1000 Genomes javabin store and `allConcepts.csv`
when the checkout lacks them, as a consent-partitioned branch does.

## Things to know

- **The "Load HPDS Data From CSV" Jenkins job moves `hpds.env` out of `hpds/`** and replaces the
  Java store. After it runs, `hpds/` no longer holds what `load.sh migrate` needs; re-run
  `load.sh csv` (which reads `hpds_input/`) or restore the Java store first. `load.sh csv` still
  needs an `encryption_key` in `hpds/` or `hpds-rs/`.
- **Start PIC-SURE leaves `hpds-rs` alone** (it never names it), but it recreates the query
  service from `query.env`. If you switched to rust, the file says rust and the query service
  stays on rust after a restart. Run `switch.sh java` to go back. If the file and the running
  container disagree, `status.sh` says so.
- **Roll back** with `aio/switch.sh java`, or `aio/down.sh`, which switches back first when the
  query service still points at `hpds-rs`. When no query service container exists (after a Docker
  reset), `down.sh` restores `query.env` alone. `switch.sh java` needs `query.env.pre-rust`, or a
  `query.env` that already points at the Java HPDS, and warns when the backup's URLs differ from
  a current non-rust `query.env`.
- Network and container names can be overridden for tests with `PICSURE_NETWORK`,
  `HPDS_NETWORK`, `QS_CONTAINER`, `HPDS_RS_CONTAINER` and `HPDS_JAVA_CONTAINER`.

# pic-sure-hpds-rs

Rust prototype of the HPDS phenotype store and query service. It is a Cargo workspace outside
the Maven reactor and is not listed in any pom.

## Crates

| Crate | Kind | Purpose |
|---|---|---|
| `hpds-format` | lib | On-disk index and block format, crypto and Java parity helpers |
| `hpds-etl` | bin + lib | Loads CSV and migrates Java javabins into the store (`hpds-etl`) |
| `hpds-query` | lib | Query engine over the store; currently the health-state check only |
| `hpds-rs` | bin + lib | HTTP server (`hpds-rs`) over `hpds-query`, currently `GET /actuator/health` only |
| `hpds-gen` | bin | Deterministic synthetic dataset generator |
| `hpds-bench` | bin | Query corpus, compare and load harness |

## Build and test

The toolchain is pinned in `rust-toolchain.toml`. With rustup on the host:

```bash
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
```

Without rustup, `bin/cargo` runs the same commands in the pinned builder image, with named
volumes `hpds-rs-cargo-home` and `hpds-rs-target` caching the registry and build output:

```bash
bin/cargo test --workspace
```

## Images

Build from this directory:

```bash
docker build -f docker/hpds-rs.Dockerfile -t hms-dbmi/pic-sure-hpds-rs:LATEST .
docker build -f docker/hpds-rs-etl.Dockerfile -t hms-dbmi/pic-sure-hpds-rs-etl:LATEST .
```

`hpds-rs` reads these environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `HPDS_DATA_DIRECTORY` | `/opt/local/hpds/` | Store directory. Matches the Java service; the all-in-one stack bind-mounts the hpds-rs host directory here. |
| `SERVER_PORT` | `8080` | Listen port on `0.0.0.0`. |
| `RUST_LOG` | `info` | Log filter, in `tracing` `EnvFilter` syntax. |

The encryption key will be read from `<HPDS_DATA_DIRECTORY>/encryption_key` once the store
loads data.

`/actuator/health` returns 200 `{"status":"UP"}` when `pheno.index` exists in the data
directory and 503 `{"status":"DOWN"}` otherwise. This existence check is a placeholder: when
the store exists, health means the index loads with at least one concept, and that check
replaces this one.

The server stops cleanly on SIGTERM and ctrl-c. `hpds-rs healthcheck` requests
`http://127.0.0.1:$SERVER_PORT/actuator/health` and exits 0 on a 200 `UP`, 1 otherwise; the
image uses it as its Docker `HEALTHCHECK`. Both images run as UID 1000 on
`debian:bookworm-slim`.

## Loading a store

`hpds-etl load --mode <new-search|sequential> --input <csv>... --out <dir>` builds a store from
phenotype CSV. `--mode` is required: `new-search` follows `CSVLoaderNewSearch`, `sequential`
follows `SequentialLoader`. The store is encrypted with `<out>/encryption_key` unless
`--key-file` names another key. `--plain` writes an unencrypted store for benchmarking and is
refused when `<out>` holds an `encryption_key`. `--out` is refused when it holds a Java store
(`columnMeta.javabin` or `allObservationsStore.javabin`).

The CSV config is the file `--config` names, or else the first `config.json` in a directory
input or beside an input file. The log names the config applied and warns about any
`config.json` beside the inputs that is not.

Every directory the ETL creates is mode `0700` and every file `0600`, so only the owning user
can read the store or its temporary files. Both images run as UID 1000, so the `hpds-rs`
service reads what the `hpds-rs-etl` container wrote. A different service UID needs the store
files made readable to it by hand.

The store is written to a staging directory beside `--out` and moved into place when complete.
SIGTERM and SIGINT remove the staging and spill directories before exiting; a later run removes
any that a killed run left behind.

Memory: the partition pass holds the concept and path dictionaries. The build pass runs
`--threads` workers (one per core by default), each holding one partition's spill file plus
40 bytes per row of it, so peak memory is about `threads` times the largest partitions.
`--partitions` (default one per 32 MiB of input, at most 128) lowers it.

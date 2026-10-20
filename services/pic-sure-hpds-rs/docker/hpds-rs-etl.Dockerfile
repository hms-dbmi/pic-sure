# syntax=docker/dockerfile:1
FROM rust:1.98.0-bookworm AS build
ARG VENDORED=0
RUN rustup component add clippy rustfmt
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/workspace/target \
    if [ "$VENDORED" = 1 ]; then mkdir -p .cargo && printf '[source.crates-io]\nreplace-with = "vendored-sources"\n\n[source.vendored-sources]\ndirectory = "vendor"\n\n[net]\noffline = true\n' > .cargo/config.toml; fi \
    && cargo build --release --locked -p hpds-etl --bin hpds-etl -p hpds-bench --bin hpds-bench \
    && cp target/release/hpds-etl target/release/hpds-bench /usr/local/bin/

FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 1000 --create-home --shell /usr/sbin/nologin hpds \
    && mkdir -p /opt/local/hpds-rs && chown hpds:hpds /opt/local/hpds-rs
COPY --from=build /usr/local/bin/hpds-etl /usr/local/bin/hpds-bench /usr/local/bin/
USER 1000
ENTRYPOINT ["/usr/local/bin/hpds-etl"]

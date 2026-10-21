# syntax=docker/dockerfile:1.27.1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e
FROM rust:1.98.0-bookworm@sha256:82150a52ec202c1b14d7817e14516c392bb7f5cfebd88f1ed531cb37ebd39922 AS build
ARG VENDORED=0
RUN if [ "$VENDORED" != 1 ]; then rustup component add clippy rustfmt; fi
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/workspace/target \
    if [ "$VENDORED" = 1 ]; then export RUSTUP_TOOLCHAIN="$RUST_VERSION" && mkdir -p .cargo && printf '[source.crates-io]\nreplace-with = "vendored-sources"\n\n[source.vendored-sources]\ndirectory = "vendor"\n\n[net]\noffline = true\n' > .cargo/config.toml; fi \
    && cargo build --release --locked -p hpds-etl --bin hpds-etl -p hpds-bench --bin hpds-bench \
    && cp target/release/hpds-etl target/release/hpds-bench /usr/local/bin/

FROM debian:bookworm-slim@sha256:7ff6ba31527b9e336162fa061746c366c4b2032f4d2cff2078bea43f78efe3f8
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 1000 --create-home --shell /usr/sbin/nologin hpds \
    && mkdir -p /opt/local/hpds-rs && chown hpds:hpds /opt/local/hpds-rs
COPY --from=build /usr/local/bin/hpds-etl /usr/local/bin/hpds-bench /usr/local/bin/
USER 1000
ENTRYPOINT ["/usr/local/bin/hpds-etl"]

# syntax=docker/dockerfile:1
FROM rust:1.98.0-bookworm@sha256:82150a52ec202c1b14d7817e14516c392bb7f5cfebd88f1ed531cb37ebd39922 AS build
RUN rustup component add clippy rustfmt
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/workspace/target \
    cargo build --release --locked -p hpds-etl --bin hpds-etl -p hpds-bench --bin hpds-bench \
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

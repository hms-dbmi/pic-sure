# syntax=docker/dockerfile:1
FROM rust:1.98.0-bookworm AS build
RUN rustup component add clippy rustfmt
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/workspace/target \
    cargo build --release --locked -p hpds-etl --bin hpds-etl -p hpds-bench --bin hpds-bench \
    && cp target/release/hpds-etl target/release/hpds-bench /usr/local/bin/

FROM debian:bookworm-slim
RUN useradd --uid 1000 --create-home --shell /usr/sbin/nologin hpds \
    && mkdir -p /opt/local/hpds-rs && chown hpds:hpds /opt/local/hpds-rs
COPY --from=build /usr/local/bin/hpds-etl /usr/local/bin/hpds-bench /usr/local/bin/
USER 1000
ENTRYPOINT ["/usr/local/bin/hpds-etl"]

# syntax=docker/dockerfile:1
FROM rust:1.98.0-bookworm AS build
ARG TARGETARCH
ARG VENDORED=0
RUN rustup component add clippy rustfmt
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/workspace/target \
    if [ "$VENDORED" = 1 ]; then mkdir -p .cargo && printf '[source.crates-io]\nreplace-with = "vendored-sources"\n\n[source.vendored-sources]\ndirectory = "vendor"\n\n[net]\noffline = true\n' > .cargo/config.toml; fi \
    && if [ "$TARGETARCH" = arm64 ]; then export JEMALLOC_SYS_WITH_LG_PAGE=16; fi \
    && cargo build --release --locked -p hpds-rs --bin hpds-rs \
    && cp target/release/hpds-rs /usr/local/bin/hpds-rs

FROM debian:bookworm-slim
RUN useradd --uid 1000 --create-home --shell /usr/sbin/nologin hpds \
    && mkdir -p /opt/local/hpds && chown hpds:hpds /opt/local/hpds
COPY --from=build /usr/local/bin/hpds-rs /usr/local/bin/hpds-rs
USER 1000
EXPOSE 8080
ENV HPDS_DATA_DIRECTORY=/opt/local/hpds/
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s CMD ["/usr/local/bin/hpds-rs","healthcheck"]
ENTRYPOINT ["/usr/local/bin/hpds-rs"]

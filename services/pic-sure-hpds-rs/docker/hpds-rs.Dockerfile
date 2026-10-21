# syntax=docker/dockerfile:1
FROM rust:1.98.0-bookworm@sha256:82150a52ec202c1b14d7817e14516c392bb7f5cfebd88f1ed531cb37ebd39922 AS build
ARG TARGETARCH
ARG VENDORED=0
RUN if [ "$VENDORED" != 1 ]; then rustup component add clippy rustfmt; fi
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/workspace/target \
    if [ "$VENDORED" = 1 ]; then export RUSTUP_TOOLCHAIN="$RUST_VERSION" && mkdir -p .cargo && printf '[source.crates-io]\nreplace-with = "vendored-sources"\n\n[source.vendored-sources]\ndirectory = "vendor"\n\n[net]\noffline = true\n' > .cargo/config.toml; fi \
    && if [ "$TARGETARCH" = arm64 ]; then export JEMALLOC_SYS_WITH_LG_PAGE=16; fi \
    && cargo build --release --locked -p hpds-rs --bin hpds-rs \
    && cp target/release/hpds-rs /usr/local/bin/hpds-rs

FROM debian:bookworm-slim@sha256:7ff6ba31527b9e336162fa061746c366c4b2032f4d2cff2078bea43f78efe3f8
RUN useradd --uid 1000 --create-home --shell /usr/sbin/nologin hpds \
    && mkdir -p /opt/local/hpds && chown hpds:hpds /opt/local/hpds
COPY --from=build /usr/local/bin/hpds-rs /usr/local/bin/hpds-rs
USER 1000
EXPOSE 8080
ENV HPDS_DATA_DIRECTORY=/opt/local/hpds/
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s CMD ["/usr/local/bin/hpds-rs","healthcheck"]
ENTRYPOINT ["/usr/local/bin/hpds-rs"]

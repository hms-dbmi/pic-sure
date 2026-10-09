# syntax=docker/dockerfile:1
FROM rust:1.98.0-bookworm AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/workspace/target \
    cargo build --release --locked -p hpds-query --bin hpds-rs \
    && cp target/release/hpds-rs /usr/local/bin/hpds-rs

FROM debian:bookworm-slim
RUN useradd --uid 1000 --create-home --shell /usr/sbin/nologin hpds \
    && mkdir -p /opt/local/hpds-rs && chown hpds:hpds /opt/local/hpds-rs
COPY --from=build /usr/local/bin/hpds-rs /usr/local/bin/hpds-rs
USER 1000
EXPOSE 8080
ENV HPDS_DATA_DIRECTORY=/opt/local/hpds-rs/
ENTRYPOINT ["/usr/local/bin/hpds-rs"]

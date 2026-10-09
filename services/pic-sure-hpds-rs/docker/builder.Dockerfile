FROM rust:1.98.0-bookworm
RUN rustup component add clippy rustfmt
WORKDIR /workspace

FROM mysql:8.4@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb AS empty-db

EXPOSE 3306

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=5 \
    CMD mysqladmin ping -h 127.0.0.1 --silent || exit 1

USER mysql

FROM empty-db AS initialized-db

COPY --chown=mysql:mysql db/picsure-auth-dump-initial.sql /docker-entrypoint-initdb.d/picsure-auth-dump-initial.sql

USER mysql

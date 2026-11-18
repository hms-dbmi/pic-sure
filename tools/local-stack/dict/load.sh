#!/bin/sh
# Fills the dictionary database of the local stack, then exits (run by the dict-load service).
#   DICT_LOAD_MODE=sql    /dict/schema.sql and /dict/data.sql (the synthetic dictionary written by dictgen.py)
#   DICT_LOAD_MODE=dump   /dump/dictionary_db.tar, a dictionary_db_*.tar of the dictionary ETL: a tar holding a directory-format
#                         pg_dump of schema dict; restored with pg_restore --schema=dict --clean --if-exists
set -eu
case "${DICT_LOAD_MODE:-sql}" in
  sql)
    psql -v ON_ERROR_STOP=1 -q -f /dict/schema.sql -f /dict/data.sql
    ;;
  dump)
    work=$(mktemp -d)
    tar -xf /dump/dictionary_db.tar -C "$work"
    toc=$(find "$work" -name toc.dat | head -n 1)
    [ -n "$toc" ] || { echo "dict-load: no directory-format dump (toc.dat) in the tar" >&2; exit 1; }
    # a pg_dump --schema=dict archive has no CREATE SCHEMA (the dictionary database has the schema before any restore)
    psql -v ON_ERROR_STOP=1 -q -c 'create schema if not exists dict'
    pg_restore --format=d --schema=dict --clean --if-exists --no-owner --no-privileges --dbname="$PGDATABASE" "$(dirname "$toc")"
    rm -rf "$work"
    ;;
  *)
    echo "dict-load: DICT_LOAD_MODE=${DICT_LOAD_MODE}: expected sql or dump" >&2
    exit 2
    ;;
esac
psql -tA -c "select 'dict-load: datasets=' || count(*) from dict.dataset"

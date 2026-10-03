# pic-sure-mcp

`pic-sure-mcp` is the PIC-SURE Model Context Protocol (MCP) server. It lets an MCP client such as Claude Code search the data dictionary and run obfuscated participant counts. It speaks MCP over stateless Streamable HTTP on `/mcp` and answers every request with a single JSON body, never an SSE stream. It sits behind the gateway, which authenticates the caller and routes `/mcp` here. Each tool call becomes ordinary REST calls back through the gateway (`PICSURE_GATEWAY_URL`), so PSAMA access rules and the gateway audit apply to every operation. The service has no database and no Spring Security.

## Tools

| Tool | Does |
|---|---|
| `search_concepts` | Searches the dictionary by free text. The dictionary ANDs every word with prefix matching, so `search` must describe one concept; `terms` runs up to 5 separate searches, such as synonyms and abbreviations, in one call and merges the results. |
| `list_facets` | Lists facet categories, such as study, with concept counts for an optional search. |
| `get_concept` | Gets one concept by dataset and concept path. |
| `get_concepts` | Gets up to 25 concepts by concept path in one call, listing the paths the dictionary does not know in `notFound`. |
| `count_participants` | Returns the obfuscated open-access participant count for a query. |
| `cross_count` | Returns obfuscated open-access cross counts for a query. |
| `get_adapter_code` | Returns Python, R, or bash code the user runs with their own token for exact, consent-filtered results. |

## The open-only rule

The service only ever reads the open, obfuscated view of the data. Query calls go to `/hpds/open/query/sync` and nowhere else: no client method exists for `/hpds/auth` or for the async query endpoints, and no setting or tool argument can select another channel. Only the four result types the open channel obfuscates are allowed (COUNT, CROSS_COUNT, CATEGORICAL_CROSS_COUNT, CONTINUOUS_CROSS_COUNT). The dictionary search and facet calls send an empty consent list, the same view the open UI gets, and the concept detail lookups carry no consents field.

## Adapter code

`get_adapter_code` returns Python, R, or bash code that the user runs in their own environment with their own token, read from `PICSURE_TOKEN`. That code returns exact counts, participant rows, or timestamps filtered by the user's consents. The Python and R code use the `picsure` adapters. The bash script calls the authorized REST path with curl and jq, and that path appears in the script only as text: this service never calls it. `MCP_ADAPTER_BASE_URL` must be the bare site URL over `https` (plain `http` only on `localhost` or `127.0.0.1`), with no path, query, fragment, or user info, and startup fails otherwise.

## Environment

| Variable | Required | Purpose |
|---|---|---|
| `PICSURE_GATEWAY_URL` | yes | The gateway's internal base URL, not httpd. Every outbound call goes here. |
| `MCP_SERVICE_TOKEN` | yes | Shared secret sent as `X-PIC-SURE-MCP-TOKEN` on loop-back calls so the gateway can mark them as MCP. Must match the gateway's value. |
| `MCP_ADAPTER_BASE_URL` | yes | The site's public URL, written into generated adapter code. The adapters add `/picsure/...` themselves, so this is the host alone, such as `https://picsure.example.org`. |
| `MCP_ADAPTER_INCLUDE_CONSENTS` | no, default `false` | Whether generated adapter code passes `include_consents=True`. True on BDC. |
| `MCP_ADAPTER_SUPPORTS_GENOMIC` | no, default `false` | Whether generated adapter code declares genomic support. True where HPDS has genomic data. |
| `MCP_ADAPTER_PYTHON_MIN_VERSION` | no, default `3.0.0` | The oldest `picsure` Python adapter release generated code supports, used in the `install` command `get_adapter_code` returns. |
| `MCP_ADAPTER_R_TAG` | no, default `v3.0.0` | The `pic-sure-r-adapter-hpds` release tag generated R code installs, used in the `install` command `get_adapter_code` returns. |
| `LOGGING_SERVICE_URL` | no | Base URL of the PIC-SURE logging service. Audit events are dropped when unset. |
| `LOGGING_API_KEY` | no | API key for the logging service. |
| `SERVER_PORT` | no, default `8080` | HTTP port. |

Only `/actuator/health` is exposed, and the container health check uses `/actuator/health/liveness`.

## Running locally

Build the module and the libraries it depends on, then start the jar:

```bash
mvn -pl services/pic-sure-mcp -am install
PICSURE_GATEWAY_URL=http://localhost:8080 \
MCP_SERVICE_TOKEN=local-mcp-token \
MCP_ADAPTER_BASE_URL=https://localhost \
SERVER_PORT=8090 \
java -jar services/pic-sure-mcp/target/pic-sure-mcp-*.jar
```

Check that it answers:

```bash
curl -s http://localhost:8090/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

## Connecting a client

Users connect through the gateway's public `/mcp` route with their PIC-SURE token:

```bash
claude mcp add --transport http picsure https://<picsure-host>/picsure/mcp --header "Authorization: Bearer $PICSURE_TOKEN"
```

On a site with open access enabled the header can be left off, and the tools see the same open data an anonymous user of the UI sees.

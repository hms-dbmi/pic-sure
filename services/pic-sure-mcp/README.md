# pic-sure-mcp

`pic-sure-mcp` is the PIC-SURE Model Context Protocol (MCP) server. It lets an MCP client such as Claude Code search the data dictionary and run obfuscated participant counts. It speaks MCP over stateless Streamable HTTP on `/mcp` and answers every request with a single JSON body, never an SSE stream. It sits behind the gateway, which authenticates the caller and routes `/mcp` here. Each tool call becomes ordinary REST calls back through the gateway (`PICSURE_GATEWAY_URL`), so PSAMA access rules and the gateway audit apply to every operation. The service has no database and no Spring Security.

## The open-only rule

The service only ever reads the open, obfuscated view of the data. Query calls go to `/hpds/open/query/sync` and nowhere else: no client method exists for `/hpds/auth` or for the async query endpoints, and no setting or tool argument can select another channel. Only the four result types the open channel obfuscates are allowed (COUNT, CROSS_COUNT, CATEGORICAL_CROSS_COUNT, CONTINUOUS_CROSS_COUNT), and dictionary calls send an empty consent list, the same view the open UI gets.

## Environment

| Variable | Required | Purpose |
|---|---|---|
| `PICSURE_GATEWAY_URL` | yes | The gateway's internal base URL, not httpd. Every outbound call goes here. |
| `MCP_SERVICE_TOKEN` | yes | Shared secret sent as `X-PIC-SURE-MCP-TOKEN` on loop-back calls so the gateway can mark them as MCP. Must match the gateway's value. |
| `MCP_ADAPTER_BASE_URL` | yes | The site's public URL, written into generated adapter code. |
| `MCP_ADAPTER_INCLUDE_CONSENTS` | no, default `false` | Whether generated adapter code passes `include_consents=True`. True on BDC. |
| `MCP_ADAPTER_SUPPORTS_GENOMIC` | no, default `false` | Whether generated adapter code declares genomic support. True where HPDS has genomic data. |
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
MCP_ADAPTER_BASE_URL=https://localhost/picsure \
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

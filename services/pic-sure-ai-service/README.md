# pic-sure-ai-service

`pic-sure-ai-service` is the AI-assisted-search chat endpoint: it runs the Bedrock Converse tool-use
loop for `POST /ai/chat` (reachable externally as `POST /picsure/ai/chat` once the outer `/picsure`
prefix in front of the gateway applies) and acts as an MCP client against the gateway's MCP endpoint,
replaying the caller's bearer JWT untouched on every call. It never mints, caches, or exchanges a
credential of its own.

## More documentation

| Doc | Read it when |
| --- | --- |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | You need the diagrams (system context, one chat turn, the guard loop, authorization, ownership and idempotency) or the error contract. |
| [docs/GUARDS.md](docs/GUARDS.md) | You are changing how the service checks or repairs what the model does, or chasing a model-behavior bug. Holds the full guard table and the known limits. |
| [docs/MODEL.md](docs/MODEL.md) | You are choosing or swapping the reasoning model, or the `aws-sdk` / `http` provider. |
| [docs/DEBUGGING.md](docs/DEBUGGING.md) | You are reading logs for a turn, or turning on DEBUG output. |
| [docs/AWS_CREDENTIALS.md](docs/AWS_CREDENTIALS.md) | You are setting up Bedrock credentials on EC2 or locally. |

## API

Both routes exist only when `AI_ENABLED=true`; otherwise they answer 404. Every request needs `Authorization: Bearer <jwt>` and the assisted-search privilege (see [Who may use the assistant](#who-may-use-the-assistant)).

| Route | Purpose |
| --- | --- |
| `POST /ai/chat` | One chat turn. Body: `message` (required, max 4000 chars), `conversationId` (required), `requestId` (required, one per message), and the researcher's current `query`, `facets`, `search` (each optional). Returns `response` plus `query`, `facets` and `search` **only when the turn changed them**; a part that is present replaces that part of the screen whole. `query` carries only `select`, `phenotypicClause` and `genomicFilters`. |
| `GET /ai/chat/{requestId}` | The stored reply to an earlier message: 200 with the reply, 202 while it is still being answered, 404 when nothing is stored (never arrived, failed, or expired). This is the retry path after a dropped connection. |

There is deliberately no `consents` field: access control is solely a function of the caller's JWT.

A `requestId` runs at most once per caller. Sending it again returns the stored reply without calling the model; reusing it for a
different message or screen state is a 422, and the same message under a new `requestId` while the first is still running is a 409.
Error statuses and `errorType`s are listed in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#6-error-contract).

## Who may use the assistant

Three layers decide whether a user gets the assistant. All must allow it.

| Layer | Where | Effect |
| --- | --- | --- |
| Deployment | `AI_ENABLED` (this service) | `false`: no chat endpoint exists, so both routes answer **404** for everyone. |
| Feature flags | `ENABLE_ASSISTED_SEARCH` (Explorer), `ENABLE_ASSISTED_SEARCH_DISCOVER` (Discover) in the UI | Whether the UI offers the assistant on that page. Independent of `AI_ENABLED` and of the privilege: some environments grant the privilege but do not want the feature. The service never sees these flags. |
| User privilege | This service | The caller must hold the assisted-search privilege, or both routes answer **403**. |

The privilege is `ASSISTED_SEARCH`, the same name in every deployment and for both pages. The service does not tell Explorer from Discover. Which page shows the assistant is the UI's
flags. The privilege is granted through roles like any other, and is created in PSAMA by a migration per deployment line (nothing in this
repo seeds it): add it to a role and give the role to the users.

Enforcement is a `@PreAuthorize` guard on both handlers (`ChatController`) against the `X-User-Privileges` header the gateway sets after
PSAMA introspection, read by `GatewayPrivilegesFilter`. The service does no access-rule evaluation of its own. `GET /ai/chat/{requestId}`
requires the same privilege as the `POST`. The guard runs after the `Authorization` (401) and body (400) checks and before anything else,
so a rejected caller never reaches the model, the MCP gateway or the turn store.

**The assistant's own MCP calls rely on `ASSISTED_SEARCH`, not on `PIC_SURE_MCP`.** To answer a turn the service calls the dictionary and count tools on `pic-sure-mcp` through the gateway's `/mcp` route, replaying the user's token. `/mcp` otherwise requires `PIC_SURE_MCP`, which assistant users do not get from `ASSISTED_SEARCH` (so they cannot connect their own model to the same counts). Every MCP call therefore also carries `X-PIC-SURE-AI-TOKEN` (`AI_SERVICE_TOKEN`, shared with the gateway). The gateway verifies it, checks the user's token against the assistant's PSAMA rule (`AR_AI_SERVICE_REQUESTS`, which `ASSISTED_SEARCH` carries) instead of the `/mcp` one, and `pic-sure-mcp` admits the call as `ai-service`. A user with `ASSISTED_SEARCH` is the only one who gets that far, because this service's own guard runs first. A blank or mismatched token makes every tool call fail: the gateway answers 401 for a wrong value and `pic-sure-mcp` answers 403 for none.

**Open-access (unauthenticated) callers are rejected with 403.** They carry no privileges, and the UI shows the assistant only to
signed-in users holding the privilege, so there is no open-access assistant. A request that reaches the service with no identity headers
is rejected the same way.

## Conversation history

Conversations are kept in this instance's memory (Caffeine), keyed by the caller's identity and `conversationId`. The model reads each
earlier message and final reply, not the earlier tool exchanges; the stored tool results only rebuild what the guards have seen
(concept paths, facets, unmatched terms), so an earlier lookup does not force a re-search. The screen state sent on the latest
message always wins over anything an earlier turn described, and what the researcher removed by hand since the last proposal is
passed to the model so it does not add it back.

- **State is lost on restart and is not shared between instances.** Run one instance, or sticky sessions, until a shared store
  exists (a new implementation of `ConversationStore` / `TurnResultStore`, selected by `AI_CONVERSATION_STORE`).
- A failed turn, or one that ends in the fallback text, is not stored, so a retry starts from the same place.
- The owner key is the gateway user id for logged-in callers, the session id inside the `picsure_s_` token for open access (unreachable while open-access callers are rejected; the
  token is reissued every few minutes, the session id is not), and a hash of the token for anything else.

> [!WARNING]
> Deploy this service only behind the gateway; never expose it directly to clients. Conversation history and stored turn results are
> keyed by the caller's identity, which the service reads from the `X-User-Id` and `X-Picsure-Access-Type` headers the gateway sets
> after token introspection. The service does not authenticate the gateway itself, so anyone who can reach it directly could supply
> another user's identity headers and read that user's stored replies.

## Tools the model is offered

The gateway's MCP tools (from `pic-sure-mcp`) plus one local tool, minus exclusions:

- `search_concepts`, `list_facets`, `get_concept`, `get_concepts`: always.
- `count_participants`, `cross_count`: obfuscated open-access counts that ignore consents. **Withheld from logged-in
  (`authorized`) callers**, whose results panel already shows exact counts; their system prompt says the assistant cannot count.
  Open-access callers would get them, with a prompt that says counts are obfuscated, but they are rejected with 403 before the tool set is chosen (see [Who may use the assistant](#who-may-use-the-assistant)), so that path is currently unreachable through the gateway.
- `get_adapter_code`: withheld by default (`AI_MCP_EXCLUDED_TOOLS`).
- `propose_query` (local, never sent to the gateway): proposes a query, facet selection and/or search for the researcher to review.
  The selected variables are a change (`addSelect` / `removeSelect`), not a list.

When changing what `pic-sure-mcp` exposes, check both sides' current code (argument names especially) rather than trusting older
test-run logs: the two services are developed in the same tree and have drifted before.

## Configuration

| Variable | Required | Purpose |
| --- | --- | --- |
| `AI_ENABLED` | no, default `false` | `true` registers the chat endpoint. Left `false`, no chat endpoint exists, so `/ai/chat` and `/ai/chat/{requestId}` answer 404 while the service stays up. The service still starts as it would enabled, so `PICSURE_GATEWAY_URL` is required even then (see below). Set it to `true` wherever the assistant should be offered. The UI is switched separately (`ENABLE_ASSISTED_SEARCH` for Explorer, `ENABLE_ASSISTED_SEARCH_DISCOVER` for Discover), and users also need the assisted-search privilege: see [Who may use the assistant](#who-may-use-the-assistant). |
| `AI_MODEL_PROVIDER` | no, default `aws-sdk` | `aws-sdk` or `http` — which `ConverseModelClient` is active. |
| `BEDROCK_REGION` | no, default `us-east-1` | AWS region, `aws-sdk` provider only. |
| `BEDROCK_MODEL_ID` | no, default `openai.gpt-oss-120b-1:0` | Bedrock model id, `aws-sdk` provider only. The model must support Converse tool use and be in the deployment's IAM allowlist — see [Choosing a model](docs/MODEL.md#choosing-a-model). |
| `BEDROCK_MAX_TOKENS` | no, default `1024` | Max output tokens per Converse call, `aws-sdk` provider only. 1024 truncated answers mid-sentence in testing; 4096 was used for the runs in [docs/MODEL.md](docs/MODEL.md#choosing-a-model). |
| `BEDROCK_SOCKET_TIMEOUT` | no, default `30s` | How long a Bedrock call may go without a response before the AWS SDK retries it, `aws-sdk` provider only. Lowered from the SDK's 90s because some calls stall and then succeed at once on retry; see [Known limits](docs/GUARDS.md#known-limits-not-handled-by-the-service). |
| `SPRING_AI_BEDROCK_CONVERSE_CHAT_OPTIONS_TEMPERATURE` | no | Optional sampling temperature (Spring AI's own property, not read by this service's code). Not needed for `openai.gpt-oss-120b-1:0`, which called tools correctly without it. |
| `AI_MODEL_HTTP_BASE_URL` | yes, if `AI_MODEL_PROVIDER=http` | Base URL the Converse POST is sent to. |
| `AI_MODEL_HTTP_API_TOKEN` | yes, if `AI_MODEL_PROVIDER=http` | Bearer token sent as `Authorization`. |
| `AI_MODEL_HTTP_MODEL_ID` | yes, if `AI_MODEL_PROVIDER=http` | Model id substituted into `/model/{modelId}/converse`. |
| `AI_MAX_TOOL_ITERATIONS` | no, default `8` | Hard cap on tool-call round-trips per chat turn. Reaching it costs one more model call, asking the model to answer from what it has (see [Guarding against model behavior](docs/GUARDS.md)), so a turn makes at most this many plus one model calls. |
| `AI_MAX_FILTERS` | no, default `100` | Most filters (subqueries count too) a query may hold and still be read or proposed by the model; at least 1. Over it, the researcher's query is withheld from the model and the model's own proposals are refused (see [Guarding against model behavior](docs/GUARDS.md)). The UI has no such limit: it only bounds what is resent to the model on every call, and the model is told the limit is its own. |
| `AI_MCP_MODE` | no, default `gateway` | `gateway` (a real MCP client against `pic-sure-mcp`). |
| `AI_SERVICE_TOKEN` | yes, if `AI_MCP_MODE=gateway` | Shared secret sent as `X-PIC-SURE-AI-TOKEN` on every `/mcp` call, so the gateway admits the assistant by `ASSISTED_SEARCH` and not `PIC_SURE_MCP`. Must equal the gateway's `AI_SERVICE_TOKEN`. |
| `PICSURE_GATEWAY_URL` | yes, if `AI_MCP_MODE=gateway` | Base URL of the gateway's `/mcp` route. Same gateway URL `pic-sure-mcp` itself binds via `picsure.mcp.gateway-url` — one gateway URL, shared across both services' deploy config. |
| `AI_MCP_CONNECT_TIMEOUT` | no, default `5s` | Connect timeout for gateway MCP calls. |
| `AI_MCP_READ_TIMEOUT` | no, default `20s` | Read timeout for gateway MCP calls. Without one the HTTP client waits forever, so a stuck dictionary call would hang the whole chat turn. |
| `AI_MCP_EXCLUDED_TOOLS` | no, default `get_adapter_code` | Comma-separated gateway tool names withheld from the model, and refused if it requests one anyway. The default drops the adapter-code tool, which the chat UI has no use for and whose long definition is resent on every model call (tool definitions were most of the ~4,900 input tokens per call). Set it blank to offer every tool. |
| `AI_CONVERSATION_STORE` | no, default `memory` | Where conversations and turn results live. `memory` is the only implementation: lost on restart, not shared between instances. |
| `AI_CONVERSATION_IDLE_TIMEOUT` | no, default `60m` | A conversation idle this long is dropped. Also bounds how stale a replayed lookup can be if the caller's access changes. |
| `AI_CONVERSATION_MAX_TURNS` | no, default `50` | Turns kept per conversation; the oldest go first (and their provenance with them: a path from a dropped turn is refused, never wrongly allowed). |
| `AI_CONVERSATION_MAX_TOTAL_CHARS` | no, default `50000000` | Bound on everything all conversations hold, in characters; least recently used go first. |
| `AI_CONVERSATION_RESULT_TTL` | no, default `60m` | How long a turn's reply stays retrievable by `requestId`. Must be at least the idle timeout; startup fails otherwise, since a late retry would run the turn twice. |
| `AI_CONVERSATION_MAX_RESULTS_PER_OWNER` | no, default `200` | Most turn replies kept for one caller; the oldest go first. A caller past this can run an old `requestId` again, but cannot push out anyone else's replies. |
| `PICSURE_ACTUATOR_EXPOSURE` | no, default `none` (the Docker image sets `health`) | Same actuator-gating convention as every other PIC-SURE service. |
| `PICSURE_ACTUATOR_REQUIRE_TOKEN` | no, default `true` | Whether `/actuator/**` needs the `X-Application-Token` header. |
| `PICSURE_APPLICATION_TOKEN` | no | `X-Application-Token` value for `/actuator/**`. |
| `SERVER_PORT` | no, default `8080` | The context path is always `/ai`. |

The `aws-sdk` provider needs AWS credentials; see [docs/AWS_CREDENTIALS.md](docs/AWS_CREDENTIALS.md). To change provider or model, see [docs/MODEL.md](docs/MODEL.md).

Misconfiguring the `http` provider (any of its three variables blank while selected), or leaving
`PICSURE_GATEWAY_URL` or `AI_SERVICE_TOKEN` blank while `AI_MCP_MODE=gateway`, fails fast at startup with a clear message,
rather than silently resolving to a blank URL or token.

## Build and test

JDK 25 is enforced. Build from the repository root, not from inside the module: the installed `pic-sure-spring-commons` jar can lag
the source, so `-am` is needed to build the siblings too.

```
mvn -pl services/pic-sure-ai-service -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='edu.harvard.hms.dbmi.avillach.ai.**.*Test'
```

The `-Dtest` pattern keeps upstream modules' tests from running. `ArchitectureTest` fails the build if anything outside
`ai.model` touches Bedrock or Spring AI types. Both this module and `pic-sure-mcp` are Spotless-clean and CI runs `spotless:check` on them, so run
`mvn -pl services/pic-sure-ai-service spotless:apply` before you push.

Model-behavior regressions are checked with a separate chat-test runner (a suite of single- and multi-turn questions run against a
locally built service); it is not part of this repo. Unit tests cannot show them.


# pic-sure-ai-service

`pic-sure-ai-service` is the AI-assisted-search chat endpoint: it runs the Bedrock Converse tool-use
loop for `POST /ai/chat` (reachable externally as `POST /picsure/ai/chat` once the outer `/picsure`
prefix in front of the gateway applies) and acts as an MCP client against the gateway's MCP endpoint,
replaying the caller's bearer JWT untouched on every call. It never mints, caches, or exchanges a
credential of its own.

## Model provider: only the Bedrock Converse shape, by design

This service speaks exactly one wire shape to the reasoning model: Amazon Bedrock's Converse API
(`messages`/`system`/`toolConfig`/`inferenceConfig` in, `output.message`/`stopReason`/`usage` out).
That's a deliberate scope choice, not an oversight — Anthropic's Messages API and OpenAI's Chat
Completions API are each a *different* JSON shape (different field names for the stop signal, the tool
call, and the tool result). Modeling three shapes inside this service would be three times the surface
to maintain for a POC that only needs one.

**Two implementations of the one `ConverseModelClient` interface, picked by `picsure.ai.model.provider`:**

| `provider` | Class | Transport |
| --- | --- | --- |
| `aws-sdk` (default) | `ConverseSdkModelClient` | AWS SDK, SigV4/IAM |
| `http` | `ConverseHttpModelClient` | plain HTTP (`RestClient`), `Authorization: Bearer <token>` |

### Why two implementations, not one — they serve different deployments, not the same case twice

- **`aws-sdk` is this project's own production path.** It uses the AWS SDK's default credential
  chain — an IAM role attached to whatever compute runs the service — so there's no static secret to
  leak, rotate, or commit; credentials rotate automatically. Use this when PIC-SURE itself is running
  in the AWS account that has Bedrock access.
- **`http` exists for a different deployment entirely: a self-hosted PIC-SURE instance that wants a
  model this project doesn't run on AWS for.** It trades IAM's auto-rotating credentials for a static
  bearer token, which is a real downgrade for the production case above — don't switch the AWS
  deployment to `http` to "simplify" it. It earns its place only for the self-host case, where there's
  no IAM role to assume in the first place.

Neither implementation replaces the other; removing `aws-sdk` would delete the project's one tested,
credential-safe production path, and removing `http` would delete the self-host story below.

### If you want a different model vendor on your own PIC-SURE deployment

Real Bedrock's Converse contract (`POST {base}/model/{modelId}/converse`) isn't actually SigV4-only —
Bedrock also supports a bearer-token API-key mode on the same endpoint. `ConverseHttpModelClient`
speaks exactly that bearer-token form of the *same* JSON shape `ConverseSdkModelClient` uses, against a
`base-url` you configure — so "support a different model" never means touching this service's code.
Two ways to use it:

1. **Run [LiteLLM](https://docs.litellm.ai/docs/bedrock_converse) as the proxy.** Its proxy server
   exposes a genuine Converse-shaped, bearer-token-authenticated endpoint at
   `POST /bedrock/model/{model}/converse`, and translates to whatever backend you configure it for —
   Anthropic, OpenAI, a local Ollama model, etc. Point `picsure.ai.model.http.base-url` at your LiteLLM
   instance (including whatever path prefix it serves that route under) and `model-id` at the model name
   you configured there.
2. **Build your own small translating proxy.** Accept the same Converse JSON on one side, call
   whatever API your model actually speaks on the other, and translate the response back. This is more
   work than (1) but gives you full control, e.g. if you need request/response shaping LiteLLM doesn't do.

**What does *not* fit this story:** [OpenRouter](https://openrouter.ai/docs/quickstart) only exposes an
OpenAI-compatible `/chat/completions` endpoint — it has no Converse-shaped ingress at all. Pointing
`ConverseHttpModelClient` at it won't work; it would need a third `ConverseModelClient` implementation
speaking OpenAI's shape instead (a real option if OpenAI-compatible models become a real need here —
that's exactly the kind of self-contained vendor-specific client this interface was built to add without
touching the dispatch loop, the controller, or anything else in the service).

## Configuration

| Variable | Required | Purpose |
| --- | --- | --- |
| `AI_MODEL_PROVIDER` | no, default `aws-sdk` | `aws-sdk` or `http` — which `ConverseModelClient` is active. |
| `BEDROCK_REGION` | no, default `us-east-1` | AWS region, `aws-sdk` provider only. |
| `BEDROCK_MODEL_ID` | no, default `openai.gpt-oss-20b-1:0` | Bedrock model id, `aws-sdk` provider only. The model must support Converse tool use and be in the deployment's IAM allowlist — see [Choosing a model](#choosing-a-model). |
| `BEDROCK_MAX_TOKENS` | no, default `1024` | Max output tokens per Converse call, `aws-sdk` provider only. 1024 truncated answers mid-sentence in testing; 4096 was used for the verified runs below. |
| `BEDROCK_SOCKET_TIMEOUT` | no, default `30s` | How long a Bedrock call may go without a response before the AWS SDK retries it, `aws-sdk` provider only. Lowered from the SDK's 90s because some calls stall and then succeed at once on retry; see [Known limits](#known-limits-not-handled-by-the-service). |
| `SPRING_AI_BEDROCK_CONVERSE_CHAT_OPTIONS_TEMPERATURE` | no | Optional sampling temperature (Spring AI's own property, not read by this service's code). Not needed for `openai.gpt-oss-120b-1:0`, which called tools correctly without it. |
| `AI_MODEL_HTTP_BASE_URL` | yes, if `AI_MODEL_PROVIDER=http` | Base URL the Converse POST is sent to. |
| `AI_MODEL_HTTP_API_TOKEN` | yes, if `AI_MODEL_PROVIDER=http` | Bearer token sent as `Authorization`. |
| `AI_MODEL_HTTP_MODEL_ID` | yes, if `AI_MODEL_PROVIDER=http` | Model id substituted into `/model/{modelId}/converse`. |
| `AI_MAX_TOOL_ITERATIONS` | no, default `8` | Hard cap on tool-call round-trips per chat turn. Reaching it costs one more model call, asking the model to answer from what it has (see [Guarding against model behavior](#guarding-against-model-behavior)), so a turn makes at most this many plus one model calls. |
| `AI_MCP_MODE` | no, default `gateway` | `gateway` (a real MCP client against `pic-sure-mcp`) or `mock`. |
| `PICSURE_GATEWAY_URL` | yes, if `AI_MCP_MODE=gateway` | Base URL of the gateway's `/mcp` route. Same gateway URL `pic-sure-mcp` itself binds via `picsure.mcp.gateway-url` — one gateway URL, shared across both services' deploy config. |
| `AI_MCP_EXCLUDED_TOOLS` | no, default `get_adapter_code` | Comma-separated gateway tool names withheld from the model, and refused if it requests one anyway. The default drops the adapter-code tool, which the chat UI has no use for and whose long definition is resent on every model call (tool definitions were most of the ~4,900 input tokens per call). Set it blank to offer every tool. |
| `PICSURE_ACTUATOR_EXPOSURE` | no, default `none` | Same actuator-gating convention as every other PIC-SURE service. |
| `PICSURE_APPLICATION_TOKEN` | no | `X-Application-Token` value for `/actuator/**`. |

Misconfiguring the `http` provider (any of its three variables blank while selected), or leaving
`PICSURE_GATEWAY_URL` blank while `AI_MCP_MODE=gateway`, fails fast at startup with a clear message,
rather than silently resolving to a blank URL or token.

## Choosing a model

This service's loop needs the model to return real Converse `toolUse` blocks. Not every Bedrock model
does, even when the request is correct. Findings from testing `POST /ai/chat` with the question "Can you
help me find variables related to BMI?" against the NHANES dev dictionary, then (for the Voxtral rows) "Can you help me
find women aged 40–65 with a BMI measurement?", which needs several searches and a count:

| Model | Result |
| --- | --- |
| `openai.gpt-oss-120b-1:0` | **Works; the model the service is tuned against.** Full five-call loop (several `search_concepts` calls, then a grounded answer); no temperature override needed. The prompt and guards were tuned on it, where most tests pass in nearly every run and typical turns take 3–10 s. |
| `openai.gpt-oss-20b-1:0` | **Works.** The women-aged-40–65 question took 6 model calls (`sex`, `age`, `BMI` with no match, retry as `body mass index`, then `count_participants` with real paths) and a final answer that did include the obfuscated/consent caveat and read the suppressed count correctly. Final input was about 4k tokens. It is the cheapest model that has passed. Its working notes show up as assistant text, and in this run the first one leaked into the reply ("...with suppression. I'll explain.The open-access count..."). In general, it was **less accurate than 120b and about twice as slow per request**, so it saves no time. It did worse on multi-topic questions, and sometimes adds facets the researcher never asked for. |
| `mistral.voxtral-small-24b-2507` | **Works.** The women-aged-40–65 question took 3 model calls: three parallel short `search_concepts` calls (`sex`, `age`, `body mass index`), a `count_participants` built only from the returned paths, then a prose answer. Input grew from about 3.7k to 5.5k tokens. It left out the "obfuscated, ignores consents" caveat the system prompt asks for. |
| `mistral.voxtral-mini-3b-2507` | **Works but shallow.** It calls tools, but sends whole-sentence searches that match nothing, copies the `phs999999` placeholder paths from tool examples into calls, and stalls or hits the 8-iteration cap. The unseen-concept-path check and the tool-description labels exist largely because of this model; with them it still did not finish the question. |
| `mistral.mistral-large-3-675b-instruct` | Returned a correct `toolUse` (`search_concepts`) on the first call, tested at temperature 0. The full loop has not been run. |
| `amazon.nova-lite-v1:0`, `amazon.nova-pro-v1:0` | **Do not use for this.** The request is correct — the logged `ConverseRequest` carried the full system prompt and all seven tools — but the model ignores the tool config and writes a made-up answer or a pseudo-call as plain text (`Action: PICSURE_search_dictionary(...)`, a tool that does not exist), with `stopReason=end_turn`. Reported input tokens stayed at 81 regardless of request size. Tested at temperature 0.7 and 0. |

Listed in `bedrock-infra/poc.tfvars` but not yet tested: `mistral.ministral-3-8b-instruct`.

Not tried: Nova with the three `$defs`/`$ref`/`anyOf`-schema tools removed (`count_participants`,
`cross_count`, `get_adapter_code`), which would show whether Nova is rejecting those schemas; `amazon.nova-2-lite-v1:0`
(inference-profile-only); and the inference-profile-only OpenAI, Kimi, and Grok models.

A model must also be in the deployment's allowlist: the IAM role from `bedrock-infra/` only permits the model
ids in its `allowed_model_ids`, and anything else fails with a 403 (`... is not authorized to perform:
bedrock:InvokeModel`), which surfaces from this service as `model_unavailable`.

### Telltale signs of a model that is not using its tools

The final response is prose that describes searching ("Searching the data dictionary...") or lists
results that no tool returned; `toolCalls=[]` on the first `Model call #1 result` log line; no `Tool call requested:` log line appears.
Check the raw `ConverseResponse` at DEBUG: a model that is using tools returns `stopReason=tool_use` and a
`ToolUse` content block.

## Debugging a chat turn

`ToolUseLoopService` logs each turn, with no payloads at the default level.

- **INFO**: turn start (conversation and request ids, the tools offered, the iteration cap); each model call result
  (`done`, tool names requested, text length, input and output tokens); each tool call (a `Tool call requested` line with name and id before it runs, so a hung call is visible, then name, id, whether it errored,
  result size); turn end (iterations, whether the cap was hit, input and output tokens summed over every model call; input is the billed total, since each call resends the history, and the tools called in order as `name(N chars)`, e.g. `search_concepts(4210 chars), count_participants(95 chars)`, which makes an oversized result easy to spot. Those are characters of result text, not tokens: the model reports tokens only per call, and JSON with backslashes typically costs well over one token per four characters). A refused concept path, or arguments that are not valid JSON, show up as `error=true`.
- **DEBUG** (`LOGGING_LEVEL_EDU_HARVARD_HMS_DBMI_AVILLACH_AI_CHAT=DEBUG`): additionally the model's text and each tool
  call's arguments and result content (including the error text when a call is refused). This includes researcher text
  and dictionary output, so keep it off outside local debugging.
- **Raw Bedrock response**: `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_AI_BEDROCK=DEBUG` makes `BedrockProxyChatModel` log the
  full `ConverseResponse`, the quickest way to see whether the model returned `toolUse` or only text.
  `LOGGING_LEVEL_SOFTWARE_AMAZON_AWSSDK_REQUEST=DEBUG` logs each AWS call's endpoint and request id, not its body.
- **The outgoing request body is not logged by Spring AI.** If you need to confirm that the system prompt and
  `toolConfig` actually left the service, register an AWS SDK `ExecutionInterceptor` that logs
  `context.request()` (a global one can be registered through the classpath resource
  `software/amazon/awssdk/global/handlers/execution.interceptors`). It was used to verify the table above and
  then removed, because it logs full researcher text.

### Implementation notes worth knowing

- Spring AI's `BedrockProxyChatModel` returns one `Generation` per Converse content block, with tool calls in their own
  `Generation`, often not the first one (a model's reasoning block can come first). `ConverseSdkModelClient` therefore
  reads every generation in `response.getResults()`; reading only `getResult()` silently drops the tool call and the
  turn ends with an empty response.
- The tool options are built as `BedrockChatOptions` rather than the generic `ToolCallingChatOptions`, so Spring AI does not
  route them through `ModelOptionsUtils.copyToTarget`. This was done on suspicion and not proven necessary.
- Each `POST /ai/chat` starts a fresh history from the single incoming message. Within a turn, every model call
  replays the full loop history (model tool calls and tool results), but nothing is kept between requests for the same
  `conversationId`.
- `search_concepts` is a dictionary text search: "BMI" returned no results on the NHANES dictionary while "body mass index"
  matched. The model recovered by retrying; that matching behavior is on the dictionary side.

## Guarding against model behavior

The prompt alone does not keep a small model (`openai.gpt-oss-20b-1:0` in particular) on track, so the service treats every
tool call and reply as untrusted and enforces the rules in code. Each guard either **refuses** the call (the model gets a tool
error it can repair, and nothing it sent is kept) or **repairs** it without a round-trip. The UI hands a returned `query`
straight to its execution logic, so nothing that failed a check is ever returned.

| What the model does | What the service does | Kind | Where |
| --- | --- | --- | --- |
| Invents a concept path, often by copying the `phs999999` placeholder from a tool example | Refuses any call using a path that no tool result or the researcher's current query contained | refuse | `ConceptPaths`, `ToolUseLoopService.callToolSafely` |
| Writes a known path with doubled backslashes, or without the leading or trailing one | Treats it as the same path, and returns it to the UI exactly as the tool returned it | repair | `ConceptPaths.canonical` |
| Sends tool arguments that are not valid JSON (usually unescaped backslashes) | Refuses, with a hint to escape backslashes as `\\` | refuse | `ConceptPaths.isMalformed` |
| Leaks chat-template tokens into a tool name (`search_concepts<\|channel\|>commentary`) or misspells it (`search_conconcepts`) | Trims the junk and snaps to the clearly closest tool name. Left as-is, Bedrock rejects the whole conversation when the name is replayed | repair | `ConverseSdkModelClient.toolName`, `RequestedToolCall` |
| Sends `{}` for a `propose_query` field it means to leave out | Reads it as not sent | repair | `ProposeQueryTool.dropEmptyPlaceholders` |
| Sends a `propose_query` with no `query`, `facets` or `search` at all | Refuses: "The proposal is empty" | refuse | `ProposeQueryTool.propose` |
| Proposes facets without calling `list_facets` first | Calls `list_facets` itself (once a turn, with the proposal's search text), then checks the facets against it | repair | `ToolUseLoopService.lookUpFacetsIfNeeded` |
| Proposes a facet `list_facets` never returned | Refuses | refuse | `KnownFacets` |
| Narrows a `search_concepts` call by a facet `list_facets` never returned | Looks facets up once a turn if none were listed, then refuses the call without reaching the dictionary | refuse | `ToolUseLoopService.refuseUnknownFacets`, `KnownFacets` |
| Reads a facet-narrowed search but proposes (or claims on screen) only the search text | Adds the facets of the last matching search when every proposed term was in it, so the table shows what the model read. Facets the model sends itself, even an empty list, are kept | repair | `ToolUseLoopService.callToolSafely`, `TurnState.facetsForSearch` |
| Calls `propose_query` for a lookup ("show me BMI"): a query with no filters, and the researcher has none either | Reads it as the search that found the variables (the proposal's own `search`, else the last search that matched). Refuses, telling the model to propose only `search`, only when there is no such search | repair | `ToolUseLoopService.callToolSafely` |
| Sends `select`/`phenotypicClause`/`genomicFilters` at the top level of `propose_query` instead of inside `query` | Moves them into `query` | repair | `ProposeQueryTool.liftQueryFields` |
| Proposes a query with no filters while the researcher has some (e.g. "select all race variables" with SEX and AGE filters on screen) | Keeps the existing filters. Without this, the proposal replaced the whole query and silently changed the cohort | repair | `ToolUseLoopService.callToolSafely` |
| Proposes only the variables it is adding ("select all race variables" came back without the BMI already selected), or re-sends only the filters on a narrowing follow-up | Keeps the researcher's current `select` and adds the proposed variables after it. The proposed `select` is taken as the whole list only when the message asks to remove or replace something (remove, drop, replace, instead, without, only, ...) | repair | `ToolUseLoopService.keepingCurrentSelect` |
| Leaves a filtered concept path out of `select` | Adds it back (the UI exports every variable it filters on) | repair | `ProposeQueryInput.withFilterPathsSelected` |
| Proposes exactly what the researcher already has | Leaves `query`/`facets`/`search` out of the response | repair | `ToolUseLoopService.respond` |
| Writes query JSON, or `query:`/`facets:`/`search:` labels, into its reply | Scrubs them out. If nothing is left, the reply becomes "I've prepared an updated search for you to review." (proposal attached) or the fallback text (nothing attached) | repair | `ReplyText.withoutQueryJson` |
| Drops a known path's trailing (or leading) backslash in a backticked path in its reply | Puts the path back exactly as the tool returned it | repair | `ReplyText.withKnownPaths` |
| Names a path in its reply that no tool returned: a categorical value written as a path of its own (`...\G47 Sleep disorders\G47.9 Sleep disorder, unspecified\`), a parent of returned paths, or an invented one | Asks the model once, within the iteration cap, to rewrite its reply, saying what each path is in terms of the results (e.g. "is one of the values of `...\G47 Sleep disorders\`"). A reply still wrong after that goes out as written | repair (one round-trip) | `ReplyText.unknownPaths`, `ConceptPaths.ownerOfValueIn`, `ToolUseLoopService.pathRepairMessage` |
| Says the variables are "now selected" after a lookup the service applied as a search | The `propose_query` result says what was applied ("Their table now lists the variables matching ... nothing was added to them") instead of a bare `received` | prevent | `ToolUseLoopService.accepted` |
| Counts the cohort with `count_participants`, says the query changed ("I included BMI in the query"), but never calls `propose_query` | Runs the counted query through every check a real `propose_query` call gets. Only a sentence saying the query changed counts, not an offer ("let me know if you'd like ...") | repair | `ReplyText.claimsQueryChanged`, `ToolUseLoopService.proposeCountedQuery` |
| Answers from one search, naming paths from it, but proposes nothing (often listing only some of the matches) | Proposes that search, so the table shows every match. With more than one matching search it does not guess | repair | `ToolUseLoopService.respond` |
| Keeps retrying a tool that fails with the same error (even with different arguments) | Adds "retrying will not help" to the second identical error; on the third, stops tool use and asks for an answer, the same way as the iteration cap | repair | `ToolUseLoopService` (`MAX_REPEATED_FAILURES`) |
| Writes its proposal as the reply instead of calling `propose_query` | Runs that JSON through every check a real `propose_query` call gets; if any fails, nothing is proposed | repair | `ReplyText.proposalArguments`, `ToolUseLoopService.proposeFromReply` |
| Is still calling tools when the iteration cap is reached (typically trying search term after search term that matches nothing) | Answers its pending calls with a "limit reached, answer now from what you have" result instead of running them, and makes one last model call. Tools stay offered, because Converse rejects a history with tool calls and no `toolConfig`; if the model calls one anyway, the reply is the fallback text | repair | `ToolUseLoopService` (`CAP_REACHED_RESULT`) |
| Ends its turn with no content right after tool results | Asks again, up to 2 times, then replies with "I wasn't able to finish gathering the information..." | repair | `ToolUseLoopService` (`MAX_EMPTY_RETRIES`) |

Two consequences worth knowing:

- **Clearing every filter is not possible from the chat.** A proposal with no filters keeps the current ones, so a researcher who
  wants to start over uses the UI's reset button. Removing *some* filters works, because the model sends the ones that stay.
- **The researcher's query, facets and search are appended to their message** as labeled data (`CurrentState`), so the model can
  build on what is on screen. They go in the user turn rather than the system prompt so the system prompt stays identical and cacheable.
  The UI's empty starting query (nothing selected, no filters) is left out: shown, it reads to the model as a query on screen for it
  to change, which pushes lookups toward `propose_query`.

### Known limits not handled by the service

Seen with `openai.gpt-oss-20b-1:0` in testing; prompt changes have not fixed them, and the service either cannot detect them or
only blocks their harmful form:

- **Prose is only partly checked.** Backticked paths are corrected (see the table), but only once a turn, and a path written without
  backticks is not checked at all. Working notes can still leak in ("We have one concept."), and a reply can leave out matches it was
  asked to list in full; the proposed search makes up for the latter on screen.
- **It skips `list_facets`** for questions about the dictionary's structure or "all" variables of a kind, and answers from a paged
  search instead. (`list_facets` returns the dataset and variable-type categories, so a request to narrow by one is expected to use them; it is not guaranteed to.)
- **It runs one search per model call**, so a message naming several terms can use up the iteration cap (`AI_MAX_TOOL_ITERATIONS`);
  the last call then answers from whatever was found by that point.
  It sometimes sends a garbled parallel call with no arguments (`search_concepts {}`, refused as `Argument 'query' is required.`).
- **Empty-turn retries resend the identical history**, and usually get an empty turn back again. Each retry also counts toward the cap.
- **Some Bedrock calls stall** until `BEDROCK_SOCKET_TIMEOUT` and then succeed immediately on the SDK's retry. Converse is not
  streamed, so a stall means nothing came back before the timeout. The cause is not confirmed; long runaway generation is the
  leading guess. A run with a longer timeout, reading `StopReason` and `OutputTokens` from the raw `ConverseResponse` log line,
  would tell.

## AWS credentials for the `aws-sdk` provider

The service sets no access key or secret. It uses the AWS SDK's default credential chain, so the same image
runs everywhere. Credentials resolve lazily on the first Bedrock call, so a clean startup does not prove they
work; send one real chat request to check. A startup warning, `Failed to load region from
DefaultAwsRegionProviderChain, using US_EAST_1`, is harmless once `BEDROCK_REGION` is set. Set `AWS_REGION`
too if you want it gone.

Do not pass `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN` into the container. They are
short-lived and fixed at container start, so they expire while it runs. Use one of the two paths below, where
the SDK refreshes credentials itself.

### On EC2: instance profile

Nothing to configure in the container; the SDK reads the instance role from the metadata service (IMDS).

- **Hop limit.** The instance's IMDSv2 `HttpPutResponseHopLimit` must be `2`. The default is `1`, and a container on
  Docker's bridge network adds a hop, so the SDK cannot reach IMDS and fails with `Unable to contact EC2 metadata
  service`. Keep `HttpTokens = required`. For an existing instance:
  `aws ec2 modify-instance-metadata-options --instance-id <id> --http-put-response-hop-limit 2 --http-endpoint enabled`
  (also settable in the console under Actions > Instance settings > Modify instance metadata options, or in the
  launch template).
- **Role permissions.** The instance's role needs `bedrock:InvokeModel` and `bedrock:InvokeModelWithResponseStream`
  on the model or inference profile. `bedrock-infra/` provides this via `attach_invocation_policy_to_role_names`;
  confirm that the role named there is the one this instance actually uses.
- Raising the hop limit lets every container on the instance reach the instance role, not just this one.

### Locally: your authenticated AWS role

Mount your AWS config into the container instead of exporting keys, and select the profile:

- Mount `~/.aws` read-write (SSO token refresh writes to `~/.aws/sso/cache`) and set `AWS_PROFILE`,
  `AWS_CONFIG_FILE` and `AWS_SHARED_CREDENTIALS_FILE` to the mounted paths. Keep this in a local compose override,
  not the shared compose file.
- The profile must be one the SDK can refresh on its own: `role_arn` plus `source_profile`, or SSO. When the
  underlying login expires, run `aws sso login` on the host; no container restart is needed.
- An IAM Identity Center identity cannot have the invoke policy attached directly. Use the dedicated `invoke` role
  from `bedrock-infra/` (`invocation_role_enabled = true`) and reference it via `role_arn` in the profile.
- If mounting is not enough (for example `credential_process` profiles), run a credentials sidecar such as
  `amazon-ecs-local-container-endpoints` and set `AWS_CONTAINER_CREDENTIALS_FULL_URI` to it.

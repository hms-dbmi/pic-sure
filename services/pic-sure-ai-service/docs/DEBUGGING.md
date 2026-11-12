# Debugging a chat turn

Logging, what each log line means, and implementation notes. Back to the [README](../README.md).

INFO is meant for dashboards and auditing: fixed `key=value` fields, no researcher text or tool payloads.

- **Access** (`ai.access` logger): one line per HTTP request, `POST /ai/chat 200 4210ms`, the same format as the gateway's
  access log. Actuator requests are skipped.
- **`Chat turn:`** one line per turn, whether it answers or fails:
  - `outcome`: `answered`, `fallback` (no usable reply, so the canned fallback text went out) or `error`
  - `stop`: `none`, `iteration_cap` or `repeated_failure` (tool use was cut short and the model answered from what it had)
  - `proposed`: which of `query`/`facets`/`search` the response changes
  - `userId`, `durationMs`, `modelCalls`, and `inputTokens`/`outputTokens` summed over every model call (input is the billed total,
    since each call resends the history)
  - `toolCalls`, `toolErrors`, and `tools` in order as `name(N chars)`, e.g. `search_concepts(4210 chars), count_participants(95 chars)`,
    which makes an oversized result easy to spot (characters of result text, not tokens)
  - `earlierTurns`: how many stored turns of the conversation this turn started from
  - `corrections`: each time the loop stepped in for model misbehavior (`emptyReplyRetry`, `pathRepair`, `selectionRepair`,
    `proposalFromReplyText`, `countedQueryProposal`), a model-quality signal

  A failed turn logs at ERROR (WARN for a 4xx) with `status`, `errorType` (`model_unavailable`, `tools_unavailable`, `internal_error`)
  and `error`, a one-line summary in place of a stack trace: the cause chain and the innermost frame of this service's code, e.g.
  `ModelUnavailableException: Bedrock Converse call failed: Read timed out <- SocketTimeoutException @ ConverseSdkModelClient.send:54`.
- **`Model call:`** per call: `n`, `durationMs`, `done`, tool names requested, text length, input and output tokens.
- **`Tool call:`** per call: name, id, `durationMs`, `error`, result size. A refused concept path, or arguments that are not valid JSON,
  show up as `error=true`. A tool or MCP transport failure also logs a WARN with its one-line error summary.
- **Ids**: every line carries `[X-Request-Id|conversationId|chatRequestId]` after the level (the last two blank outside a turn,
  including on the access line), and Spring AI and AWS SDK lines on that thread do too, so one turn can be pulled out of a full log
  with `rg '<chatRequestId>' <logfile>`. For JSON logs, set `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` (or `logstash`); the ids then
  arrive as MDC fields.
- **DEBUG** (`LOGGING_LEVEL_EDU_HARVARD_HMS_DBMI_AVILLACH_AI_CHAT=DEBUG`): additionally the model's text, each tool
  call's arguments and result content (including the error text when a call is refused), the tools offered at turn start, and
  the detail behind each correction. This includes researcher text and dictionary output, so keep it off outside local debugging.
- **Raw Bedrock response**: `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_AI_BEDROCK=DEBUG` makes `BedrockProxyChatModel` log the
  full `ConverseResponse`, the quickest way to see whether the model returned `toolUse` or only text. It mostly repeats
  what the chat DEBUG lines show, so leave it off unless that question is open.
  `LOGGING_LEVEL_SOFTWARE_AMAZON_AWSSDK_REQUEST=DEBUG` logs each AWS call's endpoint and AWS request id, not its body; only
  worth turning on to hand a request id to AWS support, since `Model call:` already has each call's duration.
- **The outgoing request body is not logged by Spring AI.** If you need to confirm that the system prompt and
  `toolConfig` actually left the service, register an AWS SDK `ExecutionInterceptor` that logs
  `context.request()` (a global one can be registered through the classpath resource
  `software/amazon/awssdk/global/handlers/execution.interceptors`). It was used to verify the model table in [MODEL.md](MODEL.md#choosing-a-model) and
  then removed, because it logs full researcher text.

## Implementation notes worth knowing

- Spring AI's `BedrockProxyChatModel` returns one `Generation` per Converse content block, with tool calls in their own
  `Generation`, often not the first one (a model's reasoning block can come first). `ConverseSdkModelClient` therefore
  reads every generation in `response.getResults()`; reading only `getResult()` silently drops the tool call and the
  turn ends with an empty response.
- The tool options are built as `BedrockChatOptions` rather than the generic `ToolCallingChatOptions`, so Spring AI does not
  route them through `ModelOptionsUtils.copyToTarget`. This was done on suspicion and not proven necessary.
- Within a turn, every model call replays the full loop history (model tool calls and tool results). Between turns of one
  `conversationId` the model sees only earlier messages and final replies (see [Conversation history](../README.md#conversation-history)).
- `search_concepts` is a dictionary text search: "BMI" returned no results on the NHANES dictionary while "body mass index"
  matched. The model recovered by retrying; that matching behavior is on the dictionary side.

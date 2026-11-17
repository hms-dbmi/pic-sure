# Model provider and model choice

Which model the service talks to, how, and which models have worked. Back to the [README](../README.md).

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

1. **Run [LiteLLM](https://docs.litellm.ai/docs/bedrock_converse) as the proxy, for Bedrock models.** Its
   proxy server exposes a Converse-shaped, bearer-token-authenticated passthrough at
   `POST /bedrock/model/{model}/converse`. That route is for Bedrock models only; it does not translate
   Converse requests for OpenAI, Anthropic's own API, or Ollama. Point `picsure.ai.model.http.base-url`
   at your LiteLLM instance (including whatever path prefix it serves that route under) and `model-id`
   at the Bedrock model ID. For a non-Bedrock vendor, use option 2 or a proxy route with an explicit
   Converse translation contract.
2. **Build your own small translating proxy.** Accept the same Converse JSON on one side, call
   whatever API your model actually speaks on the other, and translate the response back. This is more
   work than (1) but gives you full control, e.g. if you need request/response shaping LiteLLM doesn't do.

**What does *not* fit this story:** [OpenRouter](https://openrouter.ai/docs/quickstart) only exposes an
OpenAI-compatible `/chat/completions` endpoint — it has no Converse-shaped ingress at all. Pointing
`ConverseHttpModelClient` at it won't work; it would need a third `ConverseModelClient` implementation
speaking OpenAI's shape instead (a real option if OpenAI-compatible models become a real need here —
that's exactly the kind of self-contained vendor-specific client this interface was built to add without
touching the dispatch loop, the controller, or anything else in the service).

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

Not tried: Nova with the three `$defs`/`$ref`/`anyOf`-schema tools removed (`count_participants`,
`cross_count`, `get_adapter_code`), which would show whether Nova is rejecting those schemas; `amazon.nova-2-lite-v1:0`
(inference-profile-only); and the inference-profile-only OpenAI, Kimi, and Grok models.

A model must also be in the deployment's allowlist: an IAM role only permits the model
ids, and anything else should fail with a 403 (`... is not authorized to perform:
bedrock:InvokeModel`), which surfaces from this service as `model_unavailable`.

### Telltale signs of a model that is not using its tools

The final response is prose that describes searching ("Searching the data dictionary...") or lists
results that no tool returned; `toolCalls=[]` on the first `Model call: n=1` log line; no `Tool call:` log line appears.
Check the raw `ConverseResponse` at DEBUG: a model that is using tools returns `stopReason=tool_use` and a
`ToolUse` content block.

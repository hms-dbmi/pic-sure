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
| `BEDROCK_MODEL_ID` | no, default `amazon.nova-lite-v1:0` | Bedrock model id, `aws-sdk` provider only. |
| `BEDROCK_MAX_TOKENS` | no, default `1024` | Max output tokens per Converse call, `aws-sdk` provider only. |
| `AI_MODEL_HTTP_BASE_URL` | yes, if `AI_MODEL_PROVIDER=http` | Base URL the Converse POST is sent to. |
| `AI_MODEL_HTTP_API_TOKEN` | yes, if `AI_MODEL_PROVIDER=http` | Bearer token sent as `Authorization`. |
| `AI_MODEL_HTTP_MODEL_ID` | yes, if `AI_MODEL_PROVIDER=http` | Model id substituted into `/model/{modelId}/converse`. |
| `AI_MAX_TOOL_ITERATIONS` | no, default `8` | Hard cap on tool-call round-trips per chat turn. |
| `AI_MCP_MODE` | no, default `gateway` | `gateway` (a real MCP client against `pic-sure-mcp`) or `mock`. |
| `PICSURE_GATEWAY_URL` | yes, if `AI_MCP_MODE=gateway` | Base URL of the gateway's `/mcp` route. Same gateway URL `pic-sure-mcp` itself binds via `picsure.mcp.gateway-url` — one gateway URL, shared across both services' deploy config. |
| `PICSURE_ACTUATOR_EXPOSURE` | no, default `none` | Same actuator-gating convention as every other PIC-SURE service. |
| `PICSURE_APPLICATION_TOKEN` | no | `X-Application-Token` value for `/actuator/**`. |

Misconfiguring the `http` provider (any of its three variables blank while selected), or leaving
`PICSURE_GATEWAY_URL` blank while `AI_MCP_MODE=gateway`, fails fast at startup with a clear message,
rather than silently resolving to a blank URL or token.

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

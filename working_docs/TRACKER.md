# AI-Assisted Search — Work Tracker

Oct 5, 2026 · merges what used to be `INFRA_PLAN.md` (AWS account/IAM/network/
governance) and `TASKS.md` (build-order stories with Jira-level ACs) into one
categorized list, so there's a single set of identifiers instead of two
numbered sequences (`Phase N` / `Story N`) that didn't line up with each
other or with Jira (Jira tickets were copied over from the old `TASKS.md`
stories without the "Story N" prefix). Each category below has a slug used
as its identifier; use the slug, not a number, when referring to one of
these elsewhere.

Companion to `PLAN.md` (the application/API design for `pic-sure-ai-service`)
— that doc owns the architecture/request-lifecycle/UI design and isn't
duplicated here.

## Goal

Let a user ask a natural-language question (optionally alongside their
current PIC-SURE query) and get back a chat response plus an updated query
object, with the model able to pull real PIC-SURE data on the user's behalf
via tools along the way — an NHANES dictionary chatbot POC that becomes a
FISMA-environment template.

## Decisions already made

- **Existing AWS accounts, not new ones.** The POC uses the existing NHANES
  dev AWS account; the FISMA environment uses its own existing account. No
  new account is being created for either.
- Single Region only; multi-Region and global routing are out of scope.
- About 10 users a day, possibly a selected group first. Possible later: a
  health route on `pic-sure-ai-service` that checks billing health and
  server health before the UI shows the feature (see `frontend-ui`, below).
- Models: pick two (a lower tier and a middle tier), compare them in a
  bake-off, and move to a higher tier only if answers stay poor after
  retrieval tuning. Which two models is still undecided (see Open
  questions) — and **not necessarily both Claude**. Separate, lightweight
  exploration (into SageMaker Unified Studio, not this project) found that
  Anthropic models on Bedrock can require requesting special account-level
  permission before they're usable — consistent with `model-access`'s
  existing "some providers require a one-time use-case form" caveat, now
  with a concrete data point behind it. Don't assume Claude access is
  granted; if it isn't, substitute whatever vendor's models are actually
  enabled (Amazon Nova, Meta Llama, Mistral, etc.) — nothing here depends on
  vendor, since `pic-sure-ai-service` talks to the Converse API, which gives
  one request shape across providers (see `PLAN.md`'s "Model-provider
  portability" section), and the `bedrock-infra` Terraform's
  `allowed_model_ids` is just a list of ARNs, not an Anthropic-specific
  policy.
- POC runs in a low-security environment with public data. The same
  template must later run in a FISMA-controlled environment, so build for
  the high-security case and relax for the POC.
- Existing tooling is mixed: Terraform + Jenkins in one environment,
  CloudFormation for the EC2s in another, and (believed, not confirmed)
  Terraform in the high-security environment.
- IaC choice: **Terraform**, local state for now in the low-security
  environment. Remote S3 state later (see `bedrock-infra/versions.tf`).
- Application: Docker containers on EC2.
- **Bedrock credential staging** (separate from PIC-SURE identity, which is
  always the user's passed-through JWT — see `PLAN.md`'s Auth section): the
  original staging was API-key-first, IAM-later. In practice `model-access`'s
  smoke test went straight to IAM via `bedrock-infra/` and that's confirmed
  working (see `iam-access`) — no API key was used or needed. One
  real-world wrinkle the original staging didn't anticipate: the IAM path
  isn't one mechanism, it's two, chosen per identity — see `iam-access` for
  why.

## Decisions settled as of 5 Oct 2026

| Decision | Answer | What it means for the build |
| --- | --- | --- |
| Use case | `pic-sure-ai-service` (behind `pic-sure-gateway`) runs the tool-use loop itself, calling an MCP endpoint served from the gateway for dictionary terms and concept paths, then helps a user build a data query/facets/search object for another system | The model needs tool use; the sensitive surface is the MCP endpoint and the query it produces. Whether patient/variable counts are part of that MCP surface is still unconfirmed — a coworker owns the exact tool surface; treat "counts flow to the model" as unverified, not assumed |
| Scale | About 10 users a day, possibly a selected group first | Quotas and cost are small; plan for availability and a feature flag (`enableAssistedSearch`), not throughput |
| Data | No patient-level data in prompts. MCP tool results are consent-filtered per caller already (`picsure-dictionary`'s `ConceptFilterQueryGenerator`), which is why passing the user's real bearer JWT through untouched — not a shared/synthetic credential — matters here, not just as a convenience | Lower risk than patient-level data, but not risk-free: it's access-control-sensitive, not public-and-uniform. If counts ever do get exposed, small counts can be identifying; see Risks |
| Model | A lower and a middle tier, with a higher tier held in reserve | Run a short bake-off (see `model-access`) |
| Environment | POC in a low-security environment with public data, then the same template in a FISMA-controlled one | Design the template for the high-security tier first; the POC turns controls down |
| Region | One Region only | No global routing; confirm both models are offered in that Region |

Two caveats carried over from the original drafts: Bedrock's model catalog,
quotas and compliance scope change often, so treat items marked **verify**
as things to confirm against AWS documentation on the day you build; and the
third-party figures cited below (tier pricing, a HIPAA-exclusion claim) came
from search excerpts, not full AWS page reads.

## Work categories

Status legend: **done**, **in progress**, **not started**, **ACs pending**
(scoped but not yet broken into acceptance criteria).

### `account-governance` — not started

Account, compliance and governance. Your POC uses public data, so a BAA is
not the blocker it would be for PHI, but the same template must later pass a
FISMA-controlled environment. Build the high-security version first and
relax it for the POC. Authorization and eligibility apply per service,
Region and model, so confirm them for the target environment before
choosing a model.

1. **One existing account per environment, not a new one.** Use the
   existing NHANES dev AWS account for the POC and the existing
   FISMA-controlled account for that environment, from the same
   infrastructure-as-code template, with a per-environment settings file
   for the controls that differ.
2. **Confirm the FISMA authorization path. Verify.** Ask your security
   office which AWS authorization boundary and Region the FISMA environment
   must use. AWS states Bedrock is FedRAMP High authorized in the AWS
   GovCloud (US-West) Region, and GovCloud model availability can differ
   from commercial Regions, so check that both candidate models are offered
   there.
3. **Keep the PHI path as a contingency.** If prompts ever include PHI,
   confirm the BAA and per-model HIPAA eligibility first. One third-party
   summary says AWS's HIPAA reference excludes Fable and Mythos models
   (verify).
4. **Get security review early.** Walk your security and privacy office
   through the data flow, including patient counts in tool results (if the
   MCP tool surface ends up including them) and where logs land.
5. **Set organization-level guardrails.** Use service control policies to
   limit the account to your one Region and the Bedrock actions you
   approve, and require tags on AI resources.
6. **Confirm the change-approval path.** No new account is being created,
   so this is about who approves *changes* to the existing NHANES dev and
   FISMA accounts (new IAM roles/policies, enabling Bedrock) and whether an
   existing organization baseline (SCPs) already applies.

### `iam-access` — done

Identity and access (IAM). Implemented in `bedrock-infra/` (Terraform —
`iam.tf`; see that folder's README for usage). **Applied and
smoke-tested successfully** in the POC (NHANES dev) account — `terraform
apply` succeeded, and a real Converse API call against
`amazon.nova-lite-v1:0` returned normally under the IAM path it created. See
Risks/Resolved-findings below for what that run surfaced about this
account's roles specifically.

Use IAM roles for anything beyond experimentation, and keep the roles that
manage Bedrock separate from the roles that call it.

- **Invocation role.** Grant only `bedrock:InvokeModel` and
  `bedrock:InvokeModelWithResponseStream` (and `bedrock:ApplyGuardrail` if
  you call it directly), scoped to the specific model and inference-profile
  ARNs you approve, not `bedrock:*`.
- **Admin role.** Reserve model, guardrail and logging configuration for a
  small admin group, and add permissions boundaries for the identities
  Bedrock uses.
- **Enforce guardrails through IAM.** IAM policies can require that
  invocations carry a specific guardrail, so developers can use
  pre-approved guardrails but can't weaken or bypass them. The value format
  for the `bedrock:GuardrailIdentifier` condition key
  (`enforce_guardrail_identifier` in the module) is unconfirmed — check the
  Bedrock Guardrails IAM-enforcement docs before enabling.
- **Cross-Region inference needs wider policy.** If you use inference
  profiles, your IAM policies and SCPs must allow the inference actions in
  every destination Region, or calls fail.
- **Bedrock API keys: development only.** Long-term API keys are quick for
  prototyping, but prefer short-lived role credentials in shared or
  production use, and never commit keys to a repository.
- **Separate log access.** Treat invocation logs as sensitive: create
  distinct roles for reading logs, administering log data-protection
  policies, and the service role that writes logs.
- **Which role does the attachment target?** The module attaches the
  invocation policy to an *existing* role by name
  (`attach_invocation_policy_to_role_names`). Nothing has verified what role
  `pic-sure-ai-service` actually runs under — it's modeled on
  `pic-sure-operations-service`'s build/Docker conventions (see
  `service-scaffold`), but confirm the actual deployment role rather than
  assuming it matches.

### `networking-encryption` — not built

Networking and encryption. Without private connectivity, calls from your
VPC leave through an internet gateway and reach Bedrock's public endpoint;
AWS PrivateLink keeps that traffic on the AWS network. If prompts could
contain sensitive data, treat PrivateLink as required rather than optional.

- **Interface VPC endpoints.** Create endpoints for `bedrock-runtime`
  (inference) and `bedrock` (control plane, for the admin role). Skip
  `bedrock-agent`/`bedrock-agent-runtime` — those are for Bedrock
  Agents/Knowledge Bases, and `PLAN.md` explicitly runs the tool-use loop
  app-side in `pic-sure-ai-service` rather than delegating to AgentCore (a
  portability decision), so there's no Agents/Knowledge-Base surface to
  connect to.
- **Endpoint policies.** Restrict each endpoint to the roles and model ARNs
  you approved, and pair with IAM conditions so Bedrock actions that don't
  come through the endpoint are blocked.
- **Private subnets.** Run the calling application in private subnets with
  no route to the public internet for Bedrock traffic.
- **Encryption.** Data is encrypted in transit and at rest by default. Use
  a customer-managed KMS key for anything you control: log buckets,
  CloudWatch log groups, Knowledge Base sources, and agent session data.
- **Any vector store must be eligible too.** If you add retrieval (RAG),
  the vector store and source buckets must also be HIPAA eligible when PHI
  is involved; embed de-identified data where you can.
- **A Bedrock VPC endpoint with private DNS changes routing for every
  workload in that VPC that calls Bedrock**, and the endpoint policy then
  applies to them. Check for existing Bedrock users and an existing
  endpoint first — don't assume this is greenfield.
- Reference existing VPC, subnets, KMS keys and roles with data sources or
  variables; create only new Bedrock-specific resources.

Not yet built: `network.tf`/`kms.tf` in `bedrock-infra/` don't exist yet.
Inputs needed: VPC ID and private subnet IDs the app uses, the app's
security group, whether a Bedrock VPC endpoint already exists in that VPC,
and whether to reuse a KMS key for logs or create one.

### `model-access` — done

Model access, inference profiles, quotas and throughput — plus the smoke
test that proved connectivity end to end.

**Smoke test (done):** enable a Bedrock model in the NHANES dev AWS account
so later categories have something real to call against, instead of being
blocked on access provisioning.

- Acceptance, satisfied: model access requested and enabled for at least
  one model in Bedrock in the NHANES dev AWS account, in the same Region as
  the rest of NHANES dev infrastructure; a minimal smoke test (a single
  Converse API call with a trivial prompt) succeeded from within the
  NHANES dev account/network (not a developer's personal account),
  confirming both model access and runtime credentials end-to-end.
- Result: smoke-tested successfully against `amazon.nova-lite-v1:0` via the
  Converse API in the NHANES dev account (`us-east-1`). See Risks/Resolved
  findings below for how the IAM path actually worked.

**Broader category work:**

In commercial Regions, foundation model access is now enabled by default
when the account has the required AWS Marketplace permissions, but some
providers still require a one-time use-case form, so test an actual
invocation rather than trusting the console status. **Verify** this for
your chosen model.

1. **Enable and test both models — treat access as unconfirmed until
   tested, for every candidate, not just Anthropic's.** Grant the
   Marketplace permissions each model needs, submit any provider use-case
   form, then run a test call from the intended role and Region. If
   Anthropic access turns out to be gated in this account (plausible — see
   Decisions already made), fall back to whichever models are actually
   enabled and re-run the same smoke test against those instead of
   stalling on an access request.
2. **Stay in one Region.** Call the model directly by model ID where it
   allows. Some models are offered only through an inference profile, and
   profiles can route to other Regions, so if yours requires one, confirm
   its destination Regions with your security office first. **Verify.**
   Get security sign-off before using `additional_model_regions` in the
   Terraform module.

   **Confirmed from a real `aws bedrock list-foundation-models --region
   us-east-1` run:** every Anthropic model currently offered there (Opus 5,
   Sonnet 5, Fable 5, Opus 4.x, Sonnet 4.x — 15 entries) lists
   `inferenceTypesSupported: ["INFERENCE_PROFILE"]` only, with no
   `ON_DEMAND` option for any of them. So a Claude pick isn't just gated on
   the Marketplace/use-case-form access question above — it would also
   require setting up an inference profile and getting the cross-Region
   destination signed off before this smoke test could even run.

   By contrast, a large set of non-Anthropic models in the same listing
   support `ON_DEMAND` directly — no profile, no cross-Region question.
   Amazon's own Nova line is the cleanest fit here: `amazon.nova-micro-v1:0`,
   `amazon.nova-lite-v1:0`, and `amazon.nova-pro-v1:0` are all `ON_DEMAND`,
   natively documented for Converse API tool use, and sidestep both the
   Anthropic-access uncertainty and the inference-profile setup.
   **Recommended default bake-off pair: Nova Lite (lower tier) + Nova Pro
   (middle tier).** Other `ON_DEMAND` alternatives worth a look if Nova's
   tool-call quality disappoints: `mistral.mistral-large-3-675b-instruct`,
   `meta.llama3-70b-instruct-v1:0`, `deepseek.v3.2`. Still undecided (see
   Open questions) — this is a recommendation, not a locked choice.
3. **Check quotas.** At about 10 users a day, default on-demand quotas are
   probably ample, but confirm the tokens-per-minute and requests-per-minute
   values for both models in Service Quotas, since defaults can be low for
   newer models.
4. **Run a short bake-off.** Build 20 to 30 representative tasks with known
   good answers (a user request, the expected concept paths, a valid query
   object). Run both models with identical prompts and tools, then compare
   tool-call correctness, query validity, latency and cost per task. Who
   builds this set and which two models go in is still open.
5. **Escalate only if needed.** Move to a higher tier only if both models
   stay weak after you tune tool descriptions, result ranking and prompts.
6. **Use Standard tier.** At this volume, Standard on-demand is enough;
   Provisioned Throughput is not worth the commitment.

| Option | Best for | Notes |
| --- | --- | --- |
| Standard on-demand | Most synchronous traffic | No commitment; subject to shared quotas that can throttle at busy times |
| Priority | Latency-critical, user-facing calls | Priced about 75% above Standard (third-party figure; verify) |
| Flex | Work that tolerates slow, variable responses | Priced about 50% below Standard (third-party figure; verify) |
| Provisioned Throughput | Custom or fine-tuned models, or committed dedicated capacity | Billed hourly in model units whether used or not; not supported through inference profiles |
| Batch | Large offline jobs | Check that your model supports it |

This category is mostly a runbook, not Terraform: enabling models
(Marketplace permissions, provider use-case forms) is manual, tested with a
real invocation. Optional in Terraform later: application inference
profiles for cost attribution, and a quota request if needed.

### `service-scaffold` — in progress

`pic-sure-ai-service` scaffold & transport. As a frontend developer, when I
need to send a chat message with the current query and facet state, I want
a new backend chat endpoint so that I can get a response without managing
any AI-specific auth or infrastructure.

Context: a new backend service is needed for AI chat. It should follow the
same build, config, Docker, and Spring Boot conventions as
`pic-sure-operations-service`. The endpoint should be exposed through
`pic-sure-gateway`. Hold the model/inference-profile ID, guardrail ID and
version, and Region in configuration per environment (not constants); if
many teams end up calling the model, consider a shared internal gateway for
auth, per-team quotas and usage reporting.

Acceptance criteria:
- A new `services/pic-sure-ai-service` module exists.
- Its build, config, Docker wiring, and deploy pattern match
  `pic-sure-operations-service`.
- `POST /picsure/ai/chat` is reachable through `pic-sure-gateway` and is not
  called directly.
- The endpoint accepts JSON body:
  `{ message, conversationId, requestId, query?, facets?, search? }`
- A request without a valid `Authorization: Bearer <jwt>` is rejected with
  401 before any Bedrock/MCP call.
- The exact same Bearer JWT from the inbound request is attached,
  unmodified, to every outbound call the service makes to the gateway's MCP
  endpoint.
- An integration test confirms two different users' JWTs produce two
  different consent-scoped MCP results, with no shared or synthetic
  credential in between.
- The service never mints, caches, or exchanges a session token of its own.
- A test or code review confirms there is no token-issuing call in the
  request path.
- Any `consents` field in the inbound request body is removed before any
  downstream call.
- A request missing required fields `message`, `conversationId`, or
  `requestId` returns 400 with a field-level error, not 500.

**Progress (6 Oct 2026):** the module, `ChatController`/`ChatRequest`/`ChatResponse`,
caller-identity plumbing (`CallerContext`, forwarded unmodified to MCP calls),
and auth-validation/caller-identity tests have landed (commit `60dec3a66`).
Not yet verified against this list: the exact `POST /picsure/ai/chat` path
through the gateway (current code path is `/ai/...` under the service's own
`context-path`, not yet confirmed to match this AC's exact route), the 400
field-level-error behavior, and `consents`-stripping.

### `tool-use-loop` — in progress

Bedrock tool-use loop & MCP client. As the ai-service, I want to manage the
back-and-forth with the AI model so that the model can look up real
PIC-SURE data before answering, without ever talking to our data systems
directly or inferring user credentials.

Context: this is about the ai-service managing a conversation loop with an
AI model, with clear error handling, loop limits, and a clean separation of
AI provider-specific code. Use the AWS SDK for your languages (Java SDK v2
and boto3 both cover Bedrock Runtime) and the Converse API, which gives one
request shape across models and eases swapping models later. Retry
throttling errors with exponential backoff and jitter, set timeouts,
support streaming for interactive use, and decide in advance what the app
does when the model is unavailable. Cap output tokens, trim prompts, and
use prompt caching for large, repeated system prompts to cut input cost.

Acceptance criteria:
- Given a chat request, the service sends the user's message to the AI
  model with a list of allowed data-lookup actions.
- The service checks each model response to determine whether more
  information is needed or the model is ready to answer.
- When the model asks for more information:
  - Requests to look something up, such as searching the dictionary or
    checking facet values, are sent to PIC-SURE data systems.
  - Requests to propose a query, facet selection, or search are handled
    internally by this service.
  - Internal handling for query, facet selection, or search is only
    stubbed in this category (real validation is `validation-repair`).
- Any lookup sent to PIC-SURE data systems reuses the same user credential
  from the original chat request.
- The service never creates or substitutes its own credential.
- Once the model is done and ready to answer, the service stops the
  back-and-forth and returns the reply to the user.
- The response includes the conversational message and an optional,
  updated query, facet selection, or search (whatever actually changed
  during that turn).
- There is a configurable limit on how many back-and-forth exchanges can
  happen in a single turn.
- If that limit is reached, the service stops and returns the best answer
  it has so far to the user, instead of continuing indefinitely.
- If the AI model fails to respond, such as timing out or being
  overloaded, the user sees a clear error message, distinct from the error
  message for a data lookup failure.
- If a data lookup fails, that failure is returned to the model as part of
  the normal conversation, and does not crash the whole turn.
- All code that talks to the AI model, including request building,
  credential attachment, and endpoint calling, lives in one isolated
  module.
- The rest of the service does not reference anything specific to the AI
  model provider, allowing local pic-sure deployments to use their own
  models against their pic-sure instances.

**Tool use with the MCP endpoint**, specifically: Bedrock models don't
reach the MCP endpoint or the internet themselves. `pic-sure-ai-service`
sends the conversation and tool definitions to the model, receives its tool
requests, runs them against the gateway's MCP endpoint, and returns the
results. The control point is that service's code — this already matches
`PLAN.md`'s "Request lifecycle"/"Tool dispatch" sections, so treat the
items below as a checklist against what's already designed there, not a
separate design.

- Give the MCP endpoint read-only, least-privilege access. Exactly what it
  returns (terms, concept paths, and whether counts are included at all) is
  still TBD — a coworker's call on the tool surface, not settled here.
- *If* counts end up exposed: suppress or bucket small patient counts in
  the MCP endpoint, because the model and the logs see every value it
  returns. Confirm this is actually in scope before building it.
- Treat tool results as untrusted text: keep prompt-attack filtering on,
  and never let model output trigger an action directly. (See
  `prompt-injection-hardening`.)
- Validate the final query/facets/search object against a schema in your
  API before it's returned, and have the user review it before using it in
  the other system. (See `validation-repair`.)
- Cap tool-call loops (maximum steps and tokens per request) to bound cost.
  Size the actual numbers here — named as MVP, not yet sized.

**Progress (6 Oct 2026):** `ToolUseLoopService`, both `ConverseModelClient`
implementations (`ConverseSdkModelClient`, `ConverseHttpModelClient`), and the
`McpToolGateway` seam have landed. The coworker owning `pic-sure-mcp`
(`ALS-13096`) confirmed its protocol surface ("seems to work well enough for a
first pass") is stable enough to build against even though internals may
still change, so the real client, `GatewayMcpToolGateway`, is implemented
against that contract: a plain JSON-RPC-over-HTTP client (no MCP SDK
dependency, matching `ConverseHttpModelClient`'s hand-rolled-HTTP precedent)
against `pic-sure-mcp`'s stateless streamable-HTTP transport, replaying the
caller's `Authorization` and `X-Request-Id` on every `tools/call`. It is now
the sole, default implementation (`MockMcpToolGateway` has been deleted —
`picsure.ai.mcp.mode` defaults to `gateway`).

**The query/facets/search proposal tool is resolved differently than first
assumed:** it isn't a missing capability on `pic-sure-mcp` (the model can
already build a complete real query through `count_participants`/
`cross_count`'s existing `query` argument), and it was never going to be a
tool *on* `pic-sure-mcp` — per this category's own AC above, it's "handled
internally by this service." So `propose_query` is now a real **local** tool
in `ai-service` (`ProposeQueryTool`), never dispatched to `McpToolGateway` at
all — no coordination with `pic-sure-mcp`'s owner needed. It does real shape
validation against the actual `Query` model (`pic-sure-hpds-model`,
mirroring `pic-sure-mcp`'s own `QueryInput`/`QueryBinder` binder) — this pulls
forward `validation-repair`'s shape-validation AC, since on a single-developer
project the category boundary didn't need to block it. What's still correctly
deferred to `validation-repair`: real-execution validation (confirming a
shape-valid query actually runs against HPDS without error — a shape check
alone can't catch a hallucinated concept path), which is why a successful
proposal still never promotes into `ChatResponse.query`/`facets`/`search`.

Still open: cap sizing (max iterations/tokens/timeout) is still unset; and
none of this has been run against a live `pic-sure-mcp` + gateway yet, only
against a WireMock stand-in matching `pic-sure-mcp`'s own `McpProtocolIT`
fixtures.

### `validation-repair` — in progress

Query/facets/search validation & repair-loop. As a researcher using
assisted search, when the assistant proposes a query or facet selection, I
want it to be real and executable so that I never get a broken query
silently handed to me.

Context: the assistant must validate proposed queries and facet/search
selections before they are accepted. Validation happens in the service,
using the `operations-service` Query schema. If validation fails, the model
should retry within the same tool-use loop. The UI should never receive a
broken, flagged-invalid, or partially validated object.

Acceptance criteria:
- [x] When the model calls the query-proposal tool, the service first checks
  the proposed object shape against `operations-service`'s Query schema. —
  **Done (6 Oct 2026):** `ProposeQueryTool`/`ProposeQueryInput`/
  `ProposeQueryBinder` in `ai-service`, pulled forward from this category
  (see `tool-use-loop`'s progress note) since shape validation turned out to
  be cheap and the category split wasn't load-bearing on a single-developer
  project. Binds against the real `pic-sure-hpds-model` `Query` shape,
  mirroring `pic-sure-mcp`'s own `QueryInput`/`QueryBinder`.
- [x] Shape validation is cheap and happens before any backend call. — Done;
  no backend call happens at all yet (see below).
- [ ] Anything malformed is rejected before it reaches HPDS. — Vacuously true
  for now: nothing promoted from `propose_query` reaches HPDS yet, because
  nothing promoted from it goes anywhere (see next item).
- [ ] Only a shape-valid object proceeds to the next check. — **Design
  decided (6 Oct 2026), not yet implemented:** no HPDS round-trip needed in
  either the open or authorized channel. Diff the proposed `query` against
  the *current* `query` resent on the request (see `conversation-history`'s
  resend decision) to find only the newly-added concept paths, then
  re-verify each one with `get_concept` through the existing
  `McpToolGateway`, using the caller's real JWT. A path that comes back
  proves two things at once, regardless of channel: it's real (not
  hallucinated) and this caller can see it (the dictionary is already
  authorization-filtered). A path that doesn't come back is a repairable
  `toolResult` failure, same as any other tool error. This is a weaker
  guarantee than literal HPDS execution, but a deliberate, accepted one — on
  par with what a human gets today building the same query by hand in the
  Explorer UI, which doesn't dry-run against HPDS either.
- [x] When the model calls the facets/search-proposal tool, the payload uses
  the slimmed `{ category, name }[]` shape. — Done, reuses `ai-service`'s
  existing `FacetSelection` record.
- [ ] Each facet entry is checked against real current facet values before
  acceptance. — **Design decided, not yet implemented:** same diff-then-
  reverify mechanism as `query`, against `list_facets` instead of
  `get_concept`. `ProposeQueryTool` today only checks shape (non-blank
  `category`/`name`).
- [x] When any check fails, the service returns a `toolResult` with an error
  status to the model, not to the UI. — Done.
- [x] Repair retries are bounded by the same max-iteration cap (see
  `tool-use-loop`) — there is no separate, independently unbounded repair
  loop. — Done; `propose_query` shares `ToolUseLoopService`'s normal
  iteration cap like every other tool call.
- [x] If the iteration cap is reached while query/facets/search is still
  unvalidated, the final response omits that field entirely and returns
  prose only. — True today, though trivially: the field is *always* omitted
  right now, cap or no cap, pending real-execution validation.
- [x] The UI never receives a flagged-invalid or partially validated object.
  — True today: it never receives one at all yet.
- [ ] Any `consents` value arriving alongside a facets/search request has no
  bearing on this validation path. — Not yet applicable; no consents-bearing
  path exists here yet.
- [ ] Access control during validation is derived solely from the JWT used
  for the HPDS/facet call. — **Satisfied by design, not yet implemented:**
  the `get_concept`/`list_facets` re-verification calls above go through
  `McpToolGateway` the normal way, so they carry the caller's real JWT like
  every other tool call — no separate access-control mechanism needed.

**Implemented (12 Oct 2026):** the "diff-and-reverify" checks above shipped as
provenance checks instead of fresh MCP calls — a proposed concept path or
facet must have appeared in a `search_concepts`/`get_concept`/`list_facets`
result earlier in the same turn, or already be in the request's current state
(`ConceptPaths`, `KnownFacets`). Same guarantee, no extra round-trips. A
proposal that passes is held (later proposals override earlier ones per part)
and promoted into `ChatResponse` only for the parts that differ from the
request's `query`/`facets`/`search`; the returned `query` is rebuilt from the
validated fields, with paths exactly as a tool returned them, never the
model's raw JSON. Request-side `query`/`facets`/`search` get the same
structural checks (`ChatStateValidator`; 400 `invalid_request`), tolerating
the UI's extra query fields. The ACs above that this settles can be ticked;
the `consents`/JWT ACs remain satisfied by design as noted.

Follow-up (12 Oct 2026): `search` is a plain string (the UI sends it to
`/dictionary` with `facets`, and doesn't validate either), so `search` and
facet text get a harmful-content check only — no control or invisible-formatting
characters, generous length cap (1000) — assuming ordinary search terms. The
request's current `query`/`facets`/`search` are now appended to the user turn
(`CurrentState`), the query in its validated, model-settable form only, so the
model can build on what's on screen. Still open: handling `not` clauses in the
current query — see `PLAN.md`'s Open items.

**Model rounds v17–v18 (15 Oct 2026):** reply text now gets checks too, not just tool
calls. Backticked paths no tool returned get one correction round-trip, and the correction
names the actual mistake (usually a categorical value written as a path). A proposed
`select` now adds to the researcher's columns instead of replacing them, unless the message
asks to remove or replace one. A query the model counted but never proposed is proposed when
the reply says the query changed. A single search that found the variables the reply names
is proposed when the model proposes nothing. The full list is in the guard table in
`services/pic-sure-ai-service/README.md`. Dataset-mixing queries (Synthea filters + NHANES
columns) are deliberately not flagged, because some environments harmonize datasets.

**Deferred, not v1 (6 Oct 2026):** PIC-SURE's open/Discover channel and
authorized/Explorer channel carry different constraints beyond the above —
Discover must refuse any `genomicFilters` and any filter on a concept whose
`allowFiltering` is false (a "stigmatizing" concept), neither of which
applies to Explorer. Building this now would be premature: `PLAN.md`'s "UI
planning" section only wires assisted search into the Explorer page — there
is no Discover-page chat integration planned yet, so there is currently no
way to even reach the open channel through this feature. When that phase
gets planned, it will need: a mode signal on `ChatRequest` (Explorer vs.
Discover isn't derivable from anything already on the request); a
`pic-sure-mcp` change to stop dropping `allowFiltering` (currently absent
from the whole pipeline — `DictionaryConcept` doesn't even deserialize it,
`@JsonIgnoreProperties(ignoreUnknown = true)` discards it at the first hop)
and pass it through instead of filtering it at that layer (`pic-sure-mcp`
has no way to know which UI mode is asking, and the restriction is
Discover-only — filtering it server-side at the MCP layer would wrongly
restrict Explorer too); and enforcement in `ai-service`, where the mode is
actually known. A system-prompt instruction against genomic filters is a
reasonable permanent first layer regardless of mode-field timing, but not a
substitute for the hard check once mode exists.

### `conversation-history` — ACs pending

Conversation history & turn persistence: a server-side conversation table
keyed by `conversationId`/`userId`, `requestId` idempotency, and
`GET /picsure/ai/chat/{requestId}` as the retry path. Design discussion
lives in `PLAN.md`'s "Conversation history / context management" and
"Timeouts & partial results" sections — this category is that design's
implementation, not yet broken into ACs.

**Follow-up: provenance across turns (raised 12 Oct 2026).** `ConceptPaths`
and `KnownFacets` (see `validation-repair`) are built fresh each turn, which
is only correct while a turn's context is just that turn. Once prior turns are
replayed to the model, it can cite a path or facet it saw in an earlier turn,
and the per-turn guard would refuse it as never seen — safe, but it forces a
pointless re-search and breaks multi-turn use cases 4 and 7. Resolve when this
lands:
- Rebuild the seen sets on load by replaying the stored, successful tool
  results of the conversation through the same `learnFrom`/`learnFromListFacets`
  (derive, don't persist a second copy), before adding the request's current
  state.
- The source must be the server's own stored tool results, keyed by
  `conversationId` **and** `userId` — never anything the client resends, and
  never another user's conversation.
- Any truncation or summarization of old turns (see `PLAN.md`'s context
  management) must either keep the tool results or the derived sets, else
  provenance silently shrinks.
- Staleness: a replayed result reflects what the caller could see then; if
  consents change mid-conversation a stale path/facet could pass the guard.
  Accept for short sessions or bound conversation age; decide explicitly.
- `list_facets` output is capped per category (25), so a facet the model only
  saw via an earlier narrower search is still known; test that case.

**Decided (6 Oct 2026): this table is message/tool-call history only.**
`query`/`facets`/`search` are *not* part of it — they stay client-resent on
every request, as originally designed (`PLAN.md`'s Transport section), not
server-persisted alongside the conversation. More performant, and the UI
already owns that state as the source of truth. The trade-off: a raw
inspection of the stored rows alone won't show what query state a given
message was sent with. Storing that level of detail would mainly serve an
audit requirement or an "undo" feature, neither of which exists yet, and
"undo" may not even need it specifically — the model could plausibly handle
undo by re-proposing the prior state minus the last change, from
conversation context alone, without any new storage. Revisit only if a
concrete audit or undo need actually materializes.

### `guardrails-audit` — not built, ACs pending

Guardrails, logging, monitoring and audit — the AWS-side Terraform plus the
app-side wiring and per-turn audit entry, named together because they're
two halves of the same control.

**AWS side**, not yet built in Terraform — wire its outputs
(`guardrail_arns`, `enforce_guardrail_identifier`, log bucket/group/key)
into the `bedrock-infra` module once it exists:

- **Guardrails.** Define denied topics, sensitive-information filters
  (including PII/PHI redaction), and prompt-attack filtering at high
  strength. Use the `ApplyGuardrail` API if you need to check content
  independently of a model call. Add contextual-grounding checks if the
  model answers from retrieved documents. This is also the AWS-side half of
  `PLAN.md`'s own "Tool-call guardrails" MVP commitment — don't duplicate,
  just wire the two together.
- **Invocation logging.** Enable it in every Region where Bedrock is used,
  to S3 and/or CloudWatch Logs. Enforce this with an organization policy
  and an alert if it is ever disabled. It's one configuration per account
  per Region — check the existing configuration before creating one;
  import it or leave it out rather than overwrite.
- **Treat the logs as sensitive, and decide this one on purpose.** They
  hold the pre-guardrail user input, so encrypt the destination with a
  customer-managed KMS key, make the S3 bucket private with versioning and
  Object Lock (or MFA Delete), keep the destination in the same account,
  and apply a data-protection policy to CloudWatch log groups. Decide the
  retention period against your institutional records policy.

  `PLAN.md`'s own audit design already covers this interaction
  deliberately *without* storing prompt/response text in the audit trail —
  metadata only (requestId, tool-call count, token usage, outcome) to
  `pic-sure-logging`, with actual conversation content kept in the
  `conversation-history` table instead, for different retention/access
  reasons. Turning on Bedrock's native invocation logging adds a *third*
  place the same consent-scoped tool-result content lands, under AWS-side
  access controls — a reasonable addition for AWS-side incident response,
  but set its retention/access independently rather than assuming the
  app's existing content-retention story already covers it.
- **Metrics and alarms.** Watch invocation count, latency, input and
  output tokens, throttling errors, and guardrail interventions in
  CloudWatch. Alarm on throttling and on sudden token spikes.
- **Audit trail.** Turn on CloudTrail for Bedrock API activity and alert on
  access-denied calls.

**App side**, ACs pending: max tool-call iterations, max output tokens, and
wall-clock budget per turn (MVP guardrails, sizing TBD — see
`tool-use-loop`); Bedrock Guardrails config attached to the Converse call;
a per-turn audit entry to `pic-sure-logging` with shape — metadata, not
content:
- `requestId`/`conversationId`, `userId`, timestamp
- model id/version
- tool-call count for this turn (doubles as guardrail telemetry)
- outcome: success / mcp-error / timeout / partial
- token usage (input/output — free from the Converse response)
- booleans for whether `query`/`facets`/`search` were returned

Reuse note: there's a shared `AuditContext` class (in
`pic-sure-spring-commons`) implementing a request-scoped
"accumulate key/value facts, flush at the end" pattern, worth reusing for
shape-consistency — but it's currently only actually wired up in the
gateway itself (not operations-service or hpds-query-service), so confirm
it's meant for reuse before building on it.

### `frontend-ui` — ACs pending

Frontend: inline assisted-search chat UI. Toggle button replacing
`Searchbox`, `ChatWindow`/`ChatMessage`/`ChatInput`, `stores/Chat.ts`,
full-screen `Modal` expand, markdown sanitization, `enableAssistedSearch`
feature flag. Full UI design lives in `PLAN.md`'s "UI planning" section —
this category is that design's implementation, not yet broken into ACs.

**Health route and staged rollout**, part of this category's scope:

- Bedrock has no single billing-health call. Use an AWS Budgets alert or
  action to set a flag when spend passes a threshold, and have your health
  route read that flag alongside server health. **Verify** the setup.
- Don't call the model from the health check, since that spends tokens.
  Report your own recent error and throttle rates, or the Bedrock
  CloudWatch metrics, instead.
- Show the feature in the UI only when the health route is healthy and the
  user is in the selected group, using the feature flag. Fail closed: hide
  it if the budget flag trips or throttling rises.

### `prompt-injection-hardening` — not started

Prompt-injection / tool-surface hardening. The Bedrock agent has no general
internet access — only the one MCP tool is reachable. That already removes
the scariest version of this risk (exfiltration to an attacker-controlled
endpoint, arbitrary destructive actions). What's left, given that
constraint, is narrower: injected text in a tool result trying to
manipulate the one thing the model can still do — misuse the
query-building tool it legitimately has (e.g. a malicious concept
description steering it toward a manipulated query object, or going
off-topic).

- System-prompt hardening: explicit instructions that tool-result content
  is data, never instructions — ignore directive-like text found inside a
  search result or concept description.
- Bedrock Guardrails (see `guardrails-audit`) — the other half of why it's
  worth enabling at MVP.
- Confirm the implementation actually uses the Converse API's structured
  `toolResult` content blocks, not tool output string-concatenated into a
  user-turn message — Bedrock/Claude already treats structured tool
  results as a distinct content type rather than freeform instructions, so
  this is a real mitigation that comes for free from not building it the
  sloppy way.
- The existing validate-before-return design (see `validation-repair`) is
  itself a mitigation worth naming: a successful injection would have to
  produce a schema-conforming, *execution-valid* query to do real damage,
  not just get the model to say something wrong.
- Keep secrets and credentials out of prompts, strip or tokenize
  identifiers before the call when PHI isn't needed, validate model output
  before acting on it, and keep a human in the loop for consequential
  actions.
- Forward-looking, not an MVP action: low risk today because the NHANES
  dictionary is static and curated — nobody can inject text into it. If the
  MCP tool surface ever grows to include user-editable content
  (operations-service's Banner/announcements feature is an example of
  admin-editable text elsewhere in this system), that's a meaningfully
  different risk profile, worth re-checking before exposing anything like
  that as a tool.

## Cost management

On-demand Bedrock is billed per token, so cost scales with usage and is
easy to overspend without limits. Set the controls up before users arrive.

- **Budgets and alerts.** Create an AWS Budget on the account with alerts
  at several thresholds, plus Cost Anomaly Detection.
- **Attribution.** Use application inference profiles and consistent
  resource tags so spend can be split by team, project and environment.
- **Estimate first.** Multiply expected requests by average input and
  output tokens at the chosen model's published per-token price, then add a
  margin for retries and growth.
- **Reduce per-call cost.** Pick the smallest model that meets quality
  needs, cap output length, use prompt caching, and move offline work to
  Flex or batch where supported.
- **Commitments last.** Only consider Provisioned Throughput or a reserved
  tier after usage is steady and the on-demand quotas are proven
  insufficient.

## Rollout sequence and go-live checklist

Build in this order, and don't move to the next stage until the gate for
the current one is met.

| Stage | Category/ies | Gate to proceed |
| --- | --- | --- |
| 0. Decide | Settle the decisions above; start security review | Use case, data sensitivity, model and Region agreed in writing |
| 1. Foundation | `account-governance` | Compliance owner signs off; no PHI sent before this |
| 2. Access and network | `iam-access` (done), `networking-encryption` (not built) | A test call succeeds only through the private endpoint and approved role |
| 3. Model and capacity | `model-access` | Load test at expected peak without throttling |
| 4. Safety and observability | `guardrails-audit` (not built) | Logs land encrypted; a test of a blocked prompt and a disabled-logging alert both work |
| 5. Pilot | `service-scaffold`, `tool-use-loop`, `validation-repair`, `conversation-history`, `frontend-ui` on a small user group | Quality, latency and cost per request reviewed against targets |
| 6. General rollout | Wider access, runbook, support path | Cost and throttling alarms quiet for an agreed period |

Next steps, concretely, in order:

1. `networking-encryption`: add `network.tf` and `kms.tf` to `bedrock-infra/`.
2. `model-access`: run the actual bake-off (connectivity is proven; quality
   isn't).
3. `guardrails-audit`: AWS-side Terraform, then wire its outputs into the
   IAM module.
4. `service-scaffold` → `tool-use-loop` → `validation-repair` →
   `conversation-history` → `frontend-ui`, in that build order.
5. Health-route / feature-flag work (part of `frontend-ui`).

Go-live checklist:

- [ ] Data classification and BAA status confirmed for the account
- [ ] Chosen model and features confirmed eligible for the data in use
- [ ] Least-privilege roles in place; no long-term keys in shared use
- [ ] Interface VPC endpoints and endpoint policies live
- [ ] Cross-Region policy matches the residency decision
- [ ] Quotas verified against peak load; increases requested
- [ ] Guardrails attached and enforced through IAM
- [ ] Invocation logging on in every used Region, encrypted, access
      restricted
- [ ] Budget, anomaly alert and throttling alarms active
- [ ] Runbook written: who to call, how to disable access, how to rotate
      access

## Risks

The biggest risk is sending sensitive data before the agreement, network
and logging controls are in place; the stage gates above exist to prevent
that.

| Risk | Mitigation |
| --- | --- |
| PHI reaches a model that isn't eligible, or an account without a BAA | Confirm eligibility per model and Region; block other models by IAM and SCP |
| Small patient/variable counts from `count_participants`/`cross_count` are identifying | Counts are confirmed exposed (see Still open, below) — size and build the suppression/bucketing logic in `guardrails-audit` rather than treating it as speculative |
| Cross-Region routing moves data outside the intended boundary | Use a geography-tied profile or single Region; restrict Regions by SCP |
| Invocation logs expose sensitive prompts | Encrypt, restrict readers, apply data-protection policy, set retention |
| Throttling at peak or quota shortfall | Request increases early; use cross-Region profiles; retry with backoff |
| Runaway spend | Budgets, anomaly alerts, token caps, per-team attribution |
| Model or feature retired or changed | Keep IDs in config; re-test on model updates |

**Resolved by the `model-access` smoke test:**

- **Model pick, confirmed working (connectivity-wise):**
  `amazon.nova-lite-v1:0` and `amazon.nova-pro-v1:0`, both `ON_DEMAND`, both
  successfully invoked via the Converse API under the IAM path below. Not
  the same thing as the bake-off itself — tool-call quality/correctness on
  the actual dictionary-search and query-building tasks hasn't been tested,
  just that the account can reach and invoke these models. Claude was not
  pursued: every Anthropic model in this account's catalog requires an
  inference profile (see `model-access`), and this result didn't need one.
- **Credential path, confirmed working, and it's not one mechanism:** the
  original design staged API-key-first; in practice the IAM route worked
  directly, no API key involved. But a single "attach the policy to an
  existing role" pattern didn't cover both identities actually used for
  testing:
  - **`AvillachLabIamInstanceRole`** (the lab's EC2 instance role, behind
    `AvillachLabIamInstanceProfile`) — a normal IAM role, not SSO-managed,
    so `attach_invocation_policy_to_role_names` (direct policy attachment)
    works on it fine.
  - **The CLI/SSO identity** (`AWSReservedSSO_Manager_a497562d27bfafae`) —
    an IAM Identity Center–managed role. Direct `AttachRolePolicy` against
    it is rejected outright; IAM Identity Center owns these roles
    exclusively through its own control plane. The working pattern
    instead: a dedicated Terraform-created role
    (`invocation_role_enabled = true`) whose trust policy names the SSO
    role's ARN as principal, assumed via `sts assume-role` at test time.
  - **Generalizes beyond this POC:** whatever role `pic-sure-ai-service`
    ends up running under (EC2 instance role, ECS task role, etc.) is very
    unlikely to be SSO-managed, so direct attachment should work for it the
    same way it did for the lab's EC2 role here — but the *check* ("is this
    an `AWSReservedSSO_*` / Identity Center–managed role, or a normal
    one?") is the reusable lesson, not the specific role names.
  - Also resolved in passing: a role's **instance-profile ARN**
    (`arn:...:instance-profile/...`) is never a valid trust-policy
    principal — only the IAM role ARN behind it is. Tripped this once
    before finding the real role name.
- Terraform has in fact been run (and applied) in this account. Terraform
  version used: whatever the operator's local install was — not recorded,
  revisit if `use_lockfile` (needs 1.10+) ever matters.

**Still open:**

- Which Region must the FISMA environment use, and are both candidate
  models available there?
- Who approves changes to the existing NHANES dev and FISMA accounts (no
  new account is being created), and does an existing organization
  baseline apply?
- **Resolved, partially:** yes — `pic-sure-mcp`'s real tool set (confirmed by
  reading its code and `McpProtocolIT`) includes `count_participants` and
  `cross_count`, both obfuscated/open-access counts, alongside
  `search_concepts`, `list_facets`, `get_concept`, and `get_adapter_code`.
  What's still open is only the "what threshold counts as small" /
  suppression question (see `guardrails-audit`), not whether counts are
  exposed at all.
- The actual bake-off (20–30 example tasks, tool-call correctness and
  query validity, not just connectivity) hasn't run yet, and who builds
  that task set is still open.
- Which role does `pic-sure-ai-service` actually run under once deployed
  (confirm against `pic-sure-operations-service`'s existing role, per its
  shared conventions) — and whether that role is SSO/Identity-Center-managed
  (unlikely, but check per the lesson above, not by assumption) before
  wiring the IAM module to it.
- Third-party figures in this doc (Priority/Flex pricing offsets, a claim
  that AWS's HIPAA reference excludes Fable and Mythos models) came from
  search excerpts, not AWS pages — confirm against AWS before relying on
  them.
- Whether the FISMA environment really uses Terraform is unconfirmed
  (believed, not confirmed).
- ~~Exact MCP route path and tool surface (coworker's call).~~ **Resolved:**
  the gateway's `/mcp` route, fronting `pic-sure-mcp`'s six tools
  (`search_concepts`, `list_facets`, `get_concept`, `count_participants`,
  `cross_count`, `get_adapter_code`) over stateless streamable-HTTP JSON-RPC.
  See `tool-use-loop`'s progress note.
- Exact chat endpoint path/request-response schema.
- Whether the AI service runs the Bedrock tool-use loop itself or delegates
  to something like Bedrock AgentCore Gateway — current lean is app-side
  for the MVP (see `PLAN.md`'s "Model-provider portability" section for
  why).
- **Resolved (6 Oct 2026), split in two:** message/turn transcript — still
  leaning server-side table (not yet built, see `conversation-history`).
  `query`/`facets`/`search` specifically — **decided**: client-resent each
  request, as originally designed (`PLAN.md`'s Transport section), not
  server-persisted. More performant, and the UI already owns that state;
  storing it would mainly serve an audit or "undo" use case, neither of
  which exists yet — revisit only if one does.
- ~~Confirm HPDS actually errors on unresolvable/hallucinated concept paths
  at sync-execution time (the assumed backstop for `validation-repair`).~~
  **Moot:** validation no longer depends on HPDS behavior at all — see
  `validation-repair`'s updated design note below.
- **Resolved, design only (6 Oct 2026):** validation for a returned
  `facets`/`search` object uses the same mechanism as `query` now does —
  see `validation-repair`.
- Revisit search quality (`pg_trgm` / synonym dictionary / embeddings) once
  there's real usage to show where LLM-side alone falls short (see
  `PLAN.md`'s "Search quality" section).
- Size the actual numbers for tool-call guardrails: max iterations, max
  output tokens, per-request timeout budget (see `tool-use-loop`).
- Confirm `AuditContext`/`pic-sure-spring-commons` is meant to be reused by
  non-gateway services, or find out what operations-service/
  hpds-query-service actually do instead (see `guardrails-audit`).
- Bedrock Guardrails policy specifics (which content filters/denied
  topics) — not configured yet.
- Exact full-screen sizing/backdrop-click-to-close behavior for the
  expanded chat `Modal` (see `PLAN.md`'s "UI planning" section).
- Whether `enableAssistedSearch` ships flagged-off by default or on for
  the POC.
- Whether user (not just assistant) chat messages also get a copy button.

**What Knowledge Bases are.** Knowledge Bases is a Bedrock feature for
retrieval-augmented generation (RAG) over your own documents. You point it
at files, Bedrock splits and indexes them in a vector store, and at
question time it retrieves the most relevant passages and gives them to the
model. This design already retrieves live data through MCP tool calls, so
it may not need Knowledge Bases. Add it only if there is written material
the MCP server can't serve, such as mapping rules, usage guidance or
example queries. Tuning retrieval then means better tool descriptions,
search parameters and result ranking. If added, the vector store and
source files fall under the same FISMA controls, so check their
eligibility.

## Sources

Based on search-result excerpts as of 5 October 2026, not full-page reads.
Third-party figures (tier pricing, the Fable/Mythos HIPAA exclusion) are
marked verify and should be checked against AWS before you rely on them.

- [Amazon Bedrock security and privacy (AWS)](https://aws.amazon.com/bedrock/security-compliance)
- [Amazon Bedrock FAQs (AWS)](https://aws.amazon.com/bedrock/faqs/)
- [Cross-Region inference (AWS docs)](https://docs.aws.amazon.com/bedrock/latest/userguide/cross-region-inference.html)
- [Quotas for the bedrock-runtime endpoint (AWS docs)](https://docs.aws.amazon.com/bedrock/latest/userguide/quotas-runtime.html)
- [How inference works in Amazon Bedrock (AWS docs)](https://docs.aws.amazon.com/bedrock/latest/userguide/inference-how.html)
- [Implementing least privilege access for Amazon Bedrock (AWS Security Blog)](https://aws.amazon.com/blogs/security/implementing-least-privilege-access-for-amazon-bedrock/)
- [Is AWS Bedrock HIPAA compliant? (ComplySaaS)](https://www.complysaas.com/compliance/aws-bedrock)
- [AWS Bedrock HIPAA compliant AI agents guide (CloudTech)](https://cloudtech.com/feeds/blog/aws-bedrock-hipaa-compliant-ai-agents)
- [AWS Bedrock pricing 2026 (CloudForecast)](https://www.cloudforecast.io/blog/aws-bedrock-pricing/)
- [Bedrock regions, quotas and cross-Region inference (Pranay Jha)](https://drpranayjha.com/bedrock-regions-quotas-cross-region-inference/)
- [AWS Bedrock best practices (Trend Micro)](https://www.trendmicro.com/trendaivisiononecloudriskmanagement/knowledge-base/aws/Bedrock/)
- [Bedrock invocation logging guide (Binadox)](https://www.binadox.com/blog/binadox-article-enable-model-invocation-logging/)
- [Amazon Bedrock Guardrails IAM enforcement (Sudo Consultants)](https://sudoconsultants.com/amazon-bedrock-guardrails-announces-iam-policy-based-enforcement-to-deliver-safe-ai-interactions/)
- [AWS re:Post: Bedrock model access change](https://repost.aws/questions/QUrd_TAuVLSCGeVtJqRe5MXA/amazon-bedrock-foundational-models-access-issue)

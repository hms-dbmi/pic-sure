# bedrock-infra

Terraform for `iam-access` (identity and access) of the Bedrock rollout
behind `pic-sure-ai-service`. One root module, local state by default, same
code for the POC (existing NHANES dev AWS account) and the FISMA
environment.

For the full rollout plan — decisions, category sequencing, open
questions, why this is the *later* IAM step rather than what `model-access`
needs on day one. This file is just how to run it.

## What it creates

| Resource | Created when | Purpose |
| --- | --- | --- |
| `<prefix>-<env>-invoke` policy | always | Invoke only the approved models (and profiles) in the one Region; optionally apply and require a guardrail |
| Attachment to existing roles | `attach_invocation_policy_to_role_names` set | Gives the app's existing EC2 role Bedrock access without changing that role elsewhere |
| `<prefix>-<env>-invoke` role | `invocation_role_enabled = true` | Optional dedicated role for the app. Also the only option for a caller whose identity is IAM Identity Center–managed (`AWSReservedSSO_*`) — direct policy attachment is rejected outright on those; this role's trust policy names the SSO role as principal instead, assumed via `sts assume-role` at call time. Confirmed in the POC. |
| `<prefix>-<env>-admin` role and policy | `admin_role_trusted_principal_arns` set | Humans who manage guardrails, logging config, inference profiles, quotas. MFA required by default. Cannot invoke models. |
| `<prefix>-<env>-log-reader` role and policy | `log_reader_trusted_principal_arns` set | Read-only access to invocation logs (they contain prompt text) |
| `<prefix>-<env>-boundary` policy | `permissions_boundary_enabled` (default true) | Ceiling on roles created here: Bedrock only, only in the approved Region |

It does not create the guardrail, log bucket, KMS key, VPC endpoints or the model
invocation logging configuration. Those belong to `networking-encryption`
and `guardrails-audit`.

## Use (low-security POC, local state)

```bash
cd bedrock-infra
cp terraform.tfvars.example poc.tfvars   # fill in region, two model IDs, existing app role name
terraform init
terraform plan  -var-file=poc.tfvars -out=poc.tfplan
terraform apply poc.tfplan
```

Get model IDs with `aws bedrock list-foundation-models --region <region>`.

**Pick models that support tool use.** `pic-sure-ai-service` needs the model to return Converse `toolUse` blocks, and
the Amazon Nova models tested (`amazon.nova-lite-v1:0`, `amazon.nova-pro-v1:0`) did not, even though they invoke fine.
Tested and working: `openai.gpt-oss-120b-1:0` and `mistral.voxtral-small-24b-2507` (full loops); `mistral.voxtral-mini-3b-2507` calls tools but is too shallow to finish a multi-step question. Returned a correct first tool call:
`mistral.mistral-large-3-675b-instruct`. These are on-demand in `us-east-1` (no inference profile needed). Details are in
`services/pic-sure-ai-service/README.md#choosing-a-model`. Whatever model the service is configured with via
`BEDROCK_MODEL_ID` must be listed in `allowed_model_ids`; any other model is rejected with a 403.

## Before applying in a production account

- Review the plan: it should only add a policy, attachment(s), and the optional roles/boundary.
- The attachment changes an existing role's permissions. Confirm that role is the one `pic-sure-ai-service`'s containers actually use — don't assume it matches `pic-sure-operations-service`'s role just because the two services share build/Docker conventions; check the actual deployment. Also confirm it's not managed by another Terraform stack that would remove the attachment on its next apply.
- If a model is only reachable through a system-defined inference profile, the profile may route to other Regions. Confirm the destinations with your security office before using `additional_model_regions`.
- `enforce_guardrail_identifier` denies every invocation that lacks the guardrail. Verify the value format, and enable it only after the guardrail exists and the application sends it.

## Moving to the FISMA environment

- Copy `poc.tfvars` to `fisma.tfvars` and change the values only (Region, model IDs, role names, trusted principals).
- Use a remote backend with a separate state key per environment (see the commented block in `versions.tf`; `use_lockfile` needs Terraform 1.10 or later).
- Keep `permissions_boundary_enabled = true`, set `admin_require_mfa = true`, and fill in the log settings once `guardrails-audit` exists.

## Verified

Applied and smoke-tested successfully in the POC (NHANES dev) account: `amazon.nova-lite-v1:0` invoked via the Converse API, through both a direct attachment onto an existing EC2 role and a dedicated assumable role for an IAM Identity Center–managed CLI session. That smoke test shows the invocation path works, not that the model can use tools: later end-to-end testing of `pic-sure-ai-service` against the dedicated role (`bedrock-assistant-poc-invoke`, assumed from a personal SSO session, service running in a local container) found Nova unable to do tool use and `openai.gpt-oss-120b-1:0` working (see the service README). Still unverified: the FISMA-environment path (new Region, new role names, not yet run), `enforce_guardrail_identifier`'s exact value format (no guardrail exists yet to test against), and whether the FISMA environment's Terraform version supports `use_lockfile` (needs 1.10+).

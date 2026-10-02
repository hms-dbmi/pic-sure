# poc.tfvars — NHANES dev AWS account, Story 1 (model enablement + smoke test).
# Terraform has never been run against this — validate before applying:
#   terraform fmt -check && terraform init && terraform validate
#   terraform plan -var-file=poc.tfvars -out=poc.tfplan   # review the plan
#   terraform apply poc.tfplan

region      = "us-east-1"   # from the real `aws bedrock list-foundation-models` output (models.json)
environment = "poc"

# Bake-off pair: both ON_DEMAND (no inference profile needed), both natively
# documented for Converse API tool use. See ../INFRA_PLAN.md Phase 4 for why
# Anthropic models weren't picked — every Anthropic entry in the real catalog
# output requires an inference profile; none are ON_DEMAND.
allowed_model_ids = [
  "amazon.nova-lite-v1:0", # lower tier
  "amazon.nova-pro-v1:0",  # middle tier
]

# EC2 path: not SSO-managed, so a direct attachment works fine.
attach_invocation_policy_to_role_names = ["AvillachLabIamInstanceRole"]

# CLI/SSO path: AWSReservedSSO_* roles are managed exclusively by IAM
# Identity Center — AttachRolePolicy against them is rejected outright, so a
# direct attachment (like the EC2 one above) isn't an option here. Instead,
# create a dedicated role that your SSO session can assume, and test by
# assuming it (see the smoke-test steps) rather than calling Bedrock as the
# SSO role directly.
invocation_role_enabled = true
# invocation_role_trusted_principal_arns = ["ROLE_ARN"]

# Everything else (admin role, log-reader role, guardrail enforcement) stays
# off for this story — Phases 3 and 5 don't exist yet, and Story 1 only needs
# invocation to work. See ../INFRA_PLAN.md Section 10 for the deferred items.

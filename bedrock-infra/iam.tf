data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  name      = "${var.name_prefix}-${var.environment}"
  partition = data.aws_partition.current.partition
  account   = data.aws_caller_identity.current.account_id

  model_regions = distinct(concat([var.region], var.additional_model_regions))

  foundation_model_arns = flatten([
    for r in local.model_regions : [
      for m in var.allowed_model_ids :
      "arn:${local.partition}:bedrock:${r}::foundation-model/${m}"
    ]
  ])

  inference_profile_arns = [
    for p in var.allowed_inference_profile_ids :
    "arn:${local.partition}:bedrock:${var.region}:${local.account}:inference-profile/${p}"
  ]

  invoke_resource_arns = concat(local.foundation_model_arns, local.inference_profile_arns)

  admin_enabled      = length(var.admin_role_trusted_principal_arns) > 0
  log_reader_enabled = length(var.log_reader_trusted_principal_arns) > 0

  boundary_arn = var.permissions_boundary_enabled ? aws_iam_policy.boundary[0].arn : null
}

################################################################################
# Permissions boundary: the ceiling for roles created by this module
################################################################################

data "aws_iam_policy_document" "boundary" {
  statement {
    sid = "AllowBedrockAndSupportingActions"
    actions = [
      "bedrock:*",
      "s3:GetObject",
      "s3:ListBucket",
      "logs:StartQuery",
      "logs:StopQuery",
      "logs:GetQueryResults",
      "logs:FilterLogEvents",
      "logs:GetLogEvents",
      "logs:DescribeLogGroups",
      "logs:DescribeLogStreams",
      "kms:Decrypt",
      "aws-marketplace:ViewSubscriptions",
      "aws-marketplace:Subscribe",
      "servicequotas:GetServiceQuota",
      "servicequotas:ListServiceQuotas",
      "servicequotas:RequestServiceQuotaIncrease",
    ]
    resources = ["*"]
  }

  statement {
    sid       = "DenyBedrockOutsideApprovedRegion"
    effect    = "Deny"
    actions   = ["bedrock:*"]
    resources = ["*"]

    condition {
      test     = "StringNotEquals"
      variable = "aws:RequestedRegion"
      values   = [var.region]
    }
  }
}

resource "aws_iam_policy" "boundary" {
  count = var.permissions_boundary_enabled ? 1 : 0

  name        = "${local.name}-boundary"
  description = "Permissions boundary for Bedrock roles (${var.environment})."
  policy      = data.aws_iam_policy_document.boundary.json
}

################################################################################
# Invocation policy (what the application needs) and optional role
################################################################################

data "aws_iam_policy_document" "invoke" {
  statement {
    sid       = "InvokeApprovedModels"
    actions   = ["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"]
    resources = local.invoke_resource_arns

    condition {
      test     = "StringEquals"
      variable = "aws:RequestedRegion"
      values   = [var.region]
    }
  }

  dynamic "statement" {
    for_each = length(var.guardrail_arns) > 0 ? [1] : []
    content {
      sid       = "ApplyApprovedGuardrails"
      actions   = ["bedrock:ApplyGuardrail"]
      resources = var.guardrail_arns
    }
  }

  dynamic "statement" {
    for_each = var.enforce_guardrail_identifier == null ? [] : [1]
    content {
      sid       = "DenyInvokeWithoutRequiredGuardrail"
      effect    = "Deny"
      actions   = ["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"]
      resources = ["*"]

      condition {
        test     = "StringNotEquals"
        variable = "bedrock:GuardrailIdentifier"
        values   = [var.enforce_guardrail_identifier]
      }
    }
  }
}

resource "aws_iam_policy" "invoke" {
  name        = "${local.name}-invoke"
  description = "Invoke approved Bedrock models only (${var.environment})."
  policy      = data.aws_iam_policy_document.invoke.json
}

# Attach to roles that already exist (for example the app's EC2 instance role).
resource "aws_iam_role_policy_attachment" "invoke_existing" {
  for_each = toset(var.attach_invocation_policy_to_role_names)

  role       = each.value
  policy_arn = aws_iam_policy.invoke.arn
}

data "aws_iam_policy_document" "invoke_trust" {
  count = var.invocation_role_enabled ? 1 : 0

  dynamic "statement" {
    for_each = length(var.invocation_role_trusted_services) > 0 ? [1] : []
    content {
      sid     = "TrustedServices"
      actions = ["sts:AssumeRole"]

      principals {
        type        = "Service"
        identifiers = var.invocation_role_trusted_services
      }
    }
  }

  dynamic "statement" {
    for_each = length(var.invocation_role_trusted_principal_arns) > 0 ? [1] : []
    content {
      sid     = "TrustedPrincipals"
      actions = ["sts:AssumeRole"]

      principals {
        type        = "AWS"
        identifiers = var.invocation_role_trusted_principal_arns
      }
    }
  }
}

resource "aws_iam_role" "invoke" {
  count = var.invocation_role_enabled ? 1 : 0

  name                 = "${local.name}-invoke"
  description          = "Role the application uses to call Bedrock (${var.environment})."
  assume_role_policy   = data.aws_iam_policy_document.invoke_trust[0].json
  permissions_boundary = local.boundary_arn
  max_session_duration = var.max_session_duration

  lifecycle {
    precondition {
      condition = (
        length(var.invocation_role_trusted_services) + length(var.invocation_role_trusted_principal_arns) > 0
      )
      error_message = "Set invocation_role_trusted_services or invocation_role_trusted_principal_arns when invocation_role_enabled is true."
    }
  }
}

resource "aws_iam_role_policy_attachment" "invoke" {
  count = var.invocation_role_enabled ? 1 : 0

  role       = aws_iam_role.invoke[0].name
  policy_arn = aws_iam_policy.invoke.arn
}

################################################################################
# Admin role: manages guardrails, logging config, inference profiles, quotas
################################################################################

data "aws_iam_policy_document" "admin" {
  count = local.admin_enabled ? 1 : 0

  statement {
    sid = "ManageBedrockConfiguration"
    actions = [
      "bedrock:CreateGuardrail",
      "bedrock:CreateGuardrailVersion",
      "bedrock:UpdateGuardrail",
      "bedrock:DeleteGuardrail",
      "bedrock:GetGuardrail",
      "bedrock:ListGuardrails",
      "bedrock:PutModelInvocationLoggingConfiguration",
      "bedrock:GetModelInvocationLoggingConfiguration",
      "bedrock:DeleteModelInvocationLoggingConfiguration",
      "bedrock:CreateInferenceProfile",
      "bedrock:GetInferenceProfile",
      "bedrock:ListInferenceProfiles",
      "bedrock:DeleteInferenceProfile",
      "bedrock:ListFoundationModels",
      "bedrock:GetFoundationModel",
      "bedrock:TagResource",
      "bedrock:UntagResource",
      "bedrock:ListTagsForResource",
    ]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "aws:RequestedRegion"
      values   = [var.region]
    }
  }

  statement {
    sid = "EnableModelsAndManageQuotas"
    actions = [
      "aws-marketplace:ViewSubscriptions",
      "aws-marketplace:Subscribe",
      "servicequotas:GetServiceQuota",
      "servicequotas:ListServiceQuotas",
      "servicequotas:RequestServiceQuotaIncrease",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_policy" "admin" {
  count = local.admin_enabled ? 1 : 0

  name        = "${local.name}-admin"
  description = "Manage Bedrock configuration, not invoke models (${var.environment})."
  policy      = data.aws_iam_policy_document.admin[0].json
}

data "aws_iam_policy_document" "admin_trust" {
  count = local.admin_enabled ? 1 : 0

  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "AWS"
      identifiers = var.admin_role_trusted_principal_arns
    }

    dynamic "condition" {
      for_each = var.admin_require_mfa ? [1] : []
      content {
        test     = "Bool"
        variable = "aws:MultiFactorAuthPresent"
        values   = ["true"]
      }
    }
  }
}

resource "aws_iam_role" "admin" {
  count = local.admin_enabled ? 1 : 0

  name                 = "${local.name}-admin"
  description          = "Bedrock administrators (${var.environment})."
  assume_role_policy   = data.aws_iam_policy_document.admin_trust[0].json
  permissions_boundary = local.boundary_arn
  max_session_duration = var.max_session_duration
}

resource "aws_iam_role_policy_attachment" "admin" {
  count = local.admin_enabled ? 1 : 0

  role       = aws_iam_role.admin[0].name
  policy_arn = aws_iam_policy.admin[0].arn
}

################################################################################
# Log reader role: read-only access to invocation logs (they hold prompt text)
################################################################################

data "aws_iam_policy_document" "log_reader" {
  count = local.log_reader_enabled ? 1 : 0

  dynamic "statement" {
    for_each = var.log_bucket_name == null ? [] : [1]
    content {
      sid       = "ListLogBucket"
      actions   = ["s3:ListBucket"]
      resources = ["arn:${local.partition}:s3:::${var.log_bucket_name}"]

      condition {
        test     = "StringLike"
        variable = "s3:prefix"
        values   = [var.log_key_prefix, "${var.log_key_prefix}/*"]
      }
    }
  }

  dynamic "statement" {
    for_each = var.log_bucket_name == null ? [] : [1]
    content {
      sid       = "ReadLogObjects"
      actions   = ["s3:GetObject"]
      resources = ["arn:${local.partition}:s3:::${var.log_bucket_name}/${var.log_key_prefix}/*"]
    }
  }

  dynamic "statement" {
    for_each = var.log_group_name == null ? [] : [1]
    content {
      sid = "QueryLogGroup"
      actions = [
        "logs:StartQuery",
        "logs:StopQuery",
        "logs:GetQueryResults",
        "logs:FilterLogEvents",
        "logs:GetLogEvents",
        "logs:DescribeLogStreams",
      ]
      resources = [
        "arn:${local.partition}:logs:${var.region}:${local.account}:log-group:${var.log_group_name}",
        "arn:${local.partition}:logs:${var.region}:${local.account}:log-group:${var.log_group_name}:*",
      ]
    }
  }

  dynamic "statement" {
    for_each = var.logs_kms_key_arn == null ? [] : [1]
    content {
      sid       = "DecryptLogs"
      actions   = ["kms:Decrypt"]
      resources = [var.logs_kms_key_arn]
    }
  }
}

resource "aws_iam_policy" "log_reader" {
  count = local.log_reader_enabled ? 1 : 0

  name        = "${local.name}-log-reader"
  description = "Read Bedrock invocation logs (${var.environment})."
  policy      = data.aws_iam_policy_document.log_reader[0].json

  lifecycle {
    precondition {
      condition = (
        var.log_bucket_name != null || var.log_group_name != null || var.logs_kms_key_arn != null
      )
      error_message = "Set log_bucket_name, log_group_name or logs_kms_key_arn when log_reader_trusted_principal_arns is set."
    }
  }
}

data "aws_iam_policy_document" "log_reader_trust" {
  count = local.log_reader_enabled ? 1 : 0

  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "AWS"
      identifiers = var.log_reader_trusted_principal_arns
    }
  }
}

resource "aws_iam_role" "log_reader" {
  count = local.log_reader_enabled ? 1 : 0

  name                 = "${local.name}-log-reader"
  description          = "Read-only access to Bedrock invocation logs (${var.environment})."
  assume_role_policy   = data.aws_iam_policy_document.log_reader_trust[0].json
  permissions_boundary = local.boundary_arn
  max_session_duration = var.max_session_duration
}

resource "aws_iam_role_policy_attachment" "log_reader" {
  count = local.log_reader_enabled ? 1 : 0

  role       = aws_iam_role.log_reader[0].name
  policy_arn = aws_iam_policy.log_reader[0].arn
}

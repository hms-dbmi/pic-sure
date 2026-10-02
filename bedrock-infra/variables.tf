############################
# Core
############################

variable "region" {
  description = "The single AWS Region Bedrock is used in. Policies deny Bedrock calls outside it."
  type        = string
}

variable "environment" {
  description = "Environment label used in names and tags, for example poc or fisma."
  type        = string
}

variable "name_prefix" {
  description = "Prefix for all IAM resource names."
  type        = string
  default     = "bedrock-assistant"
}

variable "tags" {
  description = "Extra tags applied to every resource."
  type        = map(string)
  default     = {}
}

############################
# Model access (invocation)
############################

variable "allowed_model_ids" {
  description = <<-EOT
    Foundation model IDs the application may invoke (the two models for the
    bake-off). Get exact IDs from: aws bedrock list-foundation-models --region <region>
  EOT
  type        = list(string)

  validation {
    condition     = length(var.allowed_model_ids) > 0
    error_message = "Provide at least one model ID in allowed_model_ids."
  }
}

variable "allowed_inference_profile_ids" {
  description = "Inference profile IDs the application may invoke. Leave empty to call models directly by model ID."
  type        = list(string)
  default     = []
}

variable "additional_model_regions" {
  description = <<-EOT
    Extra Regions to allow in foundation-model ARNs. Only needed if a model is
    reachable solely through a system-defined inference profile that routes
    elsewhere AND your security office has approved those destinations.
    Leave empty to stay strictly in var.region.
  EOT
  type        = list(string)
  default     = []
}

variable "guardrail_arns" {
  description = "Guardrail ARNs the application may apply. Leave empty until the Phase 5 guardrail exists."
  type        = list(string)
  default     = []
}

variable "enforce_guardrail_identifier" {
  description = <<-EOT
    If set, every model invocation under the invocation policy is DENIED unless
    it carries this guardrail (value of the bedrock:GuardrailIdentifier condition
    key, normally the guardrail ARN with version). Leave null until the guardrail
    exists. Verify the exact value format against the AWS Bedrock Guardrails
    IAM-enforcement docs before enabling.
  EOT
  type        = string
  default     = null
}

############################
# Invocation role (optional)
############################

variable "invocation_role_enabled" {
  description = <<-EOT
    Create a dedicated invocation role. Default false: the usual path in an
    existing production account is to attach the invocation policy to the role
    your application already uses (see attach_invocation_policy_to_role_names).
  EOT
  type        = bool
  default     = false
}

variable "invocation_role_trusted_services" {
  description = "AWS service principals allowed to assume the invocation role."
  type        = list(string)
  default     = ["ec2.amazonaws.com"]
}

variable "invocation_role_trusted_principal_arns" {
  description = "IAM principal ARNs allowed to assume the invocation role."
  type        = list(string)
  default     = []
}

variable "attach_invocation_policy_to_role_names" {
  description = "Names of EXISTING IAM roles (for example the application's EC2 instance role) to attach the invocation policy to."
  type        = list(string)
  default     = []
}

############################
# Admin role (optional)
############################

variable "admin_role_trusted_principal_arns" {
  description = "Principals allowed to assume the Bedrock admin role. Leave empty to skip creating it."
  type        = list(string)
  default     = []
}

variable "admin_require_mfa" {
  description = "Require MFA to assume the admin role."
  type        = bool
  default     = true
}

############################
# Log reader role (optional)
############################

variable "log_reader_trusted_principal_arns" {
  description = "Principals allowed to assume the invocation-log reader role. Leave empty to skip creating it."
  type        = list(string)
  default     = []
}

variable "log_bucket_name" {
  description = "S3 bucket holding Bedrock invocation logs (Phase 5). Null if not used."
  type        = string
  default     = null
}

variable "log_key_prefix" {
  description = "S3 key prefix of the invocation logs."
  type        = string
  default     = "invocation-logs"
}

variable "log_group_name" {
  description = "CloudWatch Logs log group holding invocation logs (Phase 5). Null if not used."
  type        = string
  default     = null
}

variable "logs_kms_key_arn" {
  description = "Customer-managed KMS key ARN that encrypts the logs. Null if not used."
  type        = string
  default     = null
}

############################
# Boundary and sessions
############################

variable "permissions_boundary_enabled" {
  description = "Create a permissions boundary and apply it to the roles this module creates."
  type        = bool
  default     = true
}

variable "max_session_duration" {
  description = "Maximum session duration in seconds for roles created here."
  type        = number
  default     = 3600
}

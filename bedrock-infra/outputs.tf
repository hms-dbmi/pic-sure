output "invoke_policy_arn" {
  description = "Attach this to the application's role (or use invocation_role_enabled)."
  value       = aws_iam_policy.invoke.arn
}

output "invoke_role_arn" {
  description = "Dedicated invocation role ARN, or null if not created."
  value       = try(aws_iam_role.invoke[0].arn, null)
}

output "admin_role_arn" {
  description = "Bedrock admin role ARN, or null if not created."
  value       = try(aws_iam_role.admin[0].arn, null)
}

output "log_reader_role_arn" {
  description = "Invocation-log reader role ARN, or null if not created."
  value       = try(aws_iam_role.log_reader[0].arn, null)
}

output "permissions_boundary_arn" {
  description = "Permissions boundary ARN, or null if disabled."
  value       = local.boundary_arn
}

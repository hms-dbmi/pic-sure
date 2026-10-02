terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0, < 7.0"
    }
  }

  # Local state is fine for the low-security POC. When this moves to a shared
  # repo or pipeline, switch to a remote backend, for example:
  #
  # backend "s3" {
  #   bucket       = "<state-bucket>"
  #   key          = "bedrock-infra/<environment>/terraform.tfstate"
  #   region       = "<region>"
  #   encrypt      = true
  #   use_lockfile = true # needs Terraform 1.10+; replaces DynamoDB locking
  # }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = merge(
      {
        Project     = var.name_prefix
        Environment = var.environment
        ManagedBy   = "terraform"
        Component   = "bedrock-infra"
      },
      var.tags
    )
  }
}

# AWS credentials for the `aws-sdk` provider

Back to the [README](../README.md).

The service sets no access key or secret. It uses the AWS SDK's default credential chain, so the same image
runs everywhere. Credentials resolve lazily on the first Bedrock call, so a clean startup does not prove they
work; send one real chat request to check. A startup warning, `Failed to load region from
DefaultAwsRegionProviderChain, using US_EAST_1`, is harmless once `BEDROCK_REGION` is set. Set `AWS_REGION`
too if you want it gone.

Do not pass `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN` into the container. They are
short-lived and fixed at container start, so they expire while it runs. Use one of the two paths below, where
the SDK refreshes credentials itself.

## On EC2: instance profile

Nothing to configure in the container; the SDK reads the instance role from the metadata service (IMDS).

- **Hop limit.** The instance's IMDSv2 `HttpPutResponseHopLimit` must be `2`. The default is `1`, and a container on
  Docker's bridge network (the all-in-one starts this service on the `picsure` network) adds a hop, so the SDK cannot
  reach IMDS and fails with `Unable to contact EC2 metadata service`. Keep `HttpTokens = required`. For an existing
  instance:
  `aws ec2 modify-instance-metadata-options --instance-id <id> --http-put-response-hop-limit 2 --http-endpoint enabled`
  (also settable in the console under Actions > Instance settings > Modify instance metadata options). Set it in the
  launch template or infrastructure code too (Terraform: `http_put_response_hop_limit = 2` in `metadata_options`), or a
  rebuilt instance goes back to `1`.
  - This applies only to a host that is an EC2 instance and gets credentials from its instance role. It does not apply
    when the container uses a local profile (see below) on a laptop, VM or other non-EC2 host, where there is no metadata
    service.
  - It also applies if the instance role is only used to assume another role (a profile with `role_arn` and
    `credential_source = Ec2InstanceMetadata`), because that still reads the instance's credentials from IMDS. That
    variant has not been verified on EC2 with this service.
- **Role permissions.** The instance's role needs `bedrock:InvokeModel` and `bedrock:InvokeModelWithResponseStream`
  on the model or inference profile.
- Raising the hop limit lets every container on the instance reach the instance role, not just this one.

## Locally: your authenticated AWS role

Mount your AWS config into the container instead of exporting keys, and select the profile:

- Mount `~/.aws` read-write (SSO token refresh writes to `~/.aws/sso/cache`) at the container user's home, where the SDK
  looks by default, so nothing else is needed for the `default` profile. Set `AWS_PROFILE` only to use another profile, and
  `AWS_CONFIG_FILE` / `AWS_SHARED_CREDENTIALS_FILE` only if you mount the files somewhere other than `~/.aws`. Keep this in
  a local compose override, not the shared compose file.
- The profile must be one the SDK can refresh on its own: `role_arn` plus `source_profile`, or SSO. When the
  underlying login expires, run `aws sso login` on the host; no container restart is needed.

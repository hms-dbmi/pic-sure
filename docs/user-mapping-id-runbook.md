# `user_mapping_id` RAS-to-PIC-SURE Runbook

## Purpose

Use this runbook to configure and verify the `user_mapping_id` claim across this path:

```text
RAS userinfo
  -> Okta RAS IdP profile
  -> Okta Universal Directory user profile
  -> Okta default authorization-server access token
  -> Okta introspection response
  -> PIC-SURE UserClaims
  -> PIC-SURE session JWT
```

PIC-SURE does not call Okta's userinfo endpoint in this flow. Okta still calls the upstream RAS userinfo endpoint as part of the RAS IdP federation flow.

## Data contract

Confirm the following with the RAS team before configuring Okta:

| Property | Required value |
|---|---|
| Claim name | `user_mapping_id` |
| JSON type | String |
| Meaning | Opaque RAS identifier used to correlate the same person across linked identities |
| Stability | Must not change when the user authenticates through another linked IdP |
| Cardinality | One value per mapped RAS user |
| Release channel | RAS userinfo response |
| Required RAS scope | Use the scope specified by RAS; do not assume a new scope is required |

Do not use email, display name, or another mutable profile field as a substitute. Do not use `user_mapping_id` by itself as proof of authorization or dbGaP access.

## Prerequisites

- Okta administrator access to Identity Providers, Profile Editor, and the `default` authorization server.
- A RAS test user for whom RAS returns `user_mapping_id`.
- The scope, if any, that RAS requires to release the claim.
- The PIC-SURE Okta OIDC client ID and client secret.
- A non-production environment in which a complete login can be performed.

## 1. Verify the claim at RAS

Verify with RAS or in an approved RAS test client that the upstream userinfo payload contains a non-empty string:

```json
{
  "sub": "ras-subject",
  "user_mapping_id": "opaque-ras-mapping-id"
}
```

If the claim is missing here, stop. Okta cannot map a value that RAS did not release. Confirm that:

1. The RAS client is entitled to receive the claim.
2. The RAS IdP authorization request includes the required scope, if RAS assigned one.
3. The test user meets any RAS assurance or identity-linking requirements for the claim.

## 2. Add the attribute to the Okta RAS IdP profile

In the Okta Admin Console:

1. Open **Directory > Profile Editor**.
2. Select the profile for the RAS OIDC Identity Provider.
3. Add a string attribute with:
   - Display name: `RAS User Mapping ID`
   - Variable name: `user_mapping_id`
   - External name: `user_mapping_id`
   - Data type: `String`
4. Do not make the attribute end-user editable.

The external name must exactly match the RAS JSON claim, including underscores and case.

## 3. Add the attribute to the Okta user profile

In **Directory > Profile Editor**, select the Okta user profile and add:

- Display name: `RAS User Mapping ID`
- Variable name: `user_mapping_id`
- Data type: `String`

Treat RAS as the source for this attribute. Avoid allowing users or unrelated profile sources to overwrite it.

## 4. Map RAS into Universal Directory

Open the mappings for the RAS IdP profile and configure the inbound mapping:

```text
RAS IdP: idpuser.user_mapping_id
    -> Okta user: user.user_mapping_id
```

Apply the mapping when the federated user is created and when the user signs in again so that corrections from RAS can propagate. If the Okta tenant offers a null-value overwrite option, do not overwrite an existing value with null unless RAS explicitly defines claim removal that way.

Perform a fresh RAS login, then inspect the test user's Okta profile. The Okta user profile must contain the exact RAS value before proceeding.

## 5. Emit `user_mapping_id` in the Okta access token

PIC-SURE calls endpoints under `/oauth2/default`, so configure the **default custom authorization server**, not the org authorization server.

In **Security > API > Authorization Servers > default > Claims**, add or update:

| Setting | Value |
|---|---|
| Name | `user_mapping_id` |
| Include in token type | `Access Token` |
| Value type | `Expression` |
| Value | `user.user_mapping_id` |
| Include in | Prefer a PIC-SURE-specific scope; use `Any scope` only if the claim is appropriate for every client using this authorization server |
| Disable claim | Cleared |

If using a dedicated scope:

1. Create the scope under **Security > API > Authorization Servers > default > Scopes**.
2. Permit it only for the PIC-SURE client through the applicable access policy.
3. Add the scope to the PIC-SURE authorization request.

Use Okta's Token Preview for the PIC-SURE client and test user. Confirm the previewed access token contains:

```json
{
  "user_mapping_id": "opaque-ras-mapping-id"
}
```

Okta documents custom access-token claims and scope restrictions in its [custom authorization-server guide](https://developer.okta.com/docs/guides/customize-authz-server/-/main/) and [custom claims guide](https://developer.okta.com/docs/guides/customize-tokens-returned-from-okta/main/).

## 6. Verify Okta introspection

Obtain a newly issued access token after the mapping and authorization-server changes. Existing tokens are not rewritten.

Use the same issuer and authorization server as PIC-SURE:

```text
https://<okta-domain>/oauth2/default/v1/introspect
```

Example request:

```bash
curl --fail-with-body \
  --request POST \
  --user '<pic-sure-client-id>:<pic-sure-client-secret>' \
  --header 'Accept: application/json' \
  --header 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'token_type_hint=access_token' \
  --data-urlencode 'token=<new-access-token>' \
  'https://<okta-domain>/oauth2/default/v1/introspect'
```

Expected fields include:

```json
{
  "active": true,
  "sub": "...",
  "user_mapping_id": "opaque-ras-mapping-id"
}
```

Never paste a production access token or client secret into tickets, chat, browser-based JWT decoders, or application logs. Okta documents remote validation through its [token introspection endpoint](https://developer.okta.com/docs/guides/validate-access-tokens/).

## 7. Verify PIC-SURE mapping

`RASAuthenticationService` consumes the active introspection response directly. When `user_mapping_id` is non-null, `buildUserClaims` copies it into `UserClaims`. `UserClaims.toHashMap()` then includes it in the PIC-SURE session JWT created by `UserService.getUserProfileResponse`.

Perform a complete PIC-SURE RAS login with a newly issued Okta token and decode the resulting PIC-SURE JWT locally. Confirm:

```json
{
  "user_mapping_id": "opaque-ras-mapping-id"
}
```

Current behavior places the value in the issued PIC-SURE claims; it does not persist `user_mapping_id` as a dedicated column on the PIC-SURE `User` entity. Add persistence separately if a durable database mapping is required outside the authenticated session.

## Troubleshooting

| Checkpoint | Symptom | Corrective action |
|---|---|---|
| RAS userinfo | Claim absent | Confirm RAS entitlement, required scope, test-user linking, and assurance requirements |
| Okta RAS IdP profile | Attribute absent | Add the external IdP schema attribute with external name `user_mapping_id` |
| Okta user profile | Value absent after fresh login | Correct the inbound `idpuser.user_mapping_id` to `user.user_mapping_id` mapping and profile-source behavior |
| Okta Token Preview | Claim absent | Correct the expression, access-token type, scope assignment, client access policy, or test-user profile value |
| Introspection | `active` is false | Obtain a new token and confirm issuer, audience, client, and authorization server |
| Introspection | Active but claim absent | Confirm the token was minted after the change and by `/oauth2/default`; inspect the access-token preview |
| PIC-SURE JWT | Introspection has the claim but PIC-SURE JWT does not | Confirm the deployed auth service contains the `UserClaims.user_mapping_id` mapping and inspect login logs without logging token values |

## Rollback

1. Disable the `user_mapping_id` claim on the default authorization server.
2. Remove the PIC-SURE-specific scope from new authorization requests if one was introduced.
3. Disable the RAS-to-Okta profile mapping only if the Universal Directory value must no longer be updated.
4. Reauthenticate to test rollback behavior; existing access and PIC-SURE tokens retain claims until they expire.

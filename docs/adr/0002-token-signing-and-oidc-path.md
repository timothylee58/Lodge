# ADR 0002: Token signing today, and the path to OIDC

## Status
Accepted for the take-home. The production path below is deliberately *not* built.

## Context
Two services must trust the same identity. claims-service authenticates users (email + password, bcrypt) and issues
a JWT; reporting-service only needs to verify it and read the role. Brief scope excludes SSO/OIDC.

## What exists today
- **HS256 with one shared secret** (`JWT_SECRET`, >= 32 bytes, no default; the app refuses to start without it).
  claims-service signs, reporting-service verifies with the same key. The `dev` profile carries a throwaway value.
- Claims in the token: `sub` (user id), `role`, `name`. Lifetime 2 hours. No refresh token, no revocation list.
- ~40 lines of verifier code duplicated in both services. Role gates are URL rules; ownership checks
  ("my claim", "assigned to me") are in services and key off `sub`.
- Login is protected by a per-IP rate limit (10/min, in memory) and a per-email lockout (5 consecutive failures ->
  15 minutes, stored in Postgres, identical for unknown emails so it cannot be used to discover accounts).

## Why this is acceptable here, and where it is weak
| Weakness | Consequence |
| --- | --- |
| Shared secret | Anyone who can verify can also mint tokens. A compromised reporting-service could forge a MANAGER token for claims-service. |
| No revocation / short refresh | A stolen token is valid until expiry (2h). A deactivated user keeps working until then. |
| We own passwords | Credential storage, reset flow, MFA, breach monitoring and lockout policy are all ours. |
| Lockout is a DoS lever | Anyone can lock a known email by failing 5 times. Per-IP limiting bounds how fast; MFA/captcha and alerting are the real answer. |
| Per-IP limiter is per instance | N instances = N times the allowance; behind a proxy `remoteAddr` is the proxy unless it is enforced at the gateway. `X-Forwarded-For` is intentionally not trusted. |

## Cheap intermediate step (not taken)
Sign with an RSA/EC private key in claims-service (RS256/ES256); give reporting only the public key. Reporting can then
verify but never mint. Cost: key generation and distribution in dev setup and compose (mount a keypair or generate on
first start), plus rotation. Same verifier shape, so it is a half-day change and removes the worst row in the table.

## Production path: external IdP (Keycloak or Azure AD / Entra ID) with OIDC
1. **Browser flow**: Angular uses authorization-code + PKCE against the IdP. No password ever reaches our services,
   so our `/api/auth/login`, bcrypt, `app_user.password`, the lockout table and the rate limiter are deleted; the IdP
   provides MFA, lockout, password policy, SSO, audit and session revocation.
2. **Resource servers**: both services use `spring-boot-starter-oauth2-resource-server` with `issuer-uri`. Keys come from
   the IdP's JWKS endpoint (cached, rotated automatically); `JwtService`, `JwtVerifier` and both filters go away, which
   also removes the duplicated code. Validate `iss`, `aud`, `exp`; reject tokens not meant for this API.
3. **Roles**: map IdP roles/groups (e.g. `roles` claim) to `CLAIMANT/OFFICER/MANAGER` with a `JwtAuthenticationConverter`.
   URL rules stay as they are.
4. **User identity**: ownership checks use the IdP `sub`. `app_user` becomes a profile table keyed by `sub`
   (name, market), created on first login (JIT) or by SCIM sync. Claim rows reference that id unchanged.
5. **Token lifetime**: access tokens 5-15 min with silent refresh via the IdP; revocation = IdP session end + short expiry.
   Service-to-service calls (none today) would use client-credentials.
6. **Ops endpoints and metrics**: put `/actuator` on a separate management port reachable only by the monitoring
   network, instead of a MANAGER token.
7. **Rate limiting**: IdP handles credential abuse; API-wide limits move to the gateway.

## Consequences
We ship a small, fully tested, understandable auth layer that is honest about its limits, and the migration to OIDC
removes code instead of adding it. Anyone running this beyond a demo should take the IdP path, not harden ours.

# JARVIS Cloud API — foundation
This backend scaffold is fail-closed. GET /health is active. Better Auth routes under /api/auth/* are enabled only when BETTER_AUTH_SECRET and BETTER_AUTH_URL are configured. Authenticated chat, team membership, and token-based invitations are implemented; deployment, verified public signup/email delivery, quotas, and production integration checks remain pending.

- Initial free-tier development target: Cloudflare Workers + D1.
- Android is a client; credentials and model inference stay server-side.
- No Gemini API dependency. JARVIS owns orchestration, memory policy, planning,
  tool execution, and a replaceable adapter for a suitably licensed open-weight model.
- Authentication uses Better Auth with D1-native storage; password hashing and session handling are delegated to the maintained library. Never store plaintext passwords. Email delivery/verification is not configured yet, so do not expose public registration until that control is completed.
- Personal conversations are private; team resources require server-side membership checks.
- Never log prompts, message bodies, tokens, credentials, or private content.

## Local development
1. Install Node.js 20+.
2. Run npm install here.
3. Create D1: npx wrangler d1 create jarvis-db.
4. Put returned database ID in wrangler.toml.
5. Apply schema: npx wrangler d1 migrations apply jarvis-db --local.
6. Start: npm run dev; check http://localhost:8787/health.
7. Set local secrets in an untracked `.dev.vars` file: `BETTER_AUTH_SECRET` (at least 32 random characters), `BETTER_AUTH_URL=http://localhost:8787`, and `JARVIS_OWNER_EMAIL` (the one exact owner account email). Never commit `.dev.vars`.
8. Run npm test and npm run typecheck.

## Cost and deployment guardrails
Do not provision paid services or GPU instances in this phase. Free-tier limits can
change; verify quotas before deployment. GPU inference is not assumed unlimited or
permanently free. Do not deploy as a usable public AI service until identity
verification, authorization, quotas, abuse controls, model adapter, privacy-safe
observability, integration tests, and production CORS settings are implemented.
Configure secrets only through the provider secret manager.

## Authentication status
- `POST /api/auth/sign-up/email`, `POST /api/auth/sign-in/email`, `POST /api/auth/sign-out`, and `GET /api/auth/get-session` are handled by Better Auth when configured.
- Password minimum is 12 characters; sessions expire after 7 days and refresh daily.
- CORS is exact-origin allowlisted and credentialed; D1-backed rate limiting uses `CF-Connecting-IP` for client identity.
- Android now stores Better Auth session cookies using the app Keystore-backed credential store. Public signup remains unavailable until a real email delivery provider and mandatory email verification are configured; authenticated D1 end-to-end tests remain pending.
- Team list/create routes are implemented with user-scoped membership queries. A team creator is an ordinary member, not a platform Owner or Admin. Team memberships now have only the `member` role; previous team-level owner/admin labels are normalized by migration 0004.
- A signed-in team member can create an invitation with `POST /v1/teams/:teamId/invitations` and `{ "email": "person@example.com" }`. The random 256-bit token is returned once to the inviter; only its SHA-256 digest is stored. Invitations expire after 7 days, duplicates and self-invites are rejected, and the invited account must have a verified email matching the invitation address.
- The invited person accepts with `POST /v1/team-invitations/accept` and `{ "token": "<64 hex characters>" }`. Acceptance adds only the `member` role, so a person can belong to multiple teams. Team members can list `GET /v1/teams/:teamId/members` and pending invitations with `GET /v1/teams/:teamId/invitations`; the inviter or platform Owner/Admin can revoke with `DELETE /v1/teams/:teamId/invitations/:invitationId`. Invitation create/accept/revoke events are audited.
- Email delivery is not configured yet. The Android Settings screen currently displays the one-time token for the inviter to share privately; do not treat an invitation as emailed automatically.
- There is exactly one platform Owner: the account whose verified sign-in email matches the server secret `JARVIS_OWNER_EMAIL`. The secret is configured out-of-band and is never accepted from an API request. If it is unset, no account receives Owner privileges.
- The Owner can list trusted platform Admins with `GET /v1/platform/admins`, and grant/revoke them with `POST /v1/platform/admins` using `{ "email": "...", "action": "grant" | "revoke" }`. Only existing email-verified accounts can be granted Admin. Only the configured Owner can call these management routes; every change is audited. Admin cannot grant/revoke Admin or create another Owner.
- `GET /v1/platform/access` returns the signed-in user's `owner`, `admin`, or `user` role. Admin and User have no Owner powers.

## Android client configuration
- Set the non-secret deployment URL at build time with `-PJARVIS_CLOUD_BASE_URL=https://your-worker.example`.
- The Android app stores Better Auth session cookies encrypted with Android Keystore. Passwords are sent only to the configured HTTPS auth endpoint and are not persisted.

## JARVIS model gateway contract
- `POST /v1/chat` requires a valid Better Auth session (session cookie is checked server-side).
- Request body: `{ "application_context": "...", "max_tokens": 1200, "messages": [{ "role": "user" | "assistant", "content": "..." }] }`.
- The server always prepends its own safety policy. The optional app context is length-limited and cannot grant permissions or authorize actions.
- Requests are limited to 32 messages, 12,000 characters per message, 48,000 total message characters, 24,000 context characters, and 4,096 output tokens.
- Inference uses the optional Cloudflare Workers AI binding `AI`; model selection is `JARVIS_MODEL`, defaulting to `@cf/meta/llama-3.1-8b-instruct`.
- If the binding is absent, the route returns `503 model_not_configured`. No fake response or vendor fallback is used.
- This is an open-weight hosted inference adapter for the JARVIS agent runtime, not a claim that JARVIS has trained its own foundation model. Model availability, account eligibility, and quotas must be confirmed before deployment.

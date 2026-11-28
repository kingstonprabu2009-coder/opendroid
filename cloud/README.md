# JARVIS Cloud API — foundation
This backend is fail-closed. GET /health is active. Better Auth routes under /api/auth/* are enabled only when BETTER_AUTH_SECRET and BETTER_AUTH_URL are configured. Authenticated chat, email verification/password recovery delivery hooks, automatic model routing, a hard daily usage cap, team membership, and token-based invitations are implemented in source; live deployment configuration and production integration checks must still be verified.

- Initial free-tier development target: Cloudflare Workers + D1.
- Android is a client; credentials and model inference stay server-side.
- No Gemini API dependency. JARVIS owns orchestration, memory policy, planning,
  tool execution, and a replaceable adapter for a suitably licensed open-weight model.
- Authentication uses Better Auth with D1-native storage; password hashing and session handling are delegated to the maintained library. Never store plaintext passwords. Public registration requires email verification; Resend delivery is enabled in code but requires `RESEND_API_KEY` and `RESEND_FROM_EMAIL` Cloudflare secrets before it can work in production.
- Personal conversations are private; team resources require server-side membership checks.
- Migration `0006_user_capacity_limit.sql` enforces a hard maximum of 10 registered accounts at the D1 database boundary, including concurrent sign-up attempts.
- Migration `0007_ai_daily_usage.sql` stores per-account daily AI request counts. The hard limit is 10 requests per account per UTC day; it is not configurable or disableable. Quota updates are atomic and fail closed.
- Authenticated `GET /v1/usage` reports the current account's used, remaining, and daily-limit counts. It exposes no other account's usage; the Android account screen displays the same UTC-day budget.
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

Termux note: the installed Wrangler runtime currently fails on Android arm64 because its `workerd` binary does not support this platform. Source editing and TypeScript work can continue in Termux, but local Worker emulation/deployment needs a supported host or CI runner. This is a platform limitation, not a JARVIS source error.

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
- Android stores Better Auth session cookies using the app Keystore-backed credential store. Sign-up requires email verification; the app does not treat an unverified account as signed in. Resend delivery requires the production secrets above. Authenticated D1 end-to-end tests remain pending.
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
- Request body: `{ "application_context": "...", "max_tokens": 1200, "messages": [{ "role": "user" | "assistant", "content": "..." }] }`. For a user image, content may instead be a short array containing text and an `image_url` whose URL must be a bounded base64 data URL (`data:image/jpeg;base64,...`, PNG, or WebP). Remote image URLs are rejected.
- The server always prepends its own safety policy and a server-owned specialist instruction for the selected task class. Research mode analyzes supplied evidence and explicitly does not claim live web browsing; automation mode plans ordered, verifiable device steps and preserves the non-overridable banking/payment block. The optional app context is length-limited and cannot grant permissions or authorize actions.
- Requests are limited to 32 messages, 12,000 text characters per message, 48,000 total text characters, 24,000 context characters, 5.5 million encoded image characters total, and 4,096 output tokens.
- JARVIS is online-only: the Android app routes chat and planning through the single authenticated JARVIS Cloud gateway. It does not silently switch to local/on-device inference or direct vendor APIs. The JARVIS-owned Brain Router selects exactly one approved open model runtime for each request; it never contacts another AI product API or tries a second model if the selected one fails.
- Single-model routing: general and device automation use the fast GLM Flash route; reasoning, coding, and research analysis use the Qwen route; image requests use the Llama vision route. Only the selected model receives the prompt. Optional allowlisted task overrides are JARVIS_MODEL_GENERAL, JARVIS_MODEL_REASONING, JARVIS_MODEL_CODING, JARVIS_MODEL_RESEARCH, and JARVIS_MODEL_AUTOMATION. The legacy JARVIS_MODEL global override takes precedence when explicitly set; leave it unset to use per-task routing. No Perplexity, Kimi, Manus, ChatGPT, Claude, OpenRouter, or other AI product connector is used by this cloud brain.
- Image requests use only the allowlisted Llama 4 Scout vision route on Workers AI; text-only candidates and paid inference are never sent image payloads. The Android app forwards screenshot JPEG data through the same authenticated Cloud endpoint; image data is size-limited and remote image URLs are rejected.
- No paid provider secrets, paid model overrides, or billing-backed fallbacks are supported. Keep the Cloudflare account on the Workers Free plan; Workers AI free usage is limited to 10,000 Neurons per day and requests stop when the free allowance is exhausted.
- Cost guardrail: Cloudflare Workers AI documents a 10,000-Neuron daily free allocation, shared across requests. Some large hosted models require a paid Workers plan. Treat the allowance as limited free usage, never unlimited or guaranteed zero-cost service.
- If the selected Workers AI model is unavailable or fails, JARVIS returns a generic error. It does not silently contact another model/provider or invent a response.
- This is an independently orchestrated JARVIS application and routing layer using hosted open-model inference; it is not a claim that JARVIS has trained its own foundation model. Model availability, account eligibility, and quotas must be confirmed before deployment.

## Conversation isolation (backend foundation)
- `GET /v1/conversations` lists only the signed-in user's personal conversations (`team_id IS NULL`).
- `GET /v1/conversations?team_id=<id>` lists shared conversations for that team only after checking active membership. Team conversations are shared with all members of that team, not just the creator.
- `POST /v1/conversations` creates a personal conversation when `team_id` is omitted, or a team conversation after the same membership check when `team_id` is supplied.
- `GET|POST /v1/conversations/:conversationId/messages` reads or appends messages only after loading the conversation and authorizing its scope. Personal conversations require the owning user; team conversations require current membership in that exact active team. Client-supplied roles are never used for authorization.
- `POST /v1/chat` optionally accepts `team_id`; if present, the server checks current membership before attempting inference. Omitting it preserves the personal-chat path.
- Android now has a Personal/Team selector and passes the selected team ID through the agent request to POST /v1/chat; team mode is blocked unless JARVIS Cloud is the active provider. Personal memory context is omitted from team requests, and the server adds a team-scope policy after verifying membership.
- Team mode is currently stateless and chat-only: each cloud request contains only the current user turn, local device actions are bypassed, and personal memory is neither sent nor updated. This intentionally avoids leaking personal or mixed local chat history while remote sync is unfinished.
- Android messages still remain in device-local chat history. The Android client has not yet been wired to the remote conversation/message endpoints, so members do not yet share persistent conversation history. Cross-member/cross-team end-to-end tests are pending; this is not a claim that shared team history is fully integrated or deployed.

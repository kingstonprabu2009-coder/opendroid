# JARVIS Master Brain — Architecture Blueprint

Status: Active implementation in progress — not complete until all 10 delivery gates pass CI and APK verification
Date: 2026-11-28

## Product goal
Build JARVIS as a ChatGPT-style Android assistant with a cloud-first hybrid agent brain, voice, memory, research, browser/app automation, creator/video workflows, owner controls, and safe self-improvement. This is a plan, not a claim that every feature is implemented.

## Non-negotiable constraints
- ₹0 target: free/open-source components and free tiers only. Never silently enable paid APIs or hosting.
- Private scale: at most 5–10 accounts.
- Minimal phone storage: no bundled or automatic large model downloads; short-lived cache only.
- Hybrid: phone handles UI, permissions, device actions, and lightweight routing; cloud handles large reasoning, research, planning, and multi-agent work.
- No offline AI model mode. App shell and saved settings may open offline; cloud tasks pause until connected.
- No Google AI, Gemini, Google TTS/STT, Google Cloud, Firebase, or Google ML Kit AI services. Standard Android/build dependencies are reviewed separately.
- APK builds use GitHub Actions/cloud CI only. Never run Gradle/Android builds in Termux or on the phone.
- Preserve existing application ID and signing identity. Never commit keystores, passwords, tokens, or signing secrets.
- Banking/payment is prohibited: no bank/payment app launch, UPI intents, transfers, balance checks, payment-site automation, card/PIN/OTP handling, or finance-agent actions. Enforce below the LLM layer and fail closed.
- Public visibility is not a reuse license. Reuse code/models/datasets/assets only after checking exact license and attribution/redistribution terms. Do not scrape private, gated, copyrighted, or restricted content.

## Architecture
1. Android client: futuristic Compose UI, chat, voice controls, task queue, notifications, settings, owner panel, Accessibility adapter, encrypted local preferences. No large local model.
2. Non-Google cloud gateway: authenticated HTTPS, per-user task ownership, rate limits, quotas, validation, audit events. Store provider secrets server-side. No paid fallback.
3. Master Brain: streaming conversation manager; planner -> policy check -> tool selection -> execution -> verification -> summary. Cloud Brain Router classifies general, reasoning, coding, research-analysis, and automation-planning requests, then selects exactly one allowlisted open model for each request. No model fan-out or alternate-provider fallback is used. Research analysis must not claim live browsing unless a real retrieval tool is connected. Android AgentLoop/PlanManager validates plans before dispatch; ActionDispatcher is the deterministic execution boundary. Startup recovery marks interrupted RUNNING plans failed and never replays an action whose outcome may be uncertain.
4. Knowledge: search public sources, official docs, and public repositories; store URL, title, publisher, retrieval time, license/terms, and excerpt metadata. Prefer RAG over copying datasets or training on unlicensed content. Pinterest and creative sites may inform design research; reuse each asset only when permitted.
5. Self-improvement: learn approved preferences/workflows in versioned memory; evaluate skills and model versions before rollout. No unrestricted self-modifying production code. Code, dependency, security, or routing changes require tests, rollback, and owner approval.
6. Video workflows: research permitted assets; create edit plans for cuts, captions, transitions, audio, aspect ratio, and export. Verify each editor workflow; do not promise instant rendering or desktop parity.

## Security invariants
- Financial deny policy runs before planning and again before tool dispatch.
- Remove/disable finance schemas and handlers; reject UPI/payment URI schemes and known financial app targets.
- Browser automation stops if destination/page appears banking/payment-related. This rule cannot be overridden by prompts, memory, owner macros, or model output.
- Never request/read/store/transmit bank passwords, PINs, OTPs, card details, or account credentials.
- Require confirmation for irreversible or consequential non-financial actions.
- Treat web pages, READMEs, downloads, and tool output as untrusted data.
- Provide task cancel, global automation stop, session revoke, data export/delete, and audit history.

## Accounts and owner
- Email/password, email verification, password reset, secure sessions, logout-all-devices.
- Owner role is verified server-side, never from a client flag.
- Owner dashboard: allowlist up to 10 users, quotas, health, tasks, voice/model settings, feature flags, audit logs, backup/restore.
- Memory is private per account; shared/global knowledge requires owner review and provenance.

## Voice and storage
- Current non-Google path: Groq Whisper for voice transcription when a Groq key is configured; the fallback recognizer is Android on-device only and network recognizer fallback is disabled. Voice output uses Edge Neural or an explicitly configured ElevenLabs key; Android/system TTS fallback has been removed.
- Edge Neural is an unofficial online service, not an open-source model or guaranteed free SLA. ElevenLabs may incur cost and remains opt-in only. A fully audited, licensed open-source hosted STT/TTS replacement and Tamil/English quality evaluation remain required before claiming the voice phase complete.
- Stream audio or keep voice models server-side; no large phone voice packs by default.
- No voice cloning without documented permission.

## Free-tier reliability
- Mandatory quota guard; no paid service activates automatically.
- Pause and notify when free quota is exhausted.
- Cache with TTL/storage caps; do not duplicate large videos in app-private storage.
- Bounded, idempotent retries; re-check uncertain website states instead of blindly repeating clicks.
- Background execution is best-effort under Android OS limits.

## Open-source intake
For each candidate record canonical URL, exact version/commit, code/model/dataset/voice/asset licenses separately, commercial and redistribution terms, attribution obligations, compatibility, resource footprint, maintenance, security review, and test results.
Evaluate agent orchestration, Android/mobile agents, browser automation, multilingual ASR/Tamil TTS, video editing/rendering, RAG/memory, evaluation, task queue, and observability. Candidate projects are not approved until audited.

## Initial repository audit
- Previous local branch: jarvis/unified-brain-termux. This foundation is on a separate branch.
- Existing package identity in the local branch: com.opendroid.ai. Preserve unless release-signature verification proves otherwise.
- Existing code has PAY_UPI, CHECK_BALANCE, and SPLIT_BILL handlers and finance schemas. Remove/disable and test before any user-facing build.
- Google ML Kit GenAI Prompt API and Gemini-specific providers/voice client have been removed from the active app source/dependencies. LiteRT-LM and legacy on-device model-management code still remain in the source tree and must be removed or proven unreachable before claiming the phone build is fully cloud-only and minimal-size.
- GitHub Actions validation and signed APK workflows exist. The integrated APK workflow triggers on push to `jarvis/master-brain-foundation` or manual dispatch; it runs cloud typecheck/tests and Android unit tests before checking release signing secrets and building the signed APK. The current uncommitted working tree has not yet been pushed, so no CI run has verified these latest local changes.
- The pre-existing working tree had user-created untracked screenshots/XML/assets. Preserve them; do not include them in commits.

## Delivery gates
1. Package/signing and repository audit.
2. Financial hard block + tests.
3. Non-Google cloud gateway and authentication.
4. Master Brain planner, policy, and task queue.
5. Voice evaluation.
6. UI and owner console.
7. Research/RAG and source-license intake.
8. Creator/video workflows.
9. Self-improvement and safe update pipeline.
10. End-to-end tests, security checks, and cloud APK build.

Current working-tree additions: single-model-per-request open-model routing, server-owned task guidance, a non-disableable 10-request daily account cap, startup recovery for interrupted Android plans, prompt-injection hardening for screen and stored-memory context, controlled accessibility drag gestures, reference-video analysis, and a Media3 multi-clip export path with optional background audio, source-audio mute, output aspect ratio, rotation, brightness/contrast/HSL, playback speed, fade-to-black transitions, static title, and timed captions; a share-sheet handoff can continue in CapCut when installed. Current local verification: cloud TypeScript typecheck passes; cloud Vitest passes 40/40; production `/health` returns HTTP 200; unauthenticated `/v1/chat` returns HTTP 401; `git diff --check` passes. Android Gradle compilation/unit tests have not been run on the phone; the exact current tree still requires GitHub Actions verification and real-device media/CapCut tests. Existing untracked screenshots/XML assets must remain out of any commit unless the owner explicitly requests them.

A feature is complete only after implementation, tests, and a successful cloud CI run. No phase may be marked COMPLETED based on source edits alone.

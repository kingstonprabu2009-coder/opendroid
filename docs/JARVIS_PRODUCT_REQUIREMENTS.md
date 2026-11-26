# JARVIS Product Requirements

## Product direction
JARVIS is a cloud-first AI assistant for an initial group of approximately 5-10 users. The Android app is a client; AI inference and shared services run in the cloud. A local on-device language model is not part of the target design.

## Main app experience
- Normal Chat: familiar message thread, simple composer, send button, and microphone button that converts speech to text.
- Live Chat: separate real-time voice conversation mode with clear listening, thinking, speaking, and end states.
- Orbit: visual workspace for active tasks, research, and agent progress.
- Move: approved actions and automation workflows.
- Keep the everyday UI minimal. Hide developer/provider configuration from normal users, while retaining essential privacy, account, permission, and accessibility controls.
- Premium visual style, readable typography, responsive layouts, and useful empty/loading/error states.

## Confirmation requirements
Before consequential actions such as sending messages, deleting or moving shared files, publishing, submitting forms, payments, or executing multi-step external actions, show a concise action plan and require explicit confirmation. Read-only actions may proceed without confirmation. Show progress and verify completion; never report success without tool confirmation.

## Public multi-user and teams
Anyone can register and create teams. Users may join multiple teams. Roles include platform administrator, team owner/admin, and member. Personal conversations and files must remain private. Team resources are available only to authorized members. Enforce authorization on every server request.

## Cloud-first architecture
- Android client: UI, secure session handling, microphone capture, and approved device-side adapters.
- Cloud API: authentication, authorization, rate limiting, task orchestration.
- Cloud AI: JARVIS-branded model service and agent runtime; no mandatory ChatGPT or Claude API dependency.
- Database: accounts, teams, memberships, task/chat metadata, permissions, audit events.
- Object storage: private user and team uploads with access policies.
- Background jobs: long-running tasks, document indexing, media processing, notifications.
- Observability: privacy-safe errors, traces, and task outcomes; never log secrets.

## Model goal
Use a suitably licensed open-weight foundation model, then evaluate and fine-tune where compute and licensing permit. Target strong research, reasoning, coding, and tool-use quality comparable to leading assistants; equivalent performance is an aspiration, not a guarantee. Use retrieval for current web/document knowledge instead of training all website content into model weights.

## Editing and automation
Show proposed editing operations before destructive/export/publish actions. Use supported media APIs and device integrations. Do not promise unrestricted control of third-party apps where APIs or accessibility constraints prevent it. External image/video generation services may have separate limits or costs.

## Bug fixing and releases
Capture privacy-safe diagnostics, reproduce issues, test fixes, and deploy backend changes centrally where possible. Android client defects may require a signed APK update. AI-generated code changes must not deploy without tests and authorized release approval. Keep versioned deployments, backups, and rollback.

## Cost
Prefer free tiers for initial development, but never promise unlimited or permanently free GPU inference, storage, or bandwidth. Track usage and enforce quotas to prevent unexpected charges. Keep cloud providers replaceable.

## Delivery stages
1. Inspect current Android app and local-model implementation.
2. Redesign and test the client UX.
3. Implement cloud authentication, tenant-isolated database, and storage.
4. Implement cloud model gateway and JARVIS agent.
5. Add Live Chat, speech input, Orbit, Move, and confirmation flows.
6. Add admin/operations dashboard, diagnostics, and deployment pipeline.
7. Run security, privacy, functional, and device tests.
8. Build and sign the final APK only after explicit release approval.

# JARVIS Open-Source Reuse Audit

Date: 2026-11-27

## Goal

Build a distinct JARVIS brain with a ₹0 software/API budget: deterministic routing, local inference when available, memory scoped to the owner, and permission-gated Android actions. Public source code is reusable only under its actual license; model weights and hosted inference have separate terms and costs.

## Reviewed upstream projects

| Project | License checked | Useful ideas | JARVIS decision |
|---|---|---|---|
| https://github.com/riceharvest/LocalIntelligence | Apache-2.0 | Small Kotlin agent loop, bounded tool surface, deterministic loop detection, risk gates, context budgets, local-first privacy | Reuse design ideas; do not transplant its whole app. Its documented loop uses a bounded step count, a small visible-tool set, malformed-output retries and duplicate-call loop detection. JARVIS already has AgentLoop, ActionDispatcher, plan approval and memory; this pass adapts the bounded-plan principle with a 10-step validator limit and focused tests. |
| https://github.com/janik96v/firn-local-ai | Apache-2.0 | Android llama.cpp runtime, local memory, tool loop, model downloads, OpenAI-compatible local interface | Reference only for runtime/model lifecycle and memory improvements. Do not replace JARVIS's existing provider and agent architecture wholesale. |
| https://github.com/ggml-org/llama.cpp | MIT | GGUF inference engine and Android native integration | Existing JARVIS LlamaCppProvider is the integration point. Preserve MIT copyright/license notices for any copied or vendored upstream code. |

## Existing JARVIS capabilities found in source

- `AgentLoop.kt`: task planning, proposed-plan approval, action execution, session pinning, memory context and recovery paths.
- `ActionDispatcher.kt`: Android action registry and dispatch.
- `LLMProviderFactory.kt`: provider construction, request wrapping, explicit fallbacks, risk-aware planning provider selection.
- `HybridOnDeviceProvider.kt`: AI Core / LiteRT-LM backend selection.
- `LiteRTLMProvider.kt`: local model verification, memory compatibility checks, inference, engine lifecycle and tool-call parsing.
- `OnDeviceModelRegistry.kt`: catalogued local models, pinned artifact revisions, sizes, SHA-256 metadata and device memory safety checks.
- `LlamaCppProvider.kt`: local GGUF inference adapter; current 7B profile requires a large model download and a high-memory device.

## Free-first implementation direction

1. Keep the JARVIS agent loop and action permissions as the authority. The model may propose actions; it must not grant itself permissions or bypass the approval policy.
2. Prefer an already-installed on-device model for private/offline tasks. Do not silently send personal memory or conversation data to an external provider.
3. For a phone with limited RAM/storage, evaluate the catalogued Qwen 2.5 0.5B LiteRT model before attempting the 7B GGUF profile. The model download uses mobile data/storage even when the model itself is free.
4. Treat public/free hosted inference as optional and quota-limited, never as guaranteed unlimited or permanently free.
5. Keep Kimi K3/K3.1 Pro as an optional future provider only; its hosted API is metered and therefore excluded from the ₹0 default route.
6. Before copying any upstream source file, record the exact path, commit/revision, license, copyright notice, modifications and required NOTICE/attribution. Model weights require a separate license review.

## Verification boundary

A repository's public visibility does not itself grant reuse rights. Apache-2.0 and MIT permit reuse subject to their notice/license conditions. The two reviewed Android projects are reference implementations, not proof that their entire feature sets are tested on this device. Any adopted code must compile and receive focused tests in JARVIS before it is considered integrated.

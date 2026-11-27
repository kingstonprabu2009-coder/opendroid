# JARVIS Unified Brain — Cloud Runtime

## Current implementation

The Cloudflare Worker owns the model-routing decision. The Android client continues to call one authenticated JARVIS endpoint; it does not need to know which foundation model handled a request.

The router selects one model for each request. Kimi is the designated first-priority brain: when the server operator enables metered inference, general chat routes to Kimi K3 and reasoning/coding route to Kimi K3.1 Pro. If paid inference is not enabled, JARVIS uses its free model defaults and recovery chain.

| Task | Default model | Purpose |
|---|---|---|
| General chat (Kimi enabled) | `kimi:kimi-k3` | First-priority Kimi K3 general brain |
| Reasoning / planning (Kimi enabled) | `kimi:kimi-k3.1-pro` | First-priority Kimi K3.1 Pro reasoning |
| Coding / debugging (Kimi enabled) | `kimi:kimi-k3.1-pro` | First-priority Kimi K3.1 Pro coding |
| Free general fallback | `@cf/zai-org/glm-4.7-flash` | Used when Kimi is not enabled or unavailable |
| Free reasoning fallback | `@cf/google/gemma-4-26b-a4b-it` | Multi-step reasoning fallback |
| Free coding fallback | `@cf/openai/gpt-oss-20b` | Coding fallback |
| Optional coding | `@cf/qwen/qwen3.8-27b` | Vision-capable Qwen route; explicitly configured |
| Premium reasoning | `@cf/deepseek-ai/deepseek-v4.1-flash-1017` | Paid opt-in only |
| Premium agentic coding | `@cf/zai-org/glm-5.3` | Paid opt-in only |
| Premium reasoning | `@cf/openai/gpt-oss-120b` | Higher-capability open-weight model; paid opt-in only |
| Kimi K3 | `kimi:kimi-k3` | Official Moonshot API adapter; explicit paid opt-in |
| Kimi K3.1 Pro | `kimi:kimi-k3.1-pro` | Official Moonshot API adapter; explicit paid opt-in |
| GPT-6.2 Astra Fast | `openai:gpt-6.2-astra:fast` | OpenAI API recovery route; explicit paid opt-in |
| Fallback | `@cf/google/gemma-4-26b-a4b-it` | Recovery if the selected model is unavailable; also the default vision route |

The router calls only the selected model during normal operation. With paid inference explicitly enabled, Kimi K3 is primary for general chat and Kimi K3.1 Pro is primary for reasoning/coding; GPT-6.2 Astra Fast and approved metered Workers AI models are ordered recovery candidates. If paid inference is disabled, JARVIS uses the free-by-default Workers AI routes. If a provider fails or returns empty output, JARVIS walks the ordered recovery chain; it does not send the same private prompt to every model in parallel. A configured Kimi/paid specialist remains opt-in and falls back to the free chain. Qwen 3.8 27B is part of the recovery pool. DeepSeek V4.1 Flash and GLM-5.3 explicitly require paid Workers billing or prepaid AI Gateway credits; GPT-OSS 120B has metered token pricing. These models are never selected unless the server operator explicitly enables premium inference and configures the route. GPT-OSS 20B/120B are open-weight OpenAI releases, not the proprietary GPT-6 / ChatGPT model.

## Runtime configuration

Optional Cloudflare Worker variables:

- `JARVIS_MODEL_GENERAL`
- `JARVIS_MODEL_REASONING`
- `JARVIS_MODEL_CODING`
- `JARVIS_MODEL_FALLBACK`

The old `JARVIS_MODEL` variable remains as a global override for backwards compatibility. Leave it unset to enable automatic task routing. Free model overrides are allowlisted. Premium overrides require the server operator to explicitly set `JARVIS_ENABLE_PAID_MODELS=true` and configure a specific model. Kimi routing uses the official Moonshot OpenAI-compatible API adapter, not the deprecated Cloudflare K2.6/K2.7 Code entries. Configure `MOONSHOT_API_KEY` as a Cloudflare secret, then set `JARVIS_MODEL_GENERAL=kimi:kimi-k3` and/or `JARVIS_MODEL_REASONING=kimi:kimi-k3.1-pro`; coding can use the K3.1 Pro route too. The free fallback remains on the Cloudflare free-model allowlist.

## What can and cannot be reused

| Ecosystem | What is reusable | Important boundary |
|---|---|---|
| Kimi K3 | Official model weights, model card, and license-governed runtime integration | Requires specialist compute; not a free Termux deployment |
| DeepSeek / Qwen | Open model families and their inference-compatible implementations | Check each checkpoint's own license and serving requirements |
| OpenAI | GPT-OSS open-weight models and SDK/tooling | GPT-6 / ChatGPT model weights and internal source are not public |
| Google | Gemma open-weight models and SDKs | Gemini model weights are not public |
| Anthropic | Public SDKs and agent/tool interfaces | Claude model weights and internal source are not public |
| Meta Llama | Public model weights and ecosystem tools | Each Llama release has its own license terms |
| OpenCode | Open-source coding-agent workflow and tool orchestration | It is an agent framework, not a foundation model or model weights |

JARVIS must combine compatible open models, inference runtimes, agent frameworks, memory, and voice adapters. Copying an SDK does not copy the intelligence of a proprietary model. Reuse only public source under its license; do not scrape or claim access to private model source.

## Kimi K3 source repository and licensing boundary

Inspected the official `MoonshotAI/Kimi-K3` GitHub repository. Its root contains `README.md`, `LICENSE`, an assets folder, and `k3_tech_report.pdf`; it is a model card, license, and technical-report repository, not a complete runnable inference source tree. The README links to Moonshot's Hugging Face organization for the actual open weights. Forking this GitHub repository alone would only fork those documents and would not bring the 2.8T-parameter weights or a runnable Kimi server into JARVIS, so JARVIS integrates the official API adapter instead of creating a misleading documentation-only fork.

The Kimi K3 license permits broad use, copying, modification, distribution, and deployment subject to retaining its notice and following its Model-as-a-Service and scale-related conditions. Review those terms before offering hosted inference to third parties.

Kimi remains JARVIS's first-priority model family whenever metered Kimi inference is explicitly enabled. However, Cloudflare's current model pages mark both Kimi K2.6 and Kimi K2.7 Code as deprecated (November 20, 2026), so neither is enabled in this router, even with paid mode enabled. This avoids shipping a model ID that the provider has withdrawn. Recheck the official catalog for a replacement Kimi release before adding it.

Kimi K3 weights and hosted Kimi inference are separate deployment choices. Large-scale Kimi weights are not a realistic free Termux-phone deployment. Review the exact model license, available serving endpoint, and compute costs before any future weight redistribution or hosted deployment.

## Online vision path

The Android client forwards a bounded JPEG data URL through the authenticated JARVIS Cloud endpoint. The Worker rejects remote image URLs and accepts only bounded base64 image data. Image requests are routed only to verified vision-capable providers: Gemma 4 on Workers AI by default, or GPT-6.2 Astra Fast when premium inference is explicitly enabled. Text-only Kimi and other text-only candidates are not selected for image payloads. The app remains a client; image inference runs in the cloud.

## Cost and capacity

Cloudflare Workers AI documents a free allowance of 10,000 Neurons per day; requests beyond that allowance require a paid plan. This is a quota, not unlimited free inference. The backend now defaults to 10 requests per account per UTC day (up to 10 accounts), but all users share the same 10,000-Neuron pool. Long prompts or outputs can exhaust it earlier, so the provider may still return a quota error.

Open-weight model source code, model weights, and inference compute are separate things. This router uses Cloudflare-hosted model inference; it does not download or embed the full model weights into the APK.

The Android voice path keeps the user's visible Thunglish/English reply unchanged, but converts recognizable Latin-script Tamil into Tamil script for the local Tamil TTS engine. Gemini Live is off by default so the free JARVIS path does not silently depend on a paid Gemini key.

## Next integration layers

1. Keep one JARVIS-facing API and hide model selection from normal users.
2. Add structured tool calling and agent execution behind the model router.
3. Connect the existing memory tiers to retrieval with strict personal/team separation.
4. Connect the existing Tamil speech stack to the same JARVIS session.
5. Add usage budgets and per-account limits for the initial ten users.
6. Run the full final verification only after implementation is complete.

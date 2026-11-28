# JARVIS Open-Source Research Register

Date: 2026-11-28
Status: Candidates for evaluation; none of these entries is approved for production integration yet.

## Research rule

Use public sources to learn from and locate candidate software, models, datasets, and creative references. Do not bulk-copy Pinterest boards, videos, images, datasets, or private website content. Publicly visible does not mean licensed for reuse. For every exact artifact, verify its own code, model-weight, dataset, voice, and asset terms before download or redistribution.

## Candidate shortlist

| Area | Candidate | Initial license / evidence | JARVIS fit | Decision |
|---|---|---|---|---|
| Android/mobile automation | droidrun/mobilerun | GitHub project lists MIT | Reference implementation for Android/iOS UI inspection, accessibility, taps, swipes, and agent workflows | Evaluate interfaces and dependencies; do not add a second automation stack before comparing with existing Accessibility service |
| Browser / general agent | OpenManus | Curated open-source listing identifies MIT | Study planner/tool execution and browser automation patterns for the cloud brain | Evaluate server-only components and security model |
| Video editor | OpenCut-app/OpenCut | Curated video-editing list identifies MIT | Candidate for editing workflow concepts and reusable timeline behavior | Check runtime/platform and license at exact commit; likely not an Android library drop-in |
| Video editing research | KDE Kdenlive, Shotcut | GPL-3.0 listings | Study timeline, filters, proxy, caption, and effect workflows | Do not embed code until GPL obligations and Android compatibility are reviewed |
| Speech recognition | whisper.cpp | MIT listing | Multilingual STT candidate; evaluate Tamil/Tanglish accuracy and server CPU cost | Server-side candidate; do not bundle large models on phone |
| Tamil speech synthesis | AI4Bharat Indic-TTS | Repository code lists MIT; model weights are separately distributed | Indian-language TTS research, including Tamil | Verify the exact weight license and inference dependencies before use |
| Compact Tamil TTS | Indic Tiny TTS Tamil | Model card lists 22.5M parameters, about 185 MB repository footprint; weights CC-BY-4.0, code MIT | Strong candidate for cloud-hosted Tamil voice while keeping phone storage low | Evaluate latency, voice quality, attribution, CPU RAM, and free-host feasibility |
| Indian-language TTS server | VEXYL-TTS | Public README describes Tamil and other Indic languages, WebSocket and REST | Candidate API shape for streamed speech | Verify code and model licenses separately; do not assume the wrapper license covers weights |

## Cross-platform inspiration

- Pinterest, public design galleries, creator sites, and video platforms can be used to discover interface patterns, editing ideas, and workflow references.
- Store only links, short notes, and permitted metadata by default.
- Download a specific image, clip, sound, font, or template only when its individual license or rights-holder permission allows the intended use.
- Do not copy a platform's private datasets, user uploads at scale, paid content, or access-controlled data.
- For training, prefer explicitly licensed datasets and user-approved examples. Use retrieval (RAG) with source citations before considering fine-tuning.

## Google-independence audit — current code findings

The current branch still contains Google-related paths that must be replaced or removed before claiming Google-independent operation:

1. LLM providers and model runtimes: Google Gemini provider, Gemma/ML Kit GenAI Prompt API, and LiteRT-LM are present in the code and Gradle dependencies. The active provider method currently routes chats to JARVIS Cloud, but old provider entries remain in the app.
2. Speech recognition: Android SpeechRecognizer is used for voice recognition and wake-word detection. The selected engine is controlled by the device; the standard recognizer can be supplied by Google. This is not acceptable as the final JARVIS voice path.
3. Speech output: Android TextToSpeech is initialized as a fallback. The installed system TTS engine may be Google. The final voice path must not silently use system TTS.
4. Search and web navigation: InformationActions and SystemActions contain Google Search, Google News, Google Translate, Google Play search, and Google homepage defaults.
5. App integrations: Google Meet, Google Clock, and Google Chromecast/Home package references exist in communication, calendar, and smart-home actions.

Replacement policy:
- Keep the Android platform SDK and standard AndroidX/build tooling needed to ship an Android app; these are not Google AI or cloud services.
- Remove Google consumer-service integrations and Google AI/model services from user-facing routing.
- Replace search with a non-Google search source; replace voice with independently selected STT/TTS engines; remove Google-only app shortcuts.
- Do not delete provider/runtime classes until all call sites, settings, persistence migrations, and tests have been mapped.
- Do not claim Google independence until a source scan, dependency scan, and runtime test confirm no Google product service is called.

## Cost and device constraints

- Keep all large model weights and heavy inference server-side; phone stores no LLM or TTS model by default.
- A 185 MB Tamil TTS model is not suitable for bundling in the APK; if selected, it must be hosted remotely or streamed from a server.
- Free source code does not make compute, storage, bandwidth, or inference free. A ₹0 design must stop safely when free-tier limits are reached.
- No final release APK should be built until the implementation gates and cloud validation pass.

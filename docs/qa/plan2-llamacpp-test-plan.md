# Plan 2 QA: Qwen2.5-7B GGUF / llama.cpp

## Before device testing
- [ ] Build `:app:assembleDebug` with Android NDK and CMake installed.
- [ ] Confirm `libopendroid_llama.so` is packaged under `lib/arm64-v8a/`.
- [ ] Select `Llama.cpp (Local 7B)` in Settings.
- [ ] Download the approximately 4.68 GB Qwen2.5-7B-Instruct Q4_K_M GGUF over Wi-Fi.
- [ ] Confirm storage has at least 5.5 GB free before download/load.

## Runtime
- [ ] Verify no API key is requested and no cloud provider is contacted.
- [ ] Verify GGUF header/size check rejects incomplete downloads.
- [ ] Verify insufficient available RAM produces a safe error, not a crash.
- [ ] Run English and Tamil smoke prompts; record first-token latency and tokens/sec.
- [ ] Verify short conversation context and 2048-token context limit.
- [ ] Verify a valid tool request produces only an allow-listed tool name and JSON args.
- [ ] Verify ordinary chat remains text and is not misclassified as a tool call.
- [ ] Verify cancellation and explicit engine release.
- [ ] Verify YouTube/app launching and existing voice input/output still work through the current app pipeline.
- [ ] Stress test heat, memory pressure, backgrounding, and app switching for at least 15 minutes.

## Acceptance
- [ ] No ANR, native crash, or OS low-memory kill.
- [ ] Tamil quality acceptable to the user.
- [ ] Existing Gemini and LiteRT provider tests remain unchanged and pass.
- [ ] No automatic cloud fallback from the local 7B provider.

Native compilation and physical-device validation are still pending in this environment.

# ADR-012: Add a local Qwen 7B GGUF runtime with llama.cpp

- Status: Implementation scaffold added; Android native build and device validation pending
- Target ABI: `arm64-v8a`
- Primary model: Qwen2.5-7B-Instruct, GGUF Q4_K_M
- Approximate model download: 4.68 GB (publisher listing)
- Existing Gemini and LiteRT providers remain unchanged and separately selectable

## Decision

Add a separately selectable `Llama.cpp (Local 7B)` provider. It has no API key,
cloud endpoint, subscription, or token-credit dependency. The model is downloaded
on demand into app-private external files storage and is not packaged in the APK.
The app must never silently route this provider to a cloud service.

The native build fetches llama.cpp tag `b7600` and builds the portable Android
`arm64-v8a` target using the Android NDK. The build disables `GGML_NATIVE`,
`GGML_OPENMP`, `GGML_LLAMAFILE`, and `LLAMA_OPENSSL`. No global `-march` flag is
used. This follows the upstream Android build guidance.

## Runtime safeguards

- Start with a 2048-token context and cap generation at 384 tokens.
- Use memory-mapped model weights.
- Refuse to load when Android reports low memory or less than 3.5 GiB available RAM.
- Require at least 5.5 GB free storage before loading.
- Validate GGUF magic and minimum expected file length before load.
- Run native inference off the main thread and expose explicit release/cancel hooks.
- Do not automatically fall back to a paid/cloud API if local inference is unavailable.

A 6 GB RAM device is not guaranteed to run a 7B model reliably. The 4.68 GB
quantized file plus runtime and Android memory requirements may exceed available
RAM. The guard intentionally prefers a clear refusal over risking an OS-level kill.

## Integration scope in this change

- Adds `LlamaCppProvider` to the provider catalog and factory.
- Adds a Settings provider entry and a Wi-Fi-only model download action.
- Adds a JNI bridge and CMake FetchContent build for llama.cpp.
- Adds basic Qwen chat formatting and constrained JSON tool-call parsing.
- Keeps the existing LiteRT 0.5B and Gemini implementations unchanged.

## Known limitations / delivery gates

1. Native library has not been compiled in this environment; Gradle wrapper download was blocked by unavailable network access.
2. Build on Android NDK/CMake and confirm `libopendroid_llama.so` is packaged for `arm64-v8a`.
3. Download the GGUF model and verify its complete file and SHA-256 before production use (the current gate checks GGUF header and file length only).
4. Test Tamil prompts, JSON tool calls, cancellation, memory pressure, and provider switching on the actual phone.
5. The first implementation emits a completed response as one Flow chunk; token-by-token streaming and full Qwen tool-template conformance remain to be validated.
6. Existing voice input/output are not changed by this provider patch; verify that the current app voice pipeline remains compatible.

No APK build or GitHub push is implied by this change.

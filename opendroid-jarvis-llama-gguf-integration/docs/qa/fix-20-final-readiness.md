# JARVIS Fix 20 – UI, voice and local 7B readiness

## Included in this source archive

- Refined the home-screen Orb animation to use calm, state-driven colors: red while listening, purple while thinking, cyan while speaking, and green while executing.
- Removed the continuously cycling cyan/red/green glow and removed the Orb's rotation; speaking feedback is a small translation-only vibration.
- Renamed the Memory home tab label to Growth; the existing Personal Growth Engine and Knowledge Graph remain in place.
- Preserved the existing speech recognition, wake-word service, TTS, voice subtitle and app-action code.
- Kept the Qwen 2.5 7B GGUF / llama.cpp provider and its model download setup from Fix 19.
- Reduced the initial 7B context to 1024 tokens and bounded native prompt-evaluation batches (256 / 128) to reduce peak memory pressure.
- Raised the provider's available-RAM safety threshold to 4 GB; it refuses to load below this threshold rather than intentionally pushing the phone into low-memory conditions.

## Important limitations

- A 7B Q4 model is still a high-memory workload. A phone advertised as 6 GB RAM may not have 4 GB free after Android and other apps are running. In that case JARVIS will refuse to load the model. No 6 GB device can be promised to run this model smoothly without testing.
- The archive contains source code, not a prebuilt APK and not the multi-gigabyte GGUF model. The model is downloaded separately by the app's model setup screen.
- Native llama.cpp is fetched by CMake at build time from the pinned upstream revision. A successful Android NDK build and on-device inference test are still required.
- Build verification could not be completed in this environment because the Gradle wrapper distribution could not be downloaded (network DNS unavailable).
- Existing voice features are preserved; wake-word reliability, Tamil recognition accuracy, and Tamil TTS quality must be verified on the target phone and its installed speech services.
- Personal Growth Engine is an adaptive memory/knowledge-graph feature. It does not autonomously rewrite application source code or train the underlying model.

## Recommended verification sequence

1. Build the debug APK with Android SDK + NDK + CMake available.
2. Confirm the arm64-v8a native library is packaged.
3. Install on the target phone and inspect free RAM before downloading/loading the 7B model.
4. Test typed Tamil, Tamil voice input, Tamil TTS, wake word, and app launching separately.
5. Load the GGUF only when the safety gate allows it; monitor memory, thermal state, cancellation, and app switching.
6. Do not treat the feature as release-ready until the above tests pass.

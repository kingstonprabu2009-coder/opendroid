# JARVIS Reverse Engineering Workspace

## Separation
The Reverse Engineering workspace is a dedicated feature area. It is not a model router and is not merged into the general JARVIS Brain. The current Android entry point is the Reverse home tab and ReverseEngineeringScreen.

## Upstream reference
- Project: https://github.com/AndrewPopesku/video-reverse-engineering
- Its README declares MIT licensing. The GitHub repository metadata does not expose a recognized SPDX license field, so preserve attribution and verify the repository's current license notice before copying or vendoring source files.
- Useful concepts: scene/keyframe extraction, timestamped transcript, visual/text/audio analysis, structured timeline export, semantic scene search.
- The upstream implementation includes Gemini-specific components. Those components are deliberately not imported because JARVIS is required to remain Google/Gemini-independent.

## Current implementation
- User-selected local video through Android's document picker.
- Native MediaMetadataRetriever extracts nine evenly spaced representative frames.
- Contact sheet preserves each frame's aspect ratio and labels approximate timestamps.
- The configured JARVIS Cloud vision path describes visible editing characteristics.
- The analysis distinguishes observed facts from estimates and marks details that still frames cannot prove as unknown.
- A concise style recipe is stored in JARVIS semantic memory for later reference-style requests.

## Not yet implemented
- True scene-boundary detection and variable-density keyframe extraction.
- Audio separation/transcription and beat/speech alignment.
- OCR with timestamped text tracking.
- Full motion/transition/effect parameter estimation.
- Scene-aware timeline reconstruction, timed caption generation, deterministic transitions, and reference-matched effect parameter estimation.
- App-specific CapCut project import/export or reliable timeline automation.

## Accuracy and speed policy
- Never invent transition durations, motion curves, audio sync, or exact effect settings from still frames.
- Prefer measured timestamps and verified metadata over visual guesses.
- Batch edit instructions into a timeline plan; apply deterministic operations in batches where the editing engine supports them.
- A fast plan does not mean a long source video can be rendered in one second. Rendering time depends on duration, resolution, effects, hardware, and export settings.
- Preserve the original media and export a new copy by default.

## Native timeline editor (implemented in source, validation pending)
- `Media3TimelineEngine` uses AndroidX Media3 Transformer to trim selected time ranges, concatenate clips, and export a new MP4 through a temporary file before copying to the user-selected destination.
- Reverse Engineering UI accepts up to 40 sorted, non-overlapping keep-ranges in seconds (for example `0-5,7-12,15-20`).
- Optional looping background audio, source-audio mute, rotation, brightness, contrast, HSL adjustments, output aspect ratio, crop-to-fill, playback speed, a static title, output-timeline timed captions, and fade-to-black at clip edges are included in the current export path.
- The UI persists document-provider URI permissions where supported. This preserves access rights for a URI if the app retains that URI, but selected media and editor UI state are not currently restored automatically after process recreation.
- The original source is not overwritten. Export progress and cancellation/failure state are surfaced in the UI. The temporary rendered MP4 is checked for an `ftyp` header before copying, and the destination copy is checked for byte count before success is reported.
- `NativeVideoCutEngine` remains as a legacy fast stream-copy implementation; its starts may snap to previous keyframes and it is not the current UI export engine.
- Crossfades, animated caption templates, audio ducking, LUT presets, keyframes, automatic beat/speech sync, and style-recipe-to-edit parameter mapping remain incomplete. Fade-to-black is the only transition currently implemented. The current engine is not a complete CapCut replacement.
- Unit tests cover range parsing/validation. Android compilation, codec compatibility, playback, output audio mixing, overlay placement, and real-device export still require CI/device testing.

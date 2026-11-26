JARVIS / OpenDroid — Robolectric CI download fix
=================================================

Base branch:
  jarvis/llama-gguf-integration

Base commit:
  ee283dcfd03f6916eca32205a7a62be910f033eb

This is a PATCH ZIP (not a full repository archive). It changes only:
  1. app/build.gradle — set Robolectric's Android SDK artifact repository to
     Maven Central's canonical endpoint.
  2. .github/workflows/build.yml — cache Robolectric Android SDK artifacts
     between CI runs.

Evidence:
The latest CI run completed 1,445 tests and showed 5 Robolectric test classes
failing at MavenArtifactFetcher with an IOException. These tests failed while
Robolectric fetched Android framework jars; the job log did not expose the
exact HTTP status. This patch targets that download path and does not disable
or skip tests.

Apply from the repository root in Termux:
  unzip -o JARVIS-Robolectric-CI-Fix.zip
  git apply JARVIS-Robolectric-CI-Fix.patch
  git diff --check
  git diff

If the diff looks correct:
  git add app/build.gradle .github/workflows/build.yml
  git commit -m "Fix Robolectric SDK downloads in CI"
  git push origin jarvis/llama-gguf-integration

Assistant did not write to GitHub. A successful CI run is still required
to verify this fix.

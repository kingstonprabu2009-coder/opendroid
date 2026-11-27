# JARVIS Training Workspace

## Current state

This workspace is for cataloguing legally reusable public datasets, model checkpoints, licenses, and reproducible fine-tuning runs.

## Important infrastructure boundary

The current JARVIS Cloudflare Worker is an application/API runtime with D1 and a Workers AI inference binding. It is not a general-purpose GPU training machine. Cloudflare's published guidance says Workers AI provides inference and does not provide customers GPU access for training. Workers AI LoRA support is for running compatible adapters; do not treat it as proof that arbitrary Kimi/DeepSeek checkpoints can be trained there.

No training job has been launched. No model weights or datasets have been downloaded. No paid service/API is configured by this workspace.

## Rules

1. Record source URL, exact version/revision, license, usage restrictions, checksum, and dataset provenance before downloading or training.
2. Do not claim access to private Kimi, DeepSeek, or OpenAI training corpora. Only include datasets that the publisher actually makes available under a license permitting the intended use.
3. Keep model code, model weights, datasets, and inference runtime as separate inventory items.
4. Start with a small compatible open-weight checkpoint and a small permitted dataset only after a suitable training host and storage are verified.
5. Never upload credentials or secrets into this directory.

## Next gate

A training host with sufficient RAM/VRAM, disk, compatible drivers, and a zero-cost/no-billing guarantee must be verified before launching fine-tuning. The existing Cloudflare Worker cannot substitute for that host.

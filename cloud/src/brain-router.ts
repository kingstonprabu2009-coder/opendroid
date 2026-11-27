export type BrainTask = "general" | "reasoning" | "coding";

export const FREE_KIMI_MODEL = "openrouter:moonshotai/kimi-k2.6:free";

export interface BrainModelConfig {
  JARVIS_MODEL?: string;
  JARVIS_MODEL_GENERAL?: string;
  JARVIS_MODEL_REASONING?: string;
  JARVIS_MODEL_CODING?: string;
  JARVIS_MODEL_FALLBACK?: string;
}

export interface BrainRoute {
  task: BrainTask;
  model: string;
  fallbackModel: string;
  modelCandidates: string[];
}

// Strict $0 allowlist: Cloudflare free-allocation models plus one exact OpenRouter :free Kimi model.
const FREE_MODEL_ALLOWLIST = new Set([
  "@cf/zai-org/glm-4.7-flash",
  "@cf/google/gemma-4-26b-a4b-it",
  "@cf/openai/gpt-oss-20b",
  "@cf/qwen/qwen3.8-27b",
  "@cf/meta/llama-4-scout-17b-16e-instruct",
  FREE_KIMI_MODEL
]);

export function isFreeBrainModel(model: string): boolean {
  return FREE_MODEL_ALLOWLIST.has(model);
}

function allowedFreeModel(value: string | undefined, fallback: string): string {
  const candidate = value?.trim();
  return candidate && FREE_MODEL_ALLOWLIST.has(candidate) ? candidate : fallback;
}

/** Free-only routing. Paid, external API, and unknown model IDs are always rejected. */
export function selectBrainRoute(
  latestUserMessage: string,
  config: BrainModelConfig,
  thinkingEnabled: boolean,
  hasImages = false
): BrainRoute {
  const text = latestUserMessage.toLowerCase();
  const coding = /\b(code|coding|program|programming|debug|compile|implement|refactor|kotlin|java|python|typescript|javascript|termux|github|git|repository|source code|api|sql|gradle|apk|bug|stack trace)\b/.test(text);
  const task: BrainTask = coding ? "coding" : thinkingEnabled ? "reasoning" : "general";

  const defaults: Record<BrainTask, string> = {
    general: "@cf/zai-org/glm-4.7-flash",
    reasoning: FREE_KIMI_MODEL,
    coding: FREE_KIMI_MODEL
  };
  const configured = task === "general"
    ? config.JARVIS_MODEL_GENERAL
    : task === "reasoning"
      ? config.JARVIS_MODEL_REASONING
      : config.JARVIS_MODEL_CODING;
  const model = allowedFreeModel(config.JARVIS_MODEL, allowedFreeModel(configured, defaults[task]));
  const fallbackModel = allowedFreeModel(config.JARVIS_MODEL_FALLBACK, "@cf/google/gemma-4-26b-a4b-it");

  const specialistOrder: Record<BrainTask, string[]> = {
    general: ["@cf/zai-org/glm-4.7-flash", FREE_KIMI_MODEL, "@cf/google/gemma-4-26b-a4b-it", "@cf/qwen/qwen3.8-27b", "@cf/meta/llama-4-scout-17b-16e-instruct", "@cf/openai/gpt-oss-20b"],
    reasoning: [FREE_KIMI_MODEL, "@cf/google/gemma-4-26b-a4b-it", "@cf/qwen/qwen3.8-27b", "@cf/meta/llama-4-scout-17b-16e-instruct", "@cf/openai/gpt-oss-20b", "@cf/zai-org/glm-4.7-flash"],
    coding: [FREE_KIMI_MODEL, "@cf/openai/gpt-oss-20b", "@cf/qwen/qwen3.8-27b", "@cf/meta/llama-4-scout-17b-16e-instruct", "@cf/google/gemma-4-26b-a4b-it", "@cf/zai-org/glm-4.7-flash"]
  };

  const modelCandidates = hasImages
    ? [FREE_KIMI_MODEL, "@cf/google/gemma-4-26b-a4b-it"]
    : [...new Set([model, fallbackModel, ...specialistOrder[task]])]
        .filter(candidate => FREE_MODEL_ALLOWLIST.has(candidate));

  return { task, model: modelCandidates[0] ?? defaults[task], fallbackModel, modelCandidates };
}

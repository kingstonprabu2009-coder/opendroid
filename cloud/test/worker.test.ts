import { describe, expect, it } from "vitest";
import worker, { consumeDailyChatQuota, hashInvitationToken, hasRunnableBrainProvider, isValidInviteEmail, resolvePlatformRole, shouldEnableThinking, validateChatContent } from "../src/index";
import { FREE_KIMI_MODEL, selectBrainRoute } from "../src/brain-router";
const env = { DB: {} as D1Database, JARVIS_ENV: "test", ALLOWED_ORIGIN: "http://localhost:8080" };
describe("JARVIS daily chat quota", () => {
  it("consumes a slot when the database returns a row", async () => {
    const db = {
      prepare: () => ({
        bind: () => ({ first: async () => ({ request_count: 1 }) })
      })
    } as unknown as D1Database;
    await expect(consumeDailyChatQuota(db, "user-1", 20)).resolves.toBe(true);
  });

  it("rejects a request when the atomic quota update returns no row", async () => {
    const db = {
      prepare: () => ({
        bind: () => ({ first: async () => null })
      })
    } as unknown as D1Database;
    await expect(consumeDailyChatQuota(db, "user-1", 20)).resolves.toBe(false);
  });
});

describe("JARVIS cloud image input validation", () => {
  it("accepts bounded base64 image data URLs with text prompts", () => {
    expect(validateChatContent("user", [
      { type: "text", text: "What is in this screenshot?" },
      { type: "image_url", image_url: { url: "data:image/jpeg;base64,SGVsbG8=" } }
    ])).not.toBeNull();
  });

  it("rejects remote image URLs, malformed data URLs, and image content in assistant messages", () => {
    expect(validateChatContent("user", [
      { type: "image_url", image_url: { url: "https://example.com/image.jpg" } }
    ])).toBeNull();
    expect(validateChatContent("user", [
      { type: "image_url", image_url: { url: "data:image/jpeg;base64,not valid!" } }
    ])).toBeNull();
    expect(validateChatContent("assistant", [
      { type: "image_url", image_url: { url: "data:image/jpeg;base64,SGVsbG8=" } }
    ])).toBeNull();
  });
});

describe("JARVIS cloud provider availability", () => {
  it("requires the correct free runtime for Cloudflare and Kimi models", () => {
    expect(hasRunnableBrainProvider(["@cf/zai-org/glm-4.7-flash"], {})).toBe(false);
    expect(hasRunnableBrainProvider(["@cf/zai-org/glm-4.7-flash"], { AI: {} as Ai })).toBe(true);
    expect(hasRunnableBrainProvider([FREE_KIMI_MODEL], { AI: {} as Ai })).toBe(false);
    expect(hasRunnableBrainProvider([FREE_KIMI_MODEL], { OPENROUTER_API_KEY: "test-free-key" })).toBe(true);
    expect(hasRunnableBrainProvider(["moonshotai/kimi-k3.1-pro"], { OPENROUTER_API_KEY: "test-free-key" })).toBe(false);
  });
});

describe("JARVIS unified brain routing", () => {
  it("routes ordinary conversation to the fast general model", () => {
    expect(selectBrainRoute("Hi JARVIS", {}, false)).toMatchObject({
      task: "general", model: "@cf/zai-org/glm-4.7-flash"
    });
  });

  it("routes complex planning to the reasoning model", () => {
    expect(selectBrainRoute("Plan the workflow", {}, true)).toMatchObject({
      task: "reasoning", model: FREE_KIMI_MODEL
    });
  });

  it("routes image analysis only to vision-capable models", () => {
    expect(selectBrainRoute("Analyze this screenshot", {}, false, true)).toMatchObject({
      model: FREE_KIMI_MODEL,
      modelCandidates: [FREE_KIMI_MODEL, "@cf/google/gemma-4-26b-a4b-it"]
    });
  });

  it("routes source-code work to the coding model", () => {
    expect(selectBrainRoute("Debug this Kotlin build error", {}, true)).toMatchObject({
      task: "coding", model: FREE_KIMI_MODEL
    });
  });

  it("builds a free multi-model recovery chain without sending prompts to every model", () => {
    const route = selectBrainRoute("Debug this Kotlin build error", {}, true);
    expect(route.modelCandidates).toEqual([
      FREE_KIMI_MODEL,
      "@cf/google/gemma-4-26b-a4b-it",
      "@cf/openai/gpt-oss-20b",
      "@cf/qwen/qwen3.8-27b",
      "@cf/meta/llama-4-scout-17b-16e-instruct",
      "@cf/zai-org/glm-4.7-flash"
    ]);
    expect(new Set(route.modelCandidates).size).toBe(route.modelCandidates.length);
  });

  it("honors per-task model overrides and a shared fallback", () => {
    expect(selectBrainRoute("Write Python code", {
      JARVIS_MODEL_CODING: "@cf/qwen/qwen3.8-27b",
      JARVIS_MODEL_FALLBACK: "@cf/google/gemma-4-26b-a4b-it"
    }, false)).toMatchObject({
      task: "coding", model: "@cf/qwen/qwen3.8-27b", fallbackModel: "@cf/google/gemma-4-26b-a4b-it"
    });
  });

  it("preserves the legacy global model override when it is on the free allowlist", () => {
    expect(selectBrainRoute("Debug Kotlin", {
      JARVIS_MODEL: "@cf/openai/gpt-oss-20b"
    }, true)).toMatchObject({ model: "@cf/openai/gpt-oss-20b" });
  });

  it("rejects paid and external API model IDs even if obsolete paid flags are supplied", () => {
    for (const forbidden of [
      "moonshotai/kimi-k3.1-pro",
      "@cf/moonshotai/kimi-k2.6",
      "@cf/deepseek-ai/deepseek-v4.1-flash-1017",
      "@cf/zai-org/glm-5.3",
      "openai:gpt-6.2-astra"
    ]) {
      expect(selectBrainRoute("Plan this carefully", {
        JARVIS_MODEL_REASONING: forbidden
      }, true)).toMatchObject({ model: FREE_KIMI_MODEL });
      const route = selectBrainRoute("Hello", {
        JARVIS_MODEL: forbidden
      }, false);
      expect(route.modelCandidates.every(model => model === FREE_KIMI_MODEL || (!model.toLowerCase().includes("kimi") && !model.startsWith("openai:")))).toBe(true);
    }
  });

  it("rejects unknown provider model IDs and keeps fallback on the free allowlist", () => {
    expect(selectBrainRoute("Hello", {
      JARVIS_MODEL: "@cf/unknown/private-model",
      JARVIS_MODEL_FALLBACK: "@cf/unknown/fallback"
    }, false)).toMatchObject({
      model: "@cf/zai-org/glm-4.7-flash",
      fallbackModel: "@cf/google/gemma-4-26b-a4b-it"
    });
  });
});


describe("JARVIS adaptive reasoning mode", () => {
  it("keeps ordinary short chat in fast mode", () => {
    expect(shouldEnableThinking("", "Hi JARVIS")).toBe(false);
  });

  it("enables deeper reasoning for complex coding and analysis requests", () => {
    expect(shouldEnableThinking("", "Debug this Kotlin crash, identify the root cause, and refactor the affected code safely.")).toBe(true);
    expect(shouldEnableThinking("", "Compare these two architectures and explain the trade-offs.")).toBe(true);
  });

  it("always enables deeper reasoning for planner, critic, and merger tasks", () => {
    expect(shouldEnableThinking("JARVIS Planning Engine: return plan JSON", "Open settings")).toBe(true);
    expect(shouldEnableThinking("Safety and Security Critic", "Send this message")).toBe(true);
  });

  it("enables deeper reasoning for long user requests", () => {
    expect(shouldEnableThinking("", "x".repeat(1201))).toBe(true);
  });
});

describe("JARVIS single-owner platform roles", () => {
  it("grants Owner only to the configured verified email", () => {
    expect(resolvePlatformRole(
      { email: "OWNER@example.com", emailVerified: true },
      "owner@example.com",
      false
    )).toMatchObject({ role: "owner", isOwner: true, isAdmin: true });
  });

  it("does not grant Owner to an unverified matching account", () => {
    expect(resolvePlatformRole(
      { email: "owner@example.com", emailVerified: false },
      "owner@example.com",
      false
    )).toMatchObject({ role: "user", isOwner: false, isAdmin: false });
  });

  it("never lets a trusted Admin become another Owner", () => {
    expect(resolvePlatformRole(
      { email: "helper@example.com", emailVerified: true },
      "owner@example.com",
      true
    )).toMatchObject({ role: "admin", isOwner: false, isAdmin: true });
  });

  it("defaults to User and grants no Owner if no owner email is configured", () => {
    expect(resolvePlatformRole(
      { email: "anyone@example.com", emailVerified: true },
      "",
      false
    )).toMatchObject({ role: "user", isOwner: false, isAdmin: false });
  });
});

describe("JARVIS team invitation security", () => {
  it("validates invite email format and length", () => {
    expect(isValidInviteEmail("person@example.com")).toBe(true);
    expect(isValidInviteEmail("not-an-email")).toBe(false);
    expect(isValidInviteEmail("")).toBe(false);
    expect(isValidInviteEmail("x".repeat(250) + "@example.com")).toBe(false);
  });

  it("stores a deterministic SHA-256 digest rather than the raw invitation token", async () => {
    const digest = await hashInvitationToken("a".repeat(64));
    expect(digest).toHaveLength(64);
    expect(digest).not.toBe("a".repeat(64));
    await expect(hashInvitationToken("a".repeat(64))).resolves.toBe(digest);
  });

  it("rejects unauthenticated invitation acceptance", async () => {
    const response = await worker.fetch(new Request("https://jarvis.test/v1/team-invitations/accept", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ token: "a".repeat(64) })
    }), env);
    expect(response.status).toBe(503);
    await expect(response.json()).resolves.toMatchObject({ error: "auth_not_configured" });
  });
});

describe("JARVIS Cloud API foundation", () => {
 it("returns a non-sensitive health response", async () => {
  const response = await worker.fetch(new Request("https://jarvis.test/health"), env);
  const body = await response.json() as Record<string, unknown>;
  expect(response.status).toBe(200); expect(body.service).toBe("jarvis-cloud-api");
  expect(body.status).toBe("ok"); expect(body.ai).toBe("not_configured");
  expect(body.brain).toBe("automatic_model_routing");
  expect(response.headers.get("cache-control")).toBe("no-store");
 });
 it("keeps application routes disabled until security is ready", async () => {
  const response = await worker.fetch(new Request("https://jarvis.test/v1/chat",{method:"POST"}),env);
  expect(response.status).toBe(503);
  await expect(response.json()).resolves.toMatchObject({error:"auth_not_configured"});
 });
 it("rejects unapproved browser origins", async () => {
  const response = await worker.fetch(new Request("https://jarvis.test/health",{headers:{Origin:"https://untrusted.example"}}),env);
  expect(response.status).toBe(403);
 });
 it("returns 404 for unknown paths", async () => {
  const response = await worker.fetch(new Request("https://jarvis.test/not-a-route"),env);
  expect(response.status).toBe(404);
 });
});


describe("JARVIS authentication gateway", () => {
  it("keeps auth routes disabled until secrets and URL are configured", async () => {
    const response = await worker.fetch(new Request("https://api.example.com/api/auth/get-session"), env);
    expect(response.status).toBe(503);
    expect(await response.json()).toMatchObject({ error: "auth_not_configured" });
  });

  it("does not reflect untrusted origins on auth routes", async () => {
    const response = await worker.fetch(new Request("https://api.example.com/api/auth/get-session", {
      headers: { Origin: "https://evil.example" }
    }), env);
    expect(response.status).toBe(403);
    expect(response.headers.get("access-control-allow-origin")).toBeNull();
  });
});

describe("JARVIS team API access control", () => {
  it("does not expose team data when auth is not configured", async () => {
    const response = await worker.fetch(new Request("https://jarvis.test/v1/teams"), env);
    expect(response.status).toBe(503);
    await expect(response.json()).resolves.toMatchObject({ error: "auth_not_configured" });
  });

  it("does not accept team creation when auth is not configured", async () => {
    const response = await worker.fetch(new Request("https://jarvis.test/v1/teams", {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ name: "My team" })
    }), env);
    expect(response.status).toBe(503);
    await expect(response.json()).resolves.toMatchObject({ error: "auth_not_configured" });
  });
});

describe("JARVIS chat gateway validation", () => {
  it("requires a signed-in session before checking model availability", async () => {
    const response = await worker.fetch(new Request("https://jarvis.test/v1/chat", {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ messages: [{ role: "user", content: "Hello" }] })
    }), env);
    expect(response.status).toBe(503);
    await expect(response.json()).resolves.toMatchObject({ error: "auth_not_configured" });
  });
});

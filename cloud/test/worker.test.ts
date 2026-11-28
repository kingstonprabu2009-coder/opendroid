import { describe, expect, it } from "vitest";
import worker, { consumeDailyChatQuota, DAILY_CHAT_REQUEST_LIMIT, hashInvitationToken, hasRunnableBrainProvider, isValidInviteEmail, resolvePlatformRole, shouldEnableThinking, validateChatContent } from "../src/index";
import { brainTaskInstruction, selectBrainRoute } from "../src/brain-router";
const env = { DB: {} as D1Database, JARVIS_ENV: "test", ALLOWED_ORIGIN: "http://localhost:8080" };
describe("JARVIS daily chat quota", () => {
  it("uses a fixed ten-request daily cap that cannot be changed by Worker environment variables", () => {
    expect(DAILY_CHAT_REQUEST_LIMIT).toBe(10);
  });

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
  it("requires the JARVIS Workers AI runtime and rejects multi-model routing", () => {
    expect(hasRunnableBrainProvider(["@cf/zai-org/glm-4.7-flash"], {})).toBe(false);
    expect(hasRunnableBrainProvider(["@cf/zai-org/glm-4.7-flash"], { AI: {} as Ai })).toBe(true);
    expect(hasRunnableBrainProvider(["@cf/zai-org/glm-4.7-flash", "@cf/qwen/qwen3.8-27b"], { AI: {} as Ai })).toBe(false);
    expect(hasRunnableBrainProvider(["external:perplexity"], { AI: {} as Ai })).toBe(false);
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
      task: "reasoning", model: "@cf/qwen/qwen3.8-27b"
    });
  });

  it("routes image analysis only to vision-capable models", () => {
    expect(selectBrainRoute("Analyze this screenshot", {}, false, true)).toMatchObject({
      model: "@cf/meta/llama-4-scout-17b-16e-instruct",
      modelCandidates: ["@cf/meta/llama-4-scout-17b-16e-instruct"]
    });
  });

  it("routes research analysis to the research specialist without claiming live browsing", () => {
    const route = selectBrainRoute("Research this topic and find sources for a fact check", {}, false);
    expect(route).toMatchObject({ task: "research", model: "@cf/qwen/qwen3.8-27b" });
    expect(route.modelCandidates).toEqual(["@cf/qwen/qwen3.8-27b"]);
    expect(brainTaskInstruction("research")).toContain("do not pretend to browse the live web");
  });

  it("routes app planner requests to the automation specialist from trusted app context", () => {
    const route = selectBrainRoute("Open Settings", {}, true, false, "JARVIS Planning Engine: return plan JSON");
    expect(route).toMatchObject({ task: "automation", model: "@cf/zai-org/glm-4.7-flash" });
  });

  it("routes phone workflows to the automation specialist and keeps safety policy explicit", () => {
    const route = selectBrainRoute("Automate this workflow: open app, tap settings, set reminder", {}, false);
    expect(route).toMatchObject({ task: "automation", model: "@cf/zai-org/glm-4.7-flash" });
    expect(brainTaskInstruction("automation")).toContain("non-overridable banking/payment safety block");
  });

  it("routes source-code work to the coding model", () => {
    expect(selectBrainRoute("Debug this Kotlin build error", {}, true)).toMatchObject({
      task: "coding", model: "@cf/qwen/qwen3.8-27b"
    });
  });

  it("selects exactly one specialist model for each request", () => {
    for (const message of ["Hi JARVIS", "Plan this carefully", "Debug Kotlin", "Research this topic", "Open app and tap settings"]) {
      const route = selectBrainRoute(message, {}, true);
      expect(route.modelCandidates).toHaveLength(1);
      expect(route.modelCandidates[0]).toBe(route.model);
    }
  });

  it("honors per-task model overrides without a second-model fallback", () => {
    expect(selectBrainRoute("Write Python code", {
      JARVIS_MODEL_CODING: "@cf/qwen/qwen3.8-27b",
    }, false)).toMatchObject({
      task: "coding", model: "@cf/qwen/qwen3.8-27b", modelCandidates: ["@cf/qwen/qwen3.8-27b"]
    });
  });

  it("supports separate research and automation model overrides", () => {
    expect(selectBrainRoute("Research this topic and find sources", {
      JARVIS_MODEL_RESEARCH: "@cf/zai-org/glm-4.7-flash"
    }, false)).toMatchObject({
      task: "research", model: "@cf/zai-org/glm-4.7-flash",
      modelCandidates: ["@cf/zai-org/glm-4.7-flash"]
    });
    expect(selectBrainRoute("Automate this workflow: open app and tap settings", {
      JARVIS_MODEL_AUTOMATION: "@cf/qwen/qwen3.8-27b"
    }, false)).toMatchObject({
      task: "automation", model: "@cf/qwen/qwen3.8-27b",
      modelCandidates: ["@cf/qwen/qwen3.8-27b"]
    });
  });

  it("rejects unapproved model families from the route", () => {
    const route = selectBrainRoute("Hello", {
      JARVIS_MODEL: "@cf/google/gemma-4-26b-a4b-it",
    }, false);
    expect(route.modelCandidates).not.toContain("@cf/google/gemma-4-26b-a4b-it");
    expect(route.modelCandidates.every(model => !model.includes("google") && !model.includes("gemma"))).toBe(true);
  });

  it("accepts only the configured open-model allowlist for the global override", () => {
    expect(selectBrainRoute("Debug Kotlin", {
      JARVIS_MODEL: "@cf/qwen/qwen3.8-27b"
    }, true)).toMatchObject({ model: "@cf/qwen/qwen3.8-27b" });
  });

  it("rejects paid and external API model IDs even if obsolete paid flags are supplied", () => {
    for (const forbidden of [
      "moonshotai/kimi-k3.1-pro",
      "@cf/moonshotai/kimi-k2.6",
      "@cf/deepseek-ai/deepseek-v4.1-flash-1017",
      "@cf/zai-org/glm-5.3",
      "openai:gpt-6.2-astra",
      "@cf/openai/gpt-oss-20b"
    ]) {
      expect(selectBrainRoute("Plan this carefully", {
        JARVIS_MODEL_REASONING: forbidden
      }, true)).toMatchObject({ model: "@cf/qwen/qwen3.8-27b" });
      const route = selectBrainRoute("Hello", {
        JARVIS_MODEL: forbidden
      }, false);
      expect(route.modelCandidates).toHaveLength(1);
      expect(route.modelCandidates[0]).not.toContain("kimi");
      expect(route.modelCandidates[0]).not.toMatch(/^openai:/);
    }
  });

  it("rejects unknown model IDs and keeps one approved route", () => {
    expect(selectBrainRoute("Hello", {
      JARVIS_MODEL: "@cf/unknown/private-model",
    }, false)).toMatchObject({
      model: "@cf/zai-org/glm-4.7-flash",
      modelCandidates: ["@cf/zai-org/glm-4.7-flash"]
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

import { describe, expect, it } from "vitest";
import worker from "../src/index";
const env = { DB: {} as D1Database, JARVIS_ENV: "test", ALLOWED_ORIGIN: "http://localhost:8080" };
describe("JARVIS Cloud API foundation", () => {
 it("returns a non-sensitive health response", async () => {
  const response = await worker.fetch(new Request("https://jarvis.test/health"), env);
  const body = await response.json() as Record<string, unknown>;
  expect(response.status).toBe(200); expect(body.service).toBe("jarvis-cloud-api");
  expect(body.status).toBe("ok"); expect(body.ai).toBe("not_configured");
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

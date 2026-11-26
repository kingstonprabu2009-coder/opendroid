import { betterAuth } from "better-auth";

export interface Env {
  DB: D1Database;
  JARVIS_ENV: string;
  ALLOWED_ORIGIN: string;
  BETTER_AUTH_URL?: string;
  BETTER_AUTH_SECRET?: string;
}

const JSON_HEADERS = { "content-type": "application/json; charset=utf-8" };
function json(data: unknown, status = 200, origin?: string): Response {
  const headers = new Headers(JSON_HEADERS);
  headers.set("cache-control", "no-store");
  headers.set("x-content-type-options", "nosniff");
  if (origin) {
    headers.set("access-control-allow-origin", origin);
    headers.set("access-control-allow-credentials", "true");
    headers.set("vary", "Origin");
  }
  return new Response(JSON.stringify(data), { status, headers });
}

function withCors(response: Response, origin?: string): Response {
  if (!origin) return response;
  const headers = new Headers(response.headers);
  headers.set("access-control-allow-origin", origin);
  headers.set("access-control-allow-credentials", "true");
  headers.set("vary", "Origin");
  headers.set("cache-control", "no-store");
  headers.set("x-content-type-options", "nosniff");
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
}

function createAuth(env: Env) {
  if (!env.BETTER_AUTH_SECRET || env.BETTER_AUTH_SECRET.length < 32 || !env.BETTER_AUTH_URL) return null;
  return betterAuth({
    database: env.DB,
    secret: env.BETTER_AUTH_SECRET,
    baseURL: env.BETTER_AUTH_URL,
    trustedOrigins: [env.ALLOWED_ORIGIN],
    emailAndPassword: { enabled: true, minPasswordLength: 12, maxPasswordLength: 128 },
    session: { expiresIn: 60 * 60 * 24 * 7, updateAge: 60 * 60 * 24 },
    rateLimit: { enabled: true, storage: "database", window: 60, max: 30, customRules: {
      "/sign-in/email": { window: 60, max: 5 },
      "/sign-up/email": { window: 60, max: 3 },
    } },
    advanced: {
      ipAddress: { ipAddressHeaders: ["cf-connecting-ip"] },
      useSecureCookies: env.BETTER_AUTH_URL.startsWith("https://"),
    },
  });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const requestOrigin = request.headers.get("Origin");
    const corsOrigin = requestOrigin === env.ALLOWED_ORIGIN ? env.ALLOWED_ORIGIN : undefined;
    if (request.method === "OPTIONS") {
      if (!corsOrigin) return json({ error: "origin_not_allowed" }, 403);
      return new Response(null, { status: 204, headers: {
        "access-control-allow-origin": corsOrigin,
        "access-control-allow-credentials": "true",
        "access-control-allow-methods": "GET, POST, OPTIONS",
        "access-control-allow-headers": "authorization, content-type",
        "access-control-max-age": "600", vary: "Origin"
      }});
    }
    if (requestOrigin && !corsOrigin) return json({ error: "origin_not_allowed" }, 403);
    if (request.method === "GET" && url.pathname === "/health") {
      return json({ service: "jarvis-cloud-api", status: "ok", environment: env.JARVIS_ENV, auth: "configured_only_when_secrets_are_set", ai: "not_configured" }, 200, corsOrigin);
    }
    if (url.pathname.startsWith("/api/auth/")) {
      const auth = createAuth(env);
      if (!auth) return json({ error: "auth_not_configured", message: "Set BETTER_AUTH_SECRET (at least 32 characters) and BETTER_AUTH_URL before enabling authentication." }, 503, corsOrigin);
      return withCors(await auth.handler(request), corsOrigin);
    }
    if (url.pathname.startsWith("/v1/")) {
      return json({ error: "service_not_configured", message: "JARVIS API routes remain disabled until authorization, team isolation, quotas, and the JARVIS model gateway are implemented." }, 503, corsOrigin);
    }
    return json({ error: "not_found" }, 404, corsOrigin);
  }
};

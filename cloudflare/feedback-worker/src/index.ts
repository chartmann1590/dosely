import type { Env } from "./env";
import {
  byteLength,
  errorResponse,
  isFeedbackIssue,
  isValidIssueNumber,
  jsonResponse,
  normalizeAsset,
  normalizeComment,
  normalizeIssue,
  readJsonBody,
  asRecord,
  MAX_BASE64_BYTES,
  MAX_COMMENT_BODY_BYTES,
  MAX_ISSUE_BODY_BYTES,
  MAX_TITLE_LENGTH,
  type NormalizedComment,
  type NormalizedIssue,
} from "./github";
import { buildAssetPath, validateAssetRequest } from "./assets";
import { GitHubApiError, githubRequest } from "./githubApi";

/**
 * Cloudflare Worker: narrowly-scoped GitHub feedback API.
 *
 * Routes:
 *   GET  /health
 *   POST /api/issues
 *   GET  /api/issues/:number
 *   GET  /api/issues/:number/comments
 *   POST /api/issues/:number/comments
 *   POST /api/assets
 *
 * Security model:
 *   - The Android app is untrusted; it never talks to GitHub directly and
 *     never sees a token.
 *   - GITHUB_TOKEN is a Cloudflare secret used only here.
 *   - Routes, methods, payloads, sizes and filenames are strictly validated.
 *   - The destination GitHub repository is fixed by worker configuration;
 *     clients cannot redirect requests to another repo or host.
 *
 * GITHUB_TOKEN is checked only as truthiness; its value is never logged,
 * serialized, or returned to any client.
 */

// Hard cap on comment pagination as a safety net.
const MAX_COMMENT_PAGES = 10;

// Rough abuse-protection ceiling on decoded issue-body size.
const MAX_REQUEST_BODY_BYTES = 256 * 1024;

/** Sliding-window in-memory rate limiter (per-isolate, best effort). */
const RATE_LIMIT_MAX = 30;
const RATE_LIMIT_WINDOW_MS = 60 * 60 * 1000; // 1 hour
const rateBuckets = new Map<string, number[]>();

function isRateLimited(key: string, now: number): boolean {
  const windowStart = now - RATE_LIMIT_WINDOW_MS;
  const hits = (rateBuckets.get(key) ?? []).filter((t) => t > windowStart);
  if (hits.length >= RATE_LIMIT_MAX) return true;
  hits.push(now);
  rateBuckets.set(key, hits);
  return false;
}

function clientKey(request: Request): string {
  return (
    request.headers.get("CF-Connecting-IP") ??
    request.headers.get("X-Forwarded-For")?.split(",")[0]?.trim() ??
    "unknown"
  );
}

function githubPath(env: Env, suffix: string): string {
  return `/repos/${env.GITHUB_REPO_OWNER}/${env.GITHUB_REPO_NAME}${suffix}`;
}

function repoConfigured(env: Env): boolean {
  return (
    typeof env.GITHUB_TOKEN === "string" &&
    env.GITHUB_TOKEN.length > 0 &&
    typeof env.GITHUB_REPO_OWNER === "string" &&
    env.GITHUB_REPO_OWNER.length > 0 &&
    typeof env.GITHUB_REPO_NAME === "string" &&
    env.GITHUB_REPO_NAME.length > 0
  );
}

async function handleGetIssue(env: Env, number: number): Promise<Response> {
  try {
    const res = await githubRequest(env, githubPath(env, `/issues/${number}`), { method: "GET" });
    const raw = asRecord(await res.json()) ?? {};
    // Only expose issues this service created (never PRs or unrelated issues).
    if (!isFeedbackIssue(raw)) {
      return errorResponse("Not found.", 404);
    }
    const issue = normalizeIssue(raw);
    return jsonResponse(issue);
  } catch (error) {
    return mapGitHubError(error, "Unable to fetch issue.");
  }
}

async function handleCreateIssue(env: Env, request: Request): Promise<Response> {
  const body = await readJsonBody(request);
  if (body === null) {
    return errorResponse("Invalid request.", 400);
  }

  const title = body.title;
  const issueBody = body.body;
  if (typeof title !== "string" || title.trim().length === 0 || title.length > MAX_TITLE_LENGTH) {
    return errorResponse("Invalid request.", 400);
  }
  if (typeof issueBody !== "string" || issueBody.trim().length === 0 || byteLength(issueBody) > MAX_ISSUE_BODY_BYTES) {
    return errorResponse("Invalid request.", 400);
  }

  try {
    const res = await githubRequest(env, githubPath(env, "/issues"), {
      method: "POST",
      body: JSON.stringify({ title, body: issueBody }),
    });
    const issue = normalizeIssue(await res.json());
    return jsonResponse(issue, 201);
  } catch (error) {
    return mapGitHubError(error, "Unable to create issue.");
  }
}

async function handleGetComments(env: Env, number: number): Promise<Response> {
  try {
    const issueRes = await githubRequest(env, githubPath(env, `/issues/${number}`), { method: "GET" });
    if (!isFeedbackIssue(asRecord(await issueRes.json()) ?? {})) {
      return errorResponse("Not found.", 404);
    }

    // Page through ALL comments (GitHub default page is 30) so older replies
    // are never hidden and newly posted comments always appear after refresh.
    const perPage = 100;
    let page = 1;
    const comments: NormalizedComment[] = [];
    for (;;) {
      const res = await githubRequest(
        env,
        githubPath(env, `/issues/${number}/comments?per_page=${perPage}&page=${page}`),
        { method: "GET" },
      );
      const raw = (await res.json()) as unknown[];
      for (const c of raw) comments.push(normalizeComment(asRecord(c) ?? {}));
      if (raw.length < perPage) break;
      page += 1;
      if (page > MAX_COMMENT_PAGES) break; // hard safety cap
    }
    return jsonResponse(comments);
  } catch (error) {
    return mapGitHubError(error, "Unable to fetch comments.");
  }
}

async function handlePostComment(env: Env, number: number, request: Request): Promise<Response> {
  const body = await readJsonBody(request);
  if (body === null) {
    return errorResponse("Invalid request.", 400);
  }
  const commentBody = body.body;
  if (typeof commentBody !== "string" || commentBody.trim().length === 0 || byteLength(commentBody) > MAX_COMMENT_BODY_BYTES) {
    return errorResponse("Invalid request.", 400);
  }

  try {
    // Restrict writes to issues this service created. Without this check any
    // internet client could post as the token owner on unrelated issues/PRs.
    const issueRes = await githubRequest(env, githubPath(env, `/issues/${number}`), { method: "GET" });
    if (!isFeedbackIssue(asRecord(await issueRes.json()) ?? {})) {
      return errorResponse("Not found.", 404);
    }

    const res = await githubRequest(env, githubPath(env, `/issues/${number}/comments`), {
      method: "POST",
      body: JSON.stringify({ body: commentBody }),
    });
    const comment = normalizeComment(await res.json());
    return jsonResponse(comment, 201);
  } catch (error) {
    return mapGitHubError(error, "Unable to post comment.");
  }
}

async function handleUploadAsset(env: Env, request: Request): Promise<Response> {
  const body = await readJsonBody(request);
  if (body === null) {
    return errorResponse("Invalid request.", 400);
  }

  const parsed = validateAssetRequest(body.fileName, body.contentBase64);
  if (parsed === null) {
    return errorResponse("Invalid request.", 400);
  }
  const { safeFilename, contentBase64 } = {
    safeFilename: parsed.safeFilename,
    contentBase64: body.contentBase64 as string,
  };
  if (byteLength(contentBase64) > MAX_BASE64_BYTES) {
    return errorResponse("Invalid request.", 413);
  }

  const path = buildAssetPath(env, safeFilename);
  try {
    const res = await githubRequest(env, `/repos/${env.GITHUB_REPO_OWNER}/${env.GITHUB_REPO_NAME}/contents/${path}`, {
      method: "PUT",
      body: JSON.stringify({
        message: `Upload feedback attachment: ${safeFilename}`,
        content: contentBase64,
      }),
    });
    const asset = normalizeAsset(await res.json());
    return jsonResponse(asset, 201);
  } catch (error) {
    return mapGitHubError(error, "Unable to upload attachment.");
  }
}

function mapGitHubError(error: unknown, fallback: string): Response {
  if (error instanceof GitHubApiError) {
    const status = error.status >= 500 ? 502 : error.status;
    return errorResponse(fallback, status);
  }
  return errorResponse(fallback, 500);
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";
    const method = request.method;

    if (path === "/health") {
      if (method !== "GET") return errorResponse("Method not allowed.", 405);
      return jsonResponse({
        ok: true,
        service: "feedback-api",
        githubRepositoryConfigured: repoConfigured(env),
      });
    }

    if (!path.startsWith("/api/")) {
      return errorResponse("Not found.", 404);
    }

    if (!repoConfigured(env)) {
      return errorResponse("Feedback service is not configured.", 503);
    }

    // Best-effort rate limiting on state-changing routes.
    if (method === "POST") {
      if (isRateLimited(clientKey(request), Date.now())) {
        return errorResponse("Too many requests.", 429);
      }
    }

    if (path === "/api/issues") {
      if (method !== "POST") return errorResponse("Method not allowed.", 405);
      return handleCreateIssue(env, request);
    }

    const issueMatch = /^\/api\/issues\/(\d+)$/.exec(path);
    if (issueMatch !== null) {
      const number = Number(issueMatch[1]);
      if (!isValidIssueNumber(number)) return errorResponse("Invalid request.", 400);
      if (method === "GET") return handleGetIssue(env, number);
      return errorResponse("Method not allowed.", 405);
    }

    const commentsMatch = /^\/api\/issues\/(\d+)\/comments$/.exec(path);
    if (commentsMatch !== null) {
      const number = Number(commentsMatch[1]);
      if (!isValidIssueNumber(number)) return errorResponse("Invalid request.", 400);
      if (method === "GET") return handleGetComments(env, number);
      if (method === "POST") return handlePostComment(env, number, request);
      return errorResponse("Method not allowed.", 405);
    }

    if (path === "/api/assets") {
      if (method !== "POST") return errorResponse("Method not allowed.", 405);
      return handleUploadAsset(env, request);
    }

    return errorResponse("Not found.", 404);
  },
} satisfies ExportedHandler<Env>;

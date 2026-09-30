import type { Env } from "./env";
import {
  byteLength,
  errorResponse,
  isFeedbackIssue,
  isValidIssueNumber,
  isValidIdempotencyKey,
  isRecentComment,
  isRecentIssue,
  jsonResponse,
  normalizeAsset,
  normalizeComment,
  normalizeFeedbackTitle,
  normalizeIssue,
  readJsonBody,
  asRecord,
  FEEDBACK_IDEMPOTENCY_HEADER,
  FEEDBACK_TITLE_MARKER,
  MAX_ASSET_BODY_BYTES,
  MAX_BASE64_BYTES,
  MAX_COMMENT_BODY_BYTES,
  MAX_ISSUE_BODY_BYTES,
  MAX_JSON_BODY_BYTES,
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
 *   - When FEEDBACK_WORKER_API_KEY is set, every /api request must carry it
 *     (X-Api-Key); /health stays open for uptime checks. The key is the only
 *     shared capability embedded in the app and can be rotated without ever
 *     touching the GitHub token.
 *
 * GITHUB_TOKEN is checked only as truthiness; its value is never logged,
 * serialized, or returned to any client.
 */

// Comment pagination: hard caps on both pages and total comments, so a
// malicious huge issue can never drive unbounded authenticated GitHub calls
// or let a single response balloon (each normalized comment is ~a few KB).
const MAX_COMMENT_PAGES = 10;
const MAX_TOTAL_COMMENTS = 1000;
const COMMENTS_PER_PAGE = 100;

/** Sliding-window in-memory rate limiter (per-isolate, best effort). */
const RATE_LIMIT_MAX = 30;
const RATE_LIMIT_WINDOW_MS = 60 * 60 * 1000; // 1 hour
const rateBuckets = new Map<string, number[]>();

/**
 * Read routes are cheap for the client but expensive for the token: each
 * GET fans out to 1..MAX_COMMENT_PAGES authenticated GitHub calls. A lower
 * per-IP cap on reads stops a client from draining the token's GitHub API
 * quota by hammering the comments endpoint.
 */
const READ_RATE_LIMIT_MAX = 60;
const READ_RATE_LIMIT_WINDOW_MS = 60 * 60 * 1000; // 1 hour
const readRateBuckets = new Map<string, number[]>();

function isRateLimited(key: string, now: number): boolean {
  const windowStart = now - RATE_LIMIT_WINDOW_MS;
  const hits = (rateBuckets.get(key) ?? []).filter((t) => t > windowStart);
  if (hits.length >= RATE_LIMIT_MAX) return true;
  hits.push(now);
  rateBuckets.set(key, hits);
  return false;
}

function isReadRateLimited(key: string, now: number): boolean {
  const windowStart = now - READ_RATE_LIMIT_WINDOW_MS;
  const hits = (readRateBuckets.get(key) ?? []).filter((t) => t > windowStart);
  if (hits.length >= READ_RATE_LIMIT_MAX) return true;
  hits.push(now);
  readRateBuckets.set(key, hits);
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

/**
 * Shared API-key gate (defense in depth beyond rate limiting).
 *
 * When FEEDBACK_WORKER_API_KEY is configured, every /api request must present
 * it in the X-Api-Key header; /health intentionally stays open for uptime
 * checks. The comparison runs over SHA-256 digests so the secret's length and
 * prefix never influence timing; the value itself is never logged or echoed.
 * An unset key means the gate is disabled (local development), falling back to
 * rate limits + strict payload validation.
 */
async function apiKeyValid(request: Request, env: Env): Promise<boolean> {
  const configured = env.FEEDBACK_WORKER_API_KEY;
  if (typeof configured !== "string" || configured.length === 0) return true;
  const provided = request.headers.get("X-Api-Key") ?? "";
  if (provided.length === 0) return false;
  const [configuredDigest, providedDigest] = await Promise.all([
    crypto.subtle.digest("SHA-256", new TextEncoder().encode(configured)),
    crypto.subtle.digest("SHA-256", new TextEncoder().encode(provided)),
  ]);
  const a = new Uint8Array(configuredDigest);
  const b = new Uint8Array(providedDigest);
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

/**
 * Lists recent issues in the feedback marker namespace (newest first), bounded
 * to a few pages. Used by the idempotency scan: a client retrying a failed
 * submission does so within minutes, so a bounded recent window is enough to
 * find the first (already-created) issue without walking the whole tracker.
 */
async function fetchRecentFeedbackIssues(env: Env): Promise<NormalizedIssue[]> {
  const results: NormalizedIssue[] = [];
  for (let page = 1; page <= 3; page++) {
    const res = await githubRequest(
      env,
      githubPath(env, `/issues?state=all&sort=created&direction=desc&per_page=50&page=${page}`),
      { method: "GET" },
    );
    const raw = (await res.json()) as unknown[];
    for (const item of raw) {
      const issue = asRecord(item);
      if (issue === null) continue;
      // Only marker-namespace issues participate in dedupe (isFeedbackIssue
      // also excludes PRs); this is what bounds the work on busy trackers.
      if (!isFeedbackIssue(issue)) continue;
      if (!isRecentIssue(issue, Date.now())) continue;
      results.push(normalizeIssue(issue));
    }
    if (raw.length < 50) break;
  }
  return results;
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
  const parsed = await readJsonBody(request, MAX_JSON_BODY_BYTES);
  if (!parsed.ok) {
    return errorResponse(parsed.status === 413 ? "Request too large." : "Invalid request.", parsed.status);
  }
  const body = parsed.body;

  const title = body.title;
  const issueBody = body.body;
  if (typeof title !== "string" || title.trim().length === 0 || title.length > MAX_TITLE_LENGTH) {
    return errorResponse("Invalid request.", 400);
  }
  if (typeof issueBody !== "string" || issueBody.trim().length === 0 || byteLength(issueBody) > MAX_ISSUE_BODY_BYTES) {
    return errorResponse("Invalid request.", 400);
  }

  // Idempotency (best effort): the client generates a UUID per report attempt
  // and reuses it across retries. When present and well-formed, any feedback
  // issue created with the same embedded key inside the recent window makes
  // this a duplicate — the existing issue is returned (409) instead of
  // creating a second one. Keys are never stored outside the issue body, so
  // no worker-side state exists to leak, prune, or migrate.
  const idempotencyKeyHeader = request.headers.get(FEEDBACK_IDEMPOTENCY_HEADER);
  const hasIdempotencyKey = isValidIdempotencyKey(idempotencyKeyHeader);
  const idempotencyKey = hasIdempotencyKey ? (idempotencyKeyHeader as string) : null;
  const finalTitle = normalizeFeedbackTitle(title);
  const finalBody = idempotencyKey
    ? `${issueBody}\n\n<!-- feedback-key: ${idempotencyKey} -->`
    : issueBody;

  try {
    if (idempotencyKey !== null) {
      const keyMarker = `<!-- feedback-key: ${idempotencyKey} -->`;
      const recent = await fetchRecentFeedbackIssues(env);
      const duplicate = recent.find((issue) => issue.body?.includes(keyMarker));
      if (duplicate !== undefined) {
        return jsonResponse(
          { error: "An identical report was already submitted.", existingIssue: duplicate },
          409,
        );
      }
    }

    const res = await githubRequest(env, githubPath(env, "/issues"), {
      method: "POST",
      body: JSON.stringify({ title: finalTitle, body: finalBody }),
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

    // Page through comments up to hard caps on BOTH pages and total count.
    // The page cap alone is not a real bound: an issue serving 100 comments
    // per page would keep issuing authenticated GitHub calls until the page
    // cap, and a repeated caller could drain the token's API quota. Stopping
    // at MAX_TOTAL_COMMENTS bounds both the fan-out and the response size.
    let page = 1;
    const comments: NormalizedComment[] = [];
    let truncated = false;
    for (;;) {
      const res = await githubRequest(
        env,
        githubPath(env, `/issues/${number}/comments?per_page=${COMMENTS_PER_PAGE}&page=${page}`),
        { method: "GET" },
      );
      const raw = (await res.json()) as unknown[];
      for (const c of raw) {
        comments.push(normalizeComment(asRecord(c) ?? {}));
        if (comments.length >= MAX_TOTAL_COMMENTS) {
          truncated = true;
          break;
        }
      }
      if (truncated || raw.length < COMMENTS_PER_PAGE) break;
      page += 1;
      if (page > MAX_COMMENT_PAGES) break; // 10 pages x 100 = hard ceiling
    }
    return jsonResponse(comments);
  } catch (error) {
    return mapGitHubError(error, "Unable to fetch comments.");
  }
}

async function handlePostComment(env: Env, number: number, request: Request): Promise<Response> {
  const parsed = await readJsonBody(request, MAX_JSON_BODY_BYTES);
  if (!parsed.ok) {
    return errorResponse(parsed.status === 413 ? "Request too large." : "Invalid request.", parsed.status);
  }
  const body = parsed.body;
  const commentBody = body.body;
  if (typeof commentBody !== "string" || commentBody.trim().length === 0 || byteLength(commentBody) > MAX_COMMENT_BODY_BYTES) {
    return errorResponse("Invalid request.", 400);
  }

  // Idempotency for replies: the client reuses the UUID it generated for the
  // original attempt, so a retry after an ambiguous failure (timeout, 502,
  // dropped connection) can never double-post the same comment.
  const idempotencyKeyHeader = request.headers.get(FEEDBACK_IDEMPOTENCY_HEADER);
  const hasIdempotencyKey = isValidIdempotencyKey(idempotencyKeyHeader);
  const idempotencyKey = hasIdempotencyKey ? (idempotencyKeyHeader as string) : null;
  const finalBody = idempotencyKey
    ? `${commentBody}\n\n<!-- feedback-key: ${idempotencyKey} -->`
    : commentBody;

  try {
    // Restrict writes to issues this service created. Without this check any
    // internet client could post as the token owner on unrelated issues/PRs.
    const issueRes = await githubRequest(env, githubPath(env, `/issues/${number}`), { method: "GET" });
    if (!isFeedbackIssue(asRecord(await issueRes.json()) ?? {})) {
      return errorResponse("Not found.", 404);
    }

    if (idempotencyKey !== null) {
      const keyMarker = `<!-- feedback-key: ${idempotencyKey} -->`;
      let page = 1;
      for (;;) {
        const listRes = await githubRequest(
          env,
          githubPath(env, `/issues/${number}/comments?per_page=50&page=${page}`),
          { method: "GET" },
        );
        const raw = (await listRes.json()) as unknown[];
        const duplicate = raw.find((c) => {
          const record = asRecord(c);
          return (
            record !== null &&
            isRecentComment(record, Date.now()) &&
            typeof record.body === "string" &&
            record.body.includes(keyMarker)
          );
        });
        if (duplicate !== undefined) {
          return jsonResponse(normalizeComment(asRecord(duplicate) ?? {}), 200);
        }
        if (raw.length < 50 || page >= 5) break;
        page += 1;
      }
    }

    const res = await githubRequest(env, githubPath(env, `/issues/${number}/comments`), {
      method: "POST",
      body: JSON.stringify({ body: finalBody }),
    });
    const comment = normalizeComment(await res.json());
    return jsonResponse(comment, 201);
  } catch (error) {
    return mapGitHubError(error, "Unable to post comment.");
  }
}

async function handleUploadAsset(env: Env, request: Request): Promise<Response> {
  const parsed = await readJsonBody(request, MAX_ASSET_BODY_BYTES);
  if (!parsed.ok) {
    return errorResponse(parsed.status === 413 ? "Request too large." : "Invalid request.", parsed.status);
  }
  const body = parsed.body;

  const asset = await validateAssetRequest(body.fileName, body.contentBase64);
  if (asset === null) {
    return errorResponse("Invalid request.", 400);
  }
  const safeFilename = asset.safeFilename;
  const contentBase64 = body.contentBase64 as string;
  if (byteLength(contentBase64) > MAX_BASE64_BYTES) {
    return errorResponse("Invalid request.", 413);
  }

  const path = buildAssetPath(env, safeFilename);
  let committedSha: string | null = null;
  try {
    const res = await githubRequest(env, `/repos/${env.GITHUB_REPO_OWNER}/${env.GITHUB_REPO_NAME}/contents/${path}`, {
      method: "PUT",
      body: JSON.stringify({
        message: `Upload feedback attachment: ${safeFilename}`,
        content: contentBase64,
      }),
    });
    const raw = asRecord(await res.json());
    const content = asRecord(raw?.content ?? null);
    const committedShaValue = content?.sha;
    if (typeof committedShaValue === "string") committedSha = committedShaValue;
    // Parse the response body only once — request.json() (and res.json())
    // cannot be consumed twice.
    const asset = normalizeAsset({ content: raw?.content ?? null });
    return jsonResponse(asset, 201);
  } catch (error) {
    // Compensation: never leave an unreferenced blob in repository history.
    if (committedSha !== null) {
      await deleteCommittedAsset(env, path, committedSha);
    }
    return mapGitHubError(error, "Unable to upload attachment.");
  }
}

/** Deletes a committed asset blob (best effort, used for compensation). */
async function deleteCommittedAsset(env: Env, path: string, sha: string): Promise<void> {
  try {
    await githubRequest(env, `/repos/${env.GITHUB_REPO_OWNER}/${env.GITHUB_REPO_NAME}/contents/${path}`, {
      method: "DELETE",
      body: JSON.stringify({
        message: `Remove unreferenced feedback attachment: ${path.split("/").pop()}`,
        sha,
      }),
    });
  } catch {
    // Best effort only; the response to the failed operation is unaffected.
  }
}

/**
 * Creates a feedback issue and uploads its attachment as one logical
 * operation: the image is only committed after the issue exists, and any
 * downstream failure removes the orphaned asset.
 */
async function handleCreateIssueWithAsset(env: Env, request: Request): Promise<Response> {
  const parsed = await readJsonBody(request, MAX_ASSET_BODY_BYTES);
  if (!parsed.ok) {
    return errorResponse(parsed.status === 413 ? "Request too large." : "Invalid request.", parsed.status);
  }
  const body = parsed.body;

  const title = body.title;
  const issueBody = body.body;
  if (typeof title !== "string" || title.trim().length === 0 || title.length > MAX_TITLE_LENGTH) {
    return errorResponse("Invalid request.", 400);
  }
  if (typeof issueBody !== "string" || issueBody.trim().length === 0 || byteLength(issueBody) > MAX_ISSUE_BODY_BYTES) {
    return errorResponse("Invalid request.", 400);
  }

  const attachment = typeof body.attachmentFileName === "string" && typeof body.attachmentContentBase64 === "string"
    ? await validateAssetRequest(body.attachmentFileName, body.attachmentContentBase64)
    : null;
  if (body.attachmentFileName !== undefined && attachment === null) {
    return errorResponse("Invalid request.", 400);
  }

  // Idempotency: same contract as handleCreateIssue — the client-generated
  // UUID is embedded in the issue body and checked against recently created
  // feedback issues before a second issue is made.
  const idempotencyKeyHeader = request.headers.get(FEEDBACK_IDEMPOTENCY_HEADER);
  const hasIdempotencyKey = isValidIdempotencyKey(idempotencyKeyHeader);
  const idempotencyKey = hasIdempotencyKey ? (idempotencyKeyHeader as string) : null;
  const finalTitle = normalizeFeedbackTitle(title);
  const finalBody = idempotencyKey
    ? `${issueBody}\n\n<!-- feedback-key: ${idempotencyKey} -->`
    : issueBody;

  const path = attachment !== null ? buildAssetPath(env, attachment.safeFilename) : null;
  let committedSha: string | null = null;
  try {
    if (idempotencyKey !== null) {
      const keyMarker = `<!-- feedback-key: ${idempotencyKey} -->`;
      const recent = await fetchRecentFeedbackIssues(env);
      const duplicate = recent.find((issue) => issue.body?.includes(keyMarker));
      if (duplicate !== undefined) {
        return jsonResponse(
          { error: "An identical report was already submitted.", existingIssue: duplicate },
          409,
        );
      }
    }

    // The issue references the asset, so the issue is created FIRST. The
    // attachment markdown is appended afterwards via PATCH on success.
    const res = await githubRequest(env, githubPath(env, "/issues"), {
      method: "POST",
      body: JSON.stringify({ title: finalTitle, body: finalBody }),
    });
    const rawIssue = asRecord(await res.json());
    const issue = normalizeIssue(rawIssue ?? {});

    if (attachment !== null && path !== null) {
      try {
        const uploadRes = await githubRequest(
          env,
          `/repos/${env.GITHUB_REPO_OWNER}/${env.GITHUB_REPO_NAME}/contents/${path}`,
          {
            method: "PUT",
            body: JSON.stringify({
              message: `Upload feedback attachment: ${attachment.safeFilename}`,
              content: body.attachmentContentBase64 as string,
            }),
          },
        );
        const rawContent = asRecord(await uploadRes.json());
        const content = asRecord(rawContent?.content ?? null);
        if (typeof content?.sha === "string") committedSha = content.sha;
        const meta = normalizeAsset({ content: rawContent ?? null });

        const url =
          meta.downloadUrl ??
          `https://raw.githubusercontent.com/${env.GITHUB_REPO_OWNER}/${env.GITHUB_REPO_NAME}/HEAD/${path}`;
        // finalBody (not issueBody) keeps the idempotency marker in the body
        // so retries after this point still dedupe against the same issue.
        const updatedBody = `${finalBody}\n\n## Attachment\n\n![Screenshot](${url})`;
        await githubRequest(env, githubPath(env, `/issues/${issue.number}`), {
          method: "PATCH",
          body: JSON.stringify({ body: updatedBody }),
        });
      } catch {
        // The issue itself exists — losing it over an attachment problem would
        // make the client's retry create a duplicate issue. Delete any
        // committed image and report success with attachmentFailed so callers
        // know the report content is safe but the image is not attached.
        if (committedSha !== null) {
          await deleteCommittedAsset(env, path, committedSha);
        }
        return jsonResponse({ ...issue, attachmentFailed: true }, 201);
      }
    }
    return jsonResponse(issue, 201);
  } catch (error) {
    // Only reached before the issue exists (validation of the issue call
    // itself): nothing attachment-related to compensate yet beyond a blob
    // that somehow committed, which cannot happen ahead of the PUT.
    return mapGitHubError(error, "Unable to create issue.");
  }
}

/**
 * Posts a comment and uploads its attachment as one logical operation,
 * with the same issue-guard and orphan-compensation rules as the other
 * feedback operations.
 */
async function handlePostCommentWithAsset(env: Env, number: number, request: Request): Promise<Response> {
  const parsed = await readJsonBody(request, MAX_ASSET_BODY_BYTES);
  if (!parsed.ok) {
    return errorResponse(parsed.status === 413 ? "Request too large." : "Invalid request.", parsed.status);
  }
  const body = parsed.body;

  const commentBody = body.body;
  if (typeof commentBody !== "string" || commentBody.trim().length === 0 || byteLength(commentBody) > MAX_COMMENT_BODY_BYTES) {
    return errorResponse("Invalid request.", 400);
  }

  const attachment = typeof body.attachmentFileName === "string" && typeof body.attachmentContentBase64 === "string"
    ? await validateAssetRequest(body.attachmentFileName, body.attachmentContentBase64)
    : null;
  if (body.attachmentFileName !== undefined && attachment === null) {
    return errorResponse("Invalid request.", 400);
  }

  // Idempotency for replies: same contract as handlePostComment — the client
  // reuses the UUID from the original attempt, so retries after ambiguous
  // failures can never double-post the same reply.
  const idempotencyKeyHeader = request.headers.get(FEEDBACK_IDEMPOTENCY_HEADER);
  const hasIdempotencyKey = isValidIdempotencyKey(idempotencyKeyHeader);
  const idempotencyKey = hasIdempotencyKey ? (idempotencyKeyHeader as string) : null;
  const finalBodyWithKey = idempotencyKey
    ? `${commentBody}\n\n<!-- feedback-key: ${idempotencyKey} -->`
    : commentBody;

  const path = attachment !== null ? buildAssetPath(env, attachment.safeFilename) : null;
  let committedSha: string | null = null;
  try {
    // Restrict writes to issues this service created.
    const issueRes = await githubRequest(env, githubPath(env, `/issues/${number}`), { method: "GET" });
    if (!isFeedbackIssue(asRecord(await issueRes.json()) ?? {})) {
      return errorResponse("Not found.", 404);
    }

    if (idempotencyKey !== null) {
      const keyMarker = `<!-- feedback-key: ${idempotencyKey} -->`;
      let page = 1;
      for (;;) {
        const listRes = await githubRequest(
          env,
          githubPath(env, `/issues/${number}/comments?per_page=50&page=${page}`),
          { method: "GET" },
        );
        const raw = (await listRes.json()) as unknown[];
        const duplicate = raw.find((c) => {
          const record = asRecord(c);
          return (
            record !== null &&
            isRecentComment(record, Date.now()) &&
            typeof record.body === "string" &&
            record.body.includes(keyMarker)
          );
        });
        if (duplicate !== undefined) {
          return jsonResponse(normalizeComment(asRecord(duplicate) ?? {}), 200);
        }
        if (raw.length < 50 || page >= 5) break;
        page += 1;
      }
    }

    const res = await githubRequest(env, githubPath(env, `/issues/${number}/comments`), {
      method: "POST",
      body: JSON.stringify({ body: finalBodyWithKey }),
    });
    const comment = normalizeComment(await res.json());

    if (attachment !== null && path !== null) {
      try {
        const uploadRes = await githubRequest(
          env,
          `/repos/${env.GITHUB_REPO_OWNER}/${env.GITHUB_REPO_NAME}/contents/${path}`,
          {
            method: "PUT",
            body: JSON.stringify({
              message: `Upload feedback attachment: ${attachment.safeFilename}`,
              content: body.attachmentContentBase64 as string,
            }),
          },
        );
        const rawContent = asRecord(await uploadRes.json());
        const content = asRecord(rawContent?.content ?? null);
        if (typeof content?.sha === "string") committedSha = content.sha;
        const meta = normalizeAsset({ content: rawContent ?? null });

        const url =
          meta.downloadUrl ??
          `https://raw.githubusercontent.com/${env.GITHUB_REPO_OWNER}/${env.GITHUB_REPO_NAME}/HEAD/${path}`;
        // finalBodyWithKey (not commentBody) keeps the idempotency marker so
        // retries after this point still dedupe against the same comment.
        const updatedBody = `${finalBodyWithKey}\n\n## Attachment\n\n![Screenshot](${url})`;
        // Updating a comment uses the global issue-comments endpoint.
        await githubRequest(env, githubPath(env, `/issues/comments/${comment.id}`), {
          method: "PATCH",
          body: JSON.stringify({ body: updatedBody }),
        });
      } catch {
        // The comment exists — same soft-success contract as issue creation.
        if (committedSha !== null) {
          await deleteCommittedAsset(env, path, committedSha);
        }
        return jsonResponse({ ...comment, attachmentFailed: true }, 201);
      }
    }
    return jsonResponse(comment, 201);
  } catch (error) {
    return mapGitHubError(error, "Unable to post comment.");
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

    // Defense in depth: an optional shared API key required on every /api
    // route when configured (never on /health). Checked before rate limiting
    // so unauthenticated hammering is rejected without touching bucket state.
    if (!(await apiKeyValid(request, env))) {
      return errorResponse("Unauthorized.", 401);
    }

    // Best-effort rate limiting: stricter per-IP budget on reads (each
    // comments GET can fan out to multiple authenticated GitHub calls) and
    // the existing budget on writes.
    if (method === "GET") {
      if (isReadRateLimited(clientKey(request), Date.now())) {
        return errorResponse("Too many requests.", 429);
      }
    }
    if (method === "POST") {
      if (isRateLimited(clientKey(request), Date.now())) {
        return errorResponse("Too many requests.", 429);
      }
    }

    if (path === "/api/issues") {
      if (method !== "POST") return errorResponse("Method not allowed.", 405);
      return handleCreateIssue(env, request);
    }

    // Issue creation with an inline attachment: one logical operation, so a
    // failed issue creation can never orphan an uploaded screenshot.
    if (path === "/api/issues-with-asset") {
      if (method !== "POST") return errorResponse("Method not allowed.", 405);
      return handleCreateIssueWithAsset(env, request);
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

    // Comment posting with an inline attachment: one logical operation with
    // the same feedback-issue guard and orphan-compensation rules.
    const commentWithAssetMatch = /^\/api\/issues\/(\d+)\/comments-with-asset$/.exec(path);
    if (commentWithAssetMatch !== null) {
      const number = Number(commentWithAssetMatch[1]);
      if (!isValidIssueNumber(number)) return errorResponse("Invalid request.", 400);
      if (method !== "POST") return errorResponse("Method not allowed.", 405);
      return handlePostCommentWithAsset(env, number, request);
    }

    if (path === "/api/assets") {
      if (method !== "POST") return errorResponse("Method not allowed.", 405);
      return handleUploadAsset(env, request);
    }

    return errorResponse("Not found.", 404);
  },
} satisfies ExportedHandler<Env>;

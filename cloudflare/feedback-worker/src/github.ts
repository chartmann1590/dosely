/**
 * Shared helpers for the feedback worker: normalized response shapes, input
 * validation, and HTTP utilities.
 *
 * Everything returned to the Android client passes through the normalizers so
 * raw GitHub payloads never leak extra fields, and every request is validated
 * before it reaches the GitHub API (see githubApi.ts).
 */

// ---------------------------------------------------------------------------
// Normalized response shapes
// ---------------------------------------------------------------------------

export interface NormalizedIssue {
  number: number;
  title: string;
  state: "open" | "closed";
  htmlUrl: string;
  createdAt: string;
  body?: string;
}

export interface NormalizedComment {
  id: number;
  body: string;
  createdAt: string;
  user: { login: string };
}

export interface NormalizedAsset {
  downloadUrl: string | null;
  htmlUrl: string | null;
}

// ---------------------------------------------------------------------------
// Normalizers (GitHub -> app)
// ---------------------------------------------------------------------------

interface RawIssue {
  number?: unknown;
  title?: unknown;
  state?: unknown;
  html_url?: unknown;
  created_at?: unknown;
  body?: unknown;
}

interface RawComment {
  id?: unknown;
  body?: unknown;
  created_at?: unknown;
  user?: { login?: unknown } | null;
}

export function normalizeIssue(raw: RawIssue): NormalizedIssue {
  return {
    number: typeof raw.number === "number" ? raw.number : 0,
    title: typeof raw.title === "string" ? raw.title : "",
    state: raw.state === "closed" ? "closed" : "open",
    htmlUrl: typeof raw.html_url === "string" ? raw.html_url : "",
    createdAt: typeof raw.created_at === "string" ? raw.created_at : "",
    ...(typeof raw.body === "string" && raw.body.length > 0 ? { body: raw.body } : {}),
  };
}

export function normalizeComment(raw: RawComment): NormalizedComment {
  return {
    id: typeof raw.id === "number" ? raw.id : 0,
    body: typeof raw.body === "string" ? raw.body : "",
    createdAt: typeof raw.created_at === "string" ? raw.created_at : "",
    user: {
      login: typeof raw.user?.login === "string" ? raw.user.login : "unknown",
    },
  };
}

interface RawContentResponse {
  content?: { download_url?: unknown; html_url?: unknown } | null;
}

export function normalizeAsset(raw: RawContentResponse): NormalizedAsset {
  const downloadUrl =
    typeof raw.content?.download_url === "string" ? raw.content.download_url : null;
  const htmlUrl = typeof raw.content?.html_url === "string" ? raw.content.html_url : null;
  return { downloadUrl, htmlUrl };
}

// ---------------------------------------------------------------------------
// Feedback-issue guard
// ---------------------------------------------------------------------------

/**
 * Issues created by this feedback service carry this title-marker namespace:
 * the Android client sends "[Feedback] <user title>" and integration tests use
 * "[Feedback Test] ...". We use the marker as a stateless guard so reads and
 * comment writes are restricted to issues the feedback service itself created
 * — the worker must never act on unrelated repository issues or pull requests
 * (PRs are always excluded via the pull_request field).
 */
export const FEEDBACK_TITLE_MARKER = "[Feedback";

/** True when a raw GitHub issue was demonstrably created by the feedback service. */
export function isFeedbackIssue(raw: {
  title?: unknown;
  pull_request?: unknown;
}): boolean {
  if (raw.pull_request !== undefined && raw.pull_request !== null) return false;
  return typeof raw.title === "string" && raw.title.startsWith(FEEDBACK_TITLE_MARKER);
}

// ---------------------------------------------------------------------------
// Request validation
// ---------------------------------------------------------------------------

export const MAX_TITLE_LENGTH = 200;
export const MAX_ISSUE_BODY_BYTES = 50 * 1024; // 50 KB
export const MAX_COMMENT_BODY_BYTES = 25 * 1024; // 25 KB
export const MAX_BASE64_BYTES = 11 * 1024 * 1024; // ~8 MB decoded

export function asRecord(body: unknown): Record<string, unknown> | null {
  return body !== null && typeof body === "object" && !Array.isArray(body)
    ? (body as Record<string, unknown>)
    : null;
}

export function isValidIssueNumber(n: unknown): n is number {
  return typeof n === "number" && Number.isInteger(n) && n > 0 && n <= 10_000_000;
}

export function isNonEmptyString(value: unknown, maxBytes: number): value is string {
  return typeof value === "string" && value.trim().length > 0 && byteLength(value) <= maxBytes;
}

export function byteLength(s: string): number {
  return new TextEncoder().encode(s).length;
}

export function isValidBase64(s: unknown): s is string {
  return typeof s === "string" && s.length > 0 && s.length % 4 === 0 && /^[A-Za-z0-9+/]+={0,2}$/.test(s);
}

/**
 * Sanitizes a client-supplied filename. The app must never be able to choose
 * the GitHub directory: the result is a bare filename with no path separators.
 */
export function sanitizeFilename(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  const base = raw.split(/[\\/]/).pop() ?? "";
  const cleaned = base
    .replace(/\.\.+/g, "")
    .replace(/[^A-Za-z0-9._-]/g, "_")
    .replace(/_{2,}/g, "_")
    .replace(/^_+|_+$/g, "");
  if (cleaned.length === 0 || cleaned.startsWith(".")) return null;
  return cleaned.length > 120 ? cleaned.slice(0, 120) : cleaned;
}

const SUPPORTED_EXTENSIONS = new Set(["png", "jpg", "jpeg", "webp"]);

export function isSupportedImageExtension(filename: string): boolean {
  const dot = filename.lastIndexOf(".");
  if (dot <= 0) return false;
  return SUPPORTED_EXTENSIONS.has(filename.slice(dot + 1).toLowerCase());
}

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------

export function jsonResponse(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

export function errorResponse(message: string, status: number): Response {
  return jsonResponse({ error: message }, status);
}

/**
 * Hard caps on request body size. Bodies are read with a bounded stream and
 * cut off at the limit BEFORE parsing, so a caller cannot make the isolate
 * buffer and parse a near-platform-limit payload just to receive an error.
 * (Asset uploads legitimately carry ~11 MB of base64, so that route gets its
 * own larger cap; the JSON routes never need more than a few hundred KB.)
 */
export const MAX_JSON_BODY_BYTES = 256 * 1024; // 256 KB
export const MAX_ASSET_BODY_BYTES = 12 * 1024 * 1024; // ~11.7 MB base64 for 8 MB images

export type ReadBodyResult =
  | { ok: true; body: Record<string, unknown> }
  | { ok: false; status: 400 | 413 };

/**
 * Fetches and JSON-parses a request body with an enforced size limit. The
 * stream is consumed in chunks and aborted as soon as the cap is exceeded;
 * Content-Length is used as a fast-path rejection before reading at all.
 */
export async function readJsonBody(request: Request, maxBytes: number): Promise<ReadBodyResult> {
  const contentType = request.headers.get("Content-Type") ?? "";
  if (!contentType.toLowerCase().includes("application/json")) return { ok: false, status: 400 };

  const contentLength = Number(request.headers.get("Content-Length") ?? "0");
  if (Number.isFinite(contentLength) && contentLength > maxBytes) return { ok: false, status: 413 };

  try {
    const reader = request.body?.getReader();
    if (!reader) return { ok: false, status: 400 };
    const chunks: Uint8Array[] = [];
    let received = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      received += value.byteLength;
      if (received > maxBytes) {
        void reader.cancel().catch(() => {});
        return { ok: false, status: 413 };
      }
      chunks.push(value);
    }
    const merged = new Uint8Array(received);
    let offset = 0;
    for (const chunk of chunks) {
      merged.set(chunk, offset);
      offset += chunk.byteLength;
    }
    const parsed = JSON.parse(new TextDecoder().decode(merged));
    const body = asRecord(parsed);
    if (body === null) return { ok: false, status: 400 };
    return { ok: true, body };
  } catch {
    return { ok: false, status: 400 };
  }
}

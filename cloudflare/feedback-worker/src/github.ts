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

/** Fetches and JSON-parses a request body, guarding against malformed input. */
export async function readJsonBody(request: Request): Promise<Record<string, unknown> | null> {
  const contentType = request.headers.get("Content-Type") ?? "";
  if (!contentType.toLowerCase().includes("application/json")) return null;
  try {
    return asRecord(await request.json());
  } catch {
    return null;
  }
}

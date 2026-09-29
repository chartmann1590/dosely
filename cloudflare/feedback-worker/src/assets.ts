import { isValidBase64 } from "./github";

/**
 * Attachment upload handling.
 *
 * The Android client supplies a filename + base64 payload; the worker decides
 * the destination path underneath FEEDBACK_ASSETS_DIR. Clients can never write
 * outside that directory.
 */
export function buildAssetPath(env: { FEEDBACK_ASSETS_DIR: string }, safeFilename: string): string {
  const dir = env.FEEDBACK_ASSETS_DIR.replace(/^\/+|\/+$/g, "") || "feedback-assets";
  return `${dir}/${safeFilename}`;
}

/**
 * Validates a client-supplied asset upload. Returns null when the payload is
 * not acceptable; returns the sanitized filename and decoded size otherwise.
 */
export function validateAssetRequest(
  fileName: unknown,
  contentBase64: unknown,
): { safeFilename: string; decodedBytes: number } | null {
  if (typeof fileName !== "string" || typeof contentBase64 !== "string") return null;
  if (!isValidBase64(contentBase64)) return null;

  const decodedBytes = Math.floor((contentBase64.length * 3) / 4);
  if (decodedBytes <= 0 || decodedBytes > 8 * 1024 * 1024) return null;

  const sanitized = fileName
    .split(/[\\/]/)
    .pop()!
    .replace(/\.\.+/g, "")
    .replace(/[^A-Za-z0-9._-]/g, "_")
    .replace(/_{2,}/g, "_")
    .replace(/^_+|_+$/g, "");
  if (sanitized.length === 0 || sanitized.startsWith(".")) return null;

  const ext = sanitized.includes(".") ? sanitized.split(".").pop()!.toLowerCase() : "";
  if (!["png", "jpg", "jpeg", "webp"].includes(ext)) return null;

  return { safeFilename: sanitized, decodedBytes };
}

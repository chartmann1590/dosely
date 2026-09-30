import { isValidBase64 } from "./github";

/**
 * Attachment upload handling.
 *
 * The Android client supplies a filename + base64 payload; the worker decides
 * the destination path underneath FEEDBACK_ASSETS_DIR. Clients can never write
 * outside that directory.
 *
 * Because /api/assets is publicly reachable, the base64 payload must also
 * decode to a real image: validateAssetRequest checks magic-byte signatures
 * (and WebP's RIFF container layout) before handleUploadAsset commits anything
 * to the repository, so the endpoint cannot be used to place arbitrary
 * non-image data in repository history.
 */

/**
 * Builds the GitHub contents path for an asset. The directory comes from the
 * worker config, never from the client, so uploads stay under FEEDBACK_ASSETS_DIR.
 */
export function buildAssetPath(env: { FEEDBACK_ASSETS_DIR: string }, safeFilename: string): string {
  const dir = env.FEEDBACK_ASSETS_DIR.replace(/^\/+|\/+$/g, "") || "feedback-assets";
  return `${dir}/${safeFilename}`;
}

// Longest prefix any supported format needs: WebP's RIFF header is 12 bytes.
const IMAGE_HEADER_BYTES = 12;

// PNG: 89 50 4E 47 0D 0A 1A 0A
const PNG_MAGIC = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
// JPEG: SOI followed by any marker byte (FF D8 FF ...)
const JPEG_MAGIC = [0xff, 0xd8, 0xff];
// WebP: RIFF....WEBP — "RIFF" at 0 and "WEBP" at 8.
const RIFF_MAGIC = [0x52, 0x49, 0x46, 0x46];
const WEBP_MAGIC = [0x57, 0x45, 0x42, 0x50];

/**
 * Decodes the leading bytes of a base64 payload without buffering the whole
 * image. Returns null when the payload is not valid base64.
 */
function decodeImageHeader(contentBase64: string): Uint8Array | null {
  // 4 base64 chars encode 3 bytes; take a multiple of 4 chars so each chunk
  // decodes without padding, then trim to the header length we need.
  const chars = Math.min(contentBase64.length, Math.ceil(IMAGE_HEADER_BYTES / 3) * 4);
  let prefix = contentBase64.slice(0, chars);
  if (prefix.length % 4 !== 0) prefix += "=".repeat(4 - (prefix.length % 4));
  try {
    const raw = atob(prefix);
    const bytes = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
    return bytes;
  } catch {
    return null;
  }
}

function startsWith(bytes: Uint8Array, magic: readonly number[], offset = 0): boolean {
  if (bytes.length < offset + magic.length) return false;
  return magic.every((b, i) => bytes[offset + i] === b);
}

/**
 * True when the decoded header matches a supported image format's signature.
 * The extension is checked separately, and must agree with the detected format.
 */
export function isSupportedImageContent(header: Uint8Array | null): "png" | "jpeg" | "webp" | null {
  if (header === null) return null;
  if (startsWith(header, PNG_MAGIC)) return "png";
  if (startsWith(header, JPEG_MAGIC)) return "jpeg";
  if (startsWith(header, RIFF_MAGIC) && startsWith(header, WEBP_MAGIC, 8)) return "webp";
  return null;
}

// Content formats accepted per file extension (jpeg extension covers jpg too).
const EXTENSION_CONTENT: Record<string, readonly string[]> = {
  png: ["png"],
  jpg: ["jpeg"],
  jpeg: ["jpeg"],
  webp: ["webp"],
};

/**
 * Validates a client-supplied asset upload. Returns null when the payload is
 * not acceptable; returns the sanitized filename and decoded size otherwise.
 *
 * Checks, in order: base64 shape, decoded size, filename sanitization,
 * extension allow-list, and finally that the payload's magic bytes are a real
 * image whose format matches the claimed extension.
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
  const accepted = EXTENSION_CONTENT[ext];
  if (accepted === undefined) return null;

  // The payload must actually be an image in a format the extension claims —
  // this is what stops arbitrary non-image bytes from being committed.
  const contentFormat = isSupportedImageContent(decodeImageHeader(contentBase64));
  if (contentFormat === null || !accepted.includes(contentFormat)) return null;

  return { safeFilename: sanitized, decodedBytes };
}

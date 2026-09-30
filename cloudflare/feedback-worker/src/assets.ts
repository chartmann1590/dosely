import { isValidBase64 } from "./github";

/**
 * Attachment upload handling.
 *
 * The Android client supplies a filename + base64 payload; the worker decides
 * the destination path underneath FEEDBACK_ASSETS_DIR. Clients can never write
 * outside that directory.
 *
 * Because /api/assets is publicly reachable, the base64 payload must decode to
 * a structurally complete image — not merely carry valid magic bytes. A
 * header-only check would let an attacker prepend FF D8 FF (or the PNG/WebP
 * signature) to arbitrary bytes and have the worker commit them to repository
 * history, so validateAssetRequest parses the full image structure:
 *
 *   - PNG: 8-byte signature, a complete chunk walk (length/type/CRC32 with
 *     zlib CRC verification per chunk, IHDR first, IEND last, no truncation).
 *   - JPEG: SOI marker, a full segment walk with length checks until SOS,
 *     terminated by an EOI marker as the final two bytes.
 *   - WebP: RIFF container with declared sizes that match the payload, a
 *     VP8/VP8L/VP8X payload chunk, and no trailing garbage.
 *
 * Arbitrary non-image data therefore cannot be committed under an image
 * extension, and images with appended payloads are rejected too.
 */

/**
 * Builds the GitHub contents path for an asset. The directory comes from the
 * worker config, never from the client, so uploads stay under FEEDBACK_ASSETS_DIR.
 */
export function buildAssetPath(env: { FEEDBACK_ASSETS_DIR: string }, safeFilename: string): string {
  const dir = env.FEEDBACK_ASSETS_DIR.replace(/^\/+|\/+$/g, "") || "feedback-assets";
  return `${dir}/${safeFilename}`;
}

export type ImageFormat = "png" | "jpeg" | "webp";

const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
const JPEG_SOI = [0xff, 0xd8, 0xff];
const WEBP_MAX_FILE_BYTES = 12 * 1024 * 1024;

// Content formats accepted per file extension (jpg is an alias of jpeg).
const EXTENSION_CONTENT: Record<string, readonly ImageFormat[]> = {
  png: ["png"],
  jpg: ["jpeg"],
  jpeg: ["jpeg"],
  webp: ["webp"],
};

/** Decodes a full base64 payload into bytes. Returns null on invalid input. */
export function decodeBase64Bytes(contentBase64: string): Uint8Array | null {
  if (!isValidBase64(contentBase64)) return null;
  try {
    const raw = atob(contentBase64);
    const bytes = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
    return bytes;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// CRC32 (zlib polynomial), used to verify every PNG chunk.
// ---------------------------------------------------------------------------

const CRC32_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(bytes: Uint8Array, start: number, end: number): number {
  let crc = 0xffffffff;
  for (let i = start; i < end; i++) {
    crc = CRC32_TABLE[(crc ^ bytes[i]) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function startsWith(bytes: Uint8Array, magic: readonly number[], offset = 0): boolean {
  if (offset + magic.length > bytes.length) return false;
  return magic.every((b, i) => bytes[offset + i] === b);
}

function asciiAt(bytes: Uint8Array, offset: number): string {
  return String.fromCharCode(bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3]);
}

function u32be(bytes: Uint8Array, offset: number): number {
  return (
    bytes[offset] * 0x1000000 + (bytes[offset + 1] << 16) + (bytes[offset + 2] << 8) + bytes[offset + 3]
  );
}

function u16be(bytes: Uint8Array, offset: number): number {
  return (bytes[offset] << 8) + bytes[offset + 1];
}

function u32le(bytes: Uint8Array, offset: number): number {
  return (bytes[offset + 3] << 24) + (bytes[offset + 2] << 16) + (bytes[offset + 1] << 8) + bytes[offset];
}

// ---------------------------------------------------------------------------
// Per-format structural validation
// ---------------------------------------------------------------------------

/**
 * PNG: walks every chunk (8-byte signature, then length/type/data/CRC32
 * records) and requires IHDR to come first, IEND to terminate the file, and
 * every stored CRC to match. Rejects truncation, oversized chunk lengths, and
 * any trailing garbage after IEND.
 */
export function isCompletePng(bytes: Uint8Array): boolean {
  if (bytes.length < 33 + 8) return false; // signature + IHDR (25) + IEND (12)
  if (!startsWith(bytes, PNG_SIGNATURE)) return false;

  let offset = 8;
  let firstChunk = true;
  let sawIEND = false;
  while (offset + 12 <= bytes.length) {
    const dataLength = u32be(bytes, offset);
    const type = asciiAt(bytes, offset + 4);
    const dataStart = offset + 8;
    const chunkEnd = dataStart + dataLength;
    const crcEnd = chunkEnd + 4;
    if (dataLength > 0x7fffffff || crcEnd > bytes.length) return false;
    if (firstChunk && type !== "IHDR") return false;
    firstChunk = false;
    if (type === "IHDR" && dataLength !== 13) return false;
    const storedCrc = u32be(bytes, chunkEnd);
    if (storedCrc !== crc32(bytes, offset + 4, chunkEnd)) return false;
    if (type === "IEND") {
      if (dataLength !== 0) return false;
      sawIEND = true;
      offset = crcEnd;
      break;
    }
    offset = crcEnd;
  }
  if (!sawIEND || offset !== bytes.length) return false; // truncated or trailing data
  return true;
}

/**
 * JPEG: requires the SOI marker, walks every segment (skipping standalone
 * markers, checking each declared length), skips entropy-coded scan data
 * after each SOS (byte-stuffed 0xFF 0x00 pairs included), and requires an
 * EOI marker that terminates the payload exactly. Truncation or appended
 * garbage therefore fails the walk.
 */
export function isCompleteJpeg(bytes: Uint8Array): boolean {
  if (bytes.length < 4) return false; // SOI + at least a 2-byte marker
  if (!startsWith(bytes, JPEG_SOI)) return false;

  let offset = 2;
  let inEntropy = false;
  for (;;) {
    if (offset >= bytes.length) return false; // ran off the end before EOI
    if (inEntropy) {
      const b = bytes[offset];
      if (b !== 0xff) {
        offset += 1; // ordinary entropy byte
      } else if (offset + 1 < bytes.length && bytes[offset + 1] === 0x00) {
        offset += 2; // byte-stuffed FF 00 inside the scan
      } else {
        inEntropy = false; // a real marker follows the scan
      }
      continue;
    }
    if (offset + 2 > bytes.length) return false; // ran off the end before EOI
    if (bytes[offset] !== 0xff) return false; // marker desync
    const marker = bytes[offset + 1];
    if (marker === 0xd9) {
      // EOI: valid only if it terminates the payload exactly.
      return offset + 2 === bytes.length;
    }
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
      offset += 2; // standalone markers carry no length
      continue;
    }
    const segmentLength = u16be(bytes, offset + 2);
    if (segmentLength < 2) return false;
    offset += 2 + segmentLength;
    if (marker === 0xda) inEntropy = true; // SOS: scan data follows
  }
}

/**
 * WebP: requires the RIFF container with a declared size that matches the
 * payload (allowing the odd-byte padding byte the spec permits), the WEBP
 * tag, and a VP8/VP8L/VP8X payload chunk whose declared size fits.
 */
export function isCompleteWebp(bytes: Uint8Array): boolean {
  // 12-byte RIFF/WEBP header + an 8-byte chunk header + at least 1 data byte.
  if (bytes.length < 21) return false;
  if (asciiAt(bytes, 0) !== "RIFF") return false;
  if (asciiAt(bytes, 8) !== "WEBP") return false;

  const declaredSize = u32le(bytes, 4);
  const expected = bytes.length - 8;
  if (declaredSize !== expected && declaredSize !== expected - 1) return false;
  if (declaredSize > WEBP_MAX_FILE_BYTES) return false;

  const chunkType = asciiAt(bytes, 12);
  if (chunkType !== "VP8 " && chunkType !== "VP8L" && chunkType !== "VP8X") return false;
  const chunkSize = u32le(bytes, 16);
  return chunkSize <= bytes.length - 20;
}

/**
 * True when the bytes are a structurally complete, internally consistent
 * image in one of the supported formats.
 */
export function isSupportedImageContent(bytes: Uint8Array | null): ImageFormat | null {
  if (bytes === null) return null;
  if (isCompletePng(bytes)) return "png";
  if (isCompleteJpeg(bytes)) return "jpeg";
  if (isCompleteWebp(bytes)) return "webp";
  return null;
}

/**
 * Validates a client-supplied asset upload. Returns null when the payload is
 * not acceptable; returns the sanitized filename and decoded size otherwise.
 *
 * Checks, in order: base64 shape, decoded size, filename sanitization,
 * extension allow-list, and finally a full structural image parse whose
 * detected format must match the claimed extension.
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

  // The payload must be a complete, internally consistent image whose format
  // matches the extension — this is what stops arbitrary bytes (even bytes
  // disguised with a real magic-number prefix) from being committed.
  const bytes = decodeBase64Bytes(contentBase64);
  const contentFormat = isSupportedImageContent(bytes);
  if (contentFormat === null || !accepted.includes(contentFormat)) return null;

  return { safeFilename: sanitized, decodedBytes };
}

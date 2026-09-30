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
 * history, so validateAssetRequest parses the full image structure. Because a
 * valid container can itself smuggle data, the walk is also an ALLOWLIST:
 *
 *   - PNG: 8-byte signature, a complete chunk walk (length/type/CRC32 with
 *     zlib CRC verification per chunk, IHDR first, IEND last, no truncation),
 *     only critical/recognized chunks accepted, at least one IDAT required.
 *     tEXt/iTXt/zTXt metadata chunks (a classic smuggling channel) are
 *     rejected, so arbitrary text cannot ride inside a well-formed PNG.
 *   - JPEG: SOI marker, a full segment walk with length checks until SOS,
 *     terminated by an EOI marker as the final two bytes. Comment (COM) and
 *     application (APPn) segments — free-form payload areas — are rejected
 *     outright rather than length-checked, so arbitrary bytes cannot be
 *     hidden in length-delimited segments.
 *   - WebP: RIFF container with declared sizes that match the payload, a
 *     VP8/VP8L/VP8X payload chunk, and no trailing garbage. Metadata sidecar
 *     chunks (ICCP/EXIF/XMP) and animation chunks are rejected — the client
 *     re-encodes to plain PNG anyway, so no legitimate upload ever carries
 *     them.
 *
 * Arbitrary non-image data therefore cannot be committed under an image
 * extension, and data smuggled inside otherwise-valid image containers is
 * rejected too.
 */

/**
 * Builds the GitHub contents path for an asset. The directory comes from the
 * worker config, never from the client, so uploads stay under FEEDBACK_ASSETS_DIR.
 * The dir also defaults here (mirroring the Env wiring) so a missing variable
 * degrades to the standard location instead of raising a 500 from the handler.
 */
export function buildAssetPath(env: { FEEDBACK_ASSETS_DIR?: string }, safeFilename: string): string {
  const dir = (env.FEEDBACK_ASSETS_DIR ?? "").replace(/^\/+|\/+$/g, "") || "feedback-assets";
  return `${dir}/${safeFilename}`;
}

export type ImageFormat = "png" | "jpeg" | "webp";

const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
const JPEG_SOI = [0xff, 0xd8, 0xff];
const WEBP_MAX_FILE_BYTES = 12 * 1024 * 1024;

/**
 * PNG chunks the client's re-encoder can legitimately emit, plus the palette
 * chunks any color-type PNG may require. Everything else — tEXt/iTXt/zTXt
 * (arbitrary text), unknown private chunks, and other ancillary chunk types —
 * is rejected so a well-formed PNG container cannot carry smuggled payloads.
 */
const PNG_ALLOWED_CHUNKS = new Set([
  "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM",
  "sRGB", "iCCP", "sBIT", "bKGD", "hIST", "sPLT", "pHYs", "tIME",
]);

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
  let idatBytes = 0;
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
    if (!PNG_ALLOWED_CHUNKS.has(type)) return false; // unknown/ancillary chunks rejected
    if (type === "IDAT") idatBytes += dataLength;
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
  if (idatBytes === 0) return false; // no image data: container-only smuggling shell
  return true;
}

/**
 * JPEG: requires the SOI marker, walks every segment (skipping standalone
 * markers, checking each declared length), and requires an actual frame:
 * a SOFn frame header with sane geometry must appear before any SOS scan,
 * entropy-coded scan data is skipped with byte-stuffing (0xFF 0x00) rules,
 * and an EOI marker must terminate the payload exactly. A bare SOI+EOI stub
 * or junk smuggled inside length-delimited APP segments therefore fails.
 */
export function isCompleteJpeg(bytes: Uint8Array): boolean {
  if (bytes.length < 4) return false; // SOI + at least a 2-byte marker
  if (!startsWith(bytes, JPEG_SOI)) return false;

  let offset = 2;
  let inEntropy = false;
  let sawSOF = false;
  let sawSOS = false;
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
      // EOI: valid only if it terminates the payload exactly and a real
      // frame with scan data was present (no SOI+EOI-only stubs).
      return sawSOF && sawSOS && offset + 2 === bytes.length;
    }
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
      offset += 2; // standalone markers carry no length
      continue;
    }
    if (marker === 0xfe) return false; // COM: free-form comment segment
    if (marker >= 0xe0 && marker <= 0xef) return false; // APPn: free-form payload segments
    const segmentLength = u16be(bytes, offset + 2);
    if (segmentLength < 2) return false;
    if (marker === 0xda) {
      if (!sawSOF) return false; // scan data before any frame header
      sawSOS = true;
      inEntropy = true;
    } else if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
      // SOFn frame header: validate the geometry block instead of trusting
      // the segment blindly. Layout: precision(1) height(2) width(2)
      // components(1) [per-component 3 bytes ...].
      if (segmentLength < 8) return false;
      const soFDataEnd = offset + 2 + segmentLength;
      if (soFDataEnd > bytes.length) return false;
      const precision = bytes[offset + 4];
      const height = u16be(bytes, offset + 5);
      const width = u16be(bytes, offset + 7);
      const components = bytes[offset + 9];
      if (precision < 8 || precision > 16) return false;
      if (height < 1 || width < 1) return false;
      if (components < 1 || components > 4) return false;
      sawSOF = true;
    }
    offset += 2 + segmentLength;
  }
}

/**
 * WebP chunks accepted from this client. The Android app re-encodes every
 * attachment to plain PNG, so a VP8X-capable WebP with metadata sidecars or
 * animation frames never arrives legitimately — and each of those chunk types
 * is a documented arbitrary-data channel. Only a bare VP8 (lossy) or VP8L
 * (lossless) bitstream is accepted.
 */
const WEBP_ALLOWED_CHUNKS = new Set(["VP8 ", "VP8L"]);

/**
 * WebP: requires the RIFF/WEBP container with a declared size that matches
 * the payload (allowing the odd-byte padding byte the spec permits), then
 * walks every chunk — FourCC, little-endian size, data, pad byte — until the
 * exact RIFF boundary. The first chunk must be the VP8/VP8L bitstream, every
 * FourCC must be an allowed chunk type (VP8X/metadata/animation containers
 * are rejected), and nothing may trail the container. A one-byte VP8X
 * followed by arbitrary data therefore fails the walk.
 */
export function isCompleteWebp(bytes: Uint8Array): boolean {
  // 12-byte RIFF/WEBP header + an 8-byte chunk header + at least 1 data byte.
  if (bytes.length < 21) return false;
  if (asciiAt(bytes, 0) !== "RIFF" || asciiAt(bytes, 8) !== "WEBP") return false;

  const declaredSize = u32le(bytes, 4);
  if (declaredSize > WEBP_MAX_FILE_BYTES) return false;
  // "RIFF" + size + payload; the payload may carry one pad byte when its
  // true size is odd, so the file is declaredSize+8 or declaredSize+9 bytes.
  if (bytes.length !== declaredSize + 8 && bytes.length !== declaredSize + 9) return false;

  const riffEnd = Math.min(8 + declaredSize, bytes.length);
  let offset = 12;
  let sawPayloadChunk = false;
  while (offset + 8 <= riffEnd) {
    const fourcc = asciiAt(bytes, offset);
    const chunkSize = u32le(bytes, offset + 4);
    const dataEnd = offset + 8 + chunkSize;
    if (dataEnd > riffEnd) return false;
    if (!WEBP_ALLOWED_CHUNKS.has(fourcc)) return false;
    if (!sawPayloadChunk) sawPayloadChunk = true;
    offset = dataEnd % 2 === 1 ? dataEnd + 1 : dataEnd; // odd chunks carry a pad byte
  }
  return sawPayloadChunk && (offset === riffEnd || offset === riffEnd + 1);
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

export interface ValidatedAsset {
  safeFilename: string;
  /** Decoded image size in bytes. */
  decodedBytes: number;
  /** Exact size of the file that will be committed. */
  fileSize: number;
  /** Git blob SHA-1 of the decoded content (what GitHub Contents will store). */
  blobSha: string;
}

/**
 * Validates a client-supplied asset upload. Returns null when the payload is
 * not acceptable; returns the sanitized filename, decoded size, exact file
 * size, and Git blob SHA otherwise (the SHA lets callers delete the blob if a
 * later step of a multi-step operation fails).
 *
 * Checks, in order: base64 shape, decoded size, filename sanitization,
 * extension allow-list, and finally a full structural image parse whose
 * detected format must match the claimed extension.
 */
export async function validateAssetRequest(
  fileName: unknown,
  contentBase64: unknown,
): Promise<ValidatedAsset | null> {
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
  if (contentFormat === null || bytes === null || !accepted.includes(contentFormat)) return null;

  return {
    safeFilename: sanitized,
    decodedBytes,
    fileSize: bytes.length,
    blobSha: await gitBlobSha(bytes),
  };
}

/**
 * Computes the Git blob SHA-1 ("blob <len>\0" + content) that GitHub Contents
 * will report for these bytes, so callers can address the object for deletion
 * without a second round-trip.
 */
export async function gitBlobSha(bytes: Uint8Array): Promise<string> {
  const header = new TextEncoder().encode(`blob ${bytes.length}\u0000`);
  const merged = new Uint8Array(header.length + bytes.length);
  merged.set(header, 0);
  merged.set(bytes, header.length);
  const digest = await crypto.subtle.digest("SHA-1", merged);
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

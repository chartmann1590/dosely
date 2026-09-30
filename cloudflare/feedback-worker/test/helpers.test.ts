import {
  byteLength,
  isFeedbackIssue,
  isNonEmptyString,
  isSupportedImageExtension,
  isValidBase64,
  isValidIssueNumber,
  normalizeAsset,
  normalizeComment,
  normalizeIssue,
  sanitizeFilename,
} from "../src/github";
import { buildAssetPath, isSupportedImageContent, validateAssetRequest } from "../src/assets";
import { describe, expect, it } from "vitest";

describe("isFeedbackIssue guard", () => {
  it("accepts issues titled with the feedback marker namespace", () => {
    expect(isFeedbackIssue({ title: "[Feedback] App crashes on start" })).toBe(true);
    expect(isFeedbackIssue({ title: "[Feedback Test] integration verification" })).toBe(true);
  });

  it("rejects unrelated issues and pull requests", () => {
    expect(isFeedbackIssue({ title: "CI failed on main" })).toBe(false);
    expect(isFeedbackIssue({ title: "" })).toBe(false);
    expect(isFeedbackIssue({})).toBe(false);
    expect(isFeedbackIssue({ title: "[Feedback] looks safe", pull_request: { url: "https://api.github.com/x" } })).toBe(false);
  });
});

describe("normalizeIssue", () => {
  it("normalizes a full GitHub issue payload", () => {
    const result = normalizeIssue({
      number: 12,
      title: "[Feedback] Crash",
      state: "open",
      html_url: "https://github.com/o/r/issues/12",
      created_at: "2026-09-29T00:00:00Z",
      body: "Details",
    });
    expect(result).toEqual({
      number: 12,
      title: "[Feedback] Crash",
      state: "open",
      htmlUrl: "https://github.com/o/r/issues/12",
      createdAt: "2026-09-29T00:00:00Z",
      body: "Details",
    });
  });

  it("omits empty body and coerces unknown state to open", () => {
    const result = normalizeIssue({ number: 1, state: "SomethingElse" });
    expect(result.body).toBeUndefined();
    expect(result.state).toBe("open");
  });
});

describe("normalizeComment", () => {
  it("normalizes a GitHub comment with user", () => {
    const result = normalizeComment({
      id: 7,
      body: "Hi",
      created_at: "2026-09-29T00:00:00Z",
      user: { login: "alice" },
    });
    expect(result.user.login).toBe("alice");
    expect(result.id).toBe(7);
  });

  it("falls back to 'unknown' when user is missing", () => {
    const result = normalizeComment({ id: 7, body: "Hi", created_at: "x", user: null });
    expect(result.user.login).toBe("unknown");
  });
});

describe("normalizeAsset", () => {
  it("extracts download and html urls", () => {
    const result = normalizeAsset({
      content: { download_url: "https://x/y.png", html_url: "https://h/y.png" },
    });
    expect(result.downloadUrl).toBe("https://x/y.png");
    expect(result.htmlUrl).toBe("https://h/y.png");
  });

  it("returns null urls when content is missing", () => {
    expect(normalizeAsset({}).downloadUrl).toBeNull();
  });
});

describe("sanitizeFilename", () => {
  it("strips path traversal segments", () => {
    expect(sanitizeFilename("../../etc/passwd.png")).toBe("passwd.png");
    expect(sanitizeFilename("..\\..\\evil.png")).toBe("evil.png");
  });

  it("removes dangerous characters and leading dots", () => {
    expect(sanitizeFilename("..secret.png")).toBe("secret.png");
    expect(sanitizeFilename("a b/c d.png")).toBe("c_d.png");
  });

  it("rejects empty or dot-only names", () => {
    expect(sanitizeFilename("")).toBeNull();
    expect(sanitizeFilename("...")).toBeNull();
    expect(sanitizeFilename(null)).toBeNull();
  });
});

describe("isSupportedImageExtension", () => {
  it("accepts png/jpg/jpeg/webp case-insensitively", () => {
    expect(isSupportedImageExtension("a.PNG")).toBe(true);
    expect(isSupportedImageExtension("a.Jpeg")).toBe(true);
    expect(isSupportedImageExtension("a.webp")).toBe(true);
  });

  it("rejects other extensions and missing ones", () => {
    expect(isSupportedImageExtension("a.exe")).toBe(false);
    expect(isSupportedImageExtension("noext")).toBe(false);
    expect(isSupportedImageExtension(".hidden")).toBe(false);
  });
});

describe("validation helpers", () => {
  it("issue numbers must be positive integers in range", () => {
    expect(isValidIssueNumber(1)).toBe(true);
    expect(isValidIssueNumber(123)).toBe(true);
    expect(isValidIssueNumber(0)).toBe(false);
    expect(isValidIssueNumber(-5)).toBe(false);
    expect(isValidIssueNumber(1.5)).toBe(false);
    expect(isValidIssueNumber(Number.MAX_SAFE_INTEGER + 1)).toBe(false);
  });

  it("non-empty strings respect the byte limit", () => {
    expect(isNonEmptyString("hello", 100)).toBe(true);
    expect(isNonEmptyString("   ", 100)).toBe(false);
    expect(isNonEmptyString("x".repeat(101), 100)).toBe(false);
  });

  it("base64 shape is enforced", () => {
    expect(isValidBase64("aGVsbG8=")).toBe(true);
    expect(isValidBase64("not base64!!")).toBe(false);
    expect(isValidBase64("abc")).toBe(false); // length not multiple of 4
  });

  it("byteLength counts UTF-8 bytes", () => {
    expect(byteLength("abc")).toBe(3);
    expect(byteLength("€")).toBe(3);
  });
});

describe("asset upload validation", () => {
  // Helpers to build base64 fixtures from raw bytes (node + workers both ship btoa).
  const b64 = (bytes: number[]) => btoa(String.fromCharCode(...bytes));
  const bytesOf = (base64: string) => Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
  const u16 = (v: number) => [(v >> 8) & 0xff, v & 0xff];
  const u32be = (v: number) => [
    (v >>> 24) & 0xff,
    (v >>> 16) & 0xff,
    (v >>> 8) & 0xff,
    v & 0xff,
  ];
  const u32le = (v: number) => [v & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff, (v >>> 24) & 0xff];

  const PNG_SIG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

  // CRC32 (zlib), needed to assemble well-formed PNG chunks.
  const crcTable: number[] = [];
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crcTable[n] = c >>> 0;
  }
  const crc32 = (bytes: number[]) => {
    let crc = 0xffffffff;
    for (const b of bytes) crc = crcTable[(crc ^ b) & 0xff] ^ (crc >>> 8);
    return (crc ^ 0xffffffff) >>> 0;
  };
  const pngChunk = (type: string, data: number[]) => {
    const body = [...type.split("").map((c) => c.charCodeAt(0)), ...data];
    return [...u32be(data.length), ...body, ...u32be(crc32(body))];
  };

  // Complete, structurally valid 1x1 PNG: IHDR + IDAT + IEND (real-world bytes).
  const tinyPngB64 =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
  // Structurally complete JPEG: SOI + APP0(JFIF) + DQT + DHT + SOF0 + SOS +
  // entropy data + EOI, with every segment length matching its payload.
  const jpegBytes: number[] = [
    0xff, 0xd8, // SOI
    // APP0/JFIF: 14 payload bytes, declared length 16.
    0xff, 0xe0, ...u16(16), 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
    // DQT: 64 payload bytes, declared length 66.
    0xff, 0xdb, ...u16(66), ...Array.from({ length: 64 }, (_, i) => i),
    // DHT: 1 + 16 + 1 = 18 payload bytes, declared length 20.
    0xff, 0xc4, ...u16(20), 0x00, ...Array.from({ length: 16 }, (_, i) => (i === 0 ? 1 : 0)), 0x01,
    // SOF0: precision + height + width + 1 component block = 9 payload bytes, length 11.
    0xff, 0xc0, ...u16(11), 0x08, ...u16(1), ...u16(1), 0x01, 0x01, 0x11, 0x00,
    // SOS: 6 payload bytes, declared length 8, then 2 bytes of entropy data.
    0xff, 0xda, ...u16(8), 0x01, 0x01, 0x00, 0x00, 0x3f, 0x00, 0xfc, 0xaa,
    0xff, 0xd9, // EOI
  ];
  const jpegHeaderB64 = b64(jpegBytes);
  // Structurally valid WebP: RIFF/WEBP + VP8X chunk (declared sizes consistent:
  // 30 total bytes -> RIFF size 22; VP8X chunk size 10 matches its 10 data bytes).
  const webpBytes: number[] = [
    0x52, 0x49, 0x46, 0x46, ...u32le(22), 0x57, 0x45, 0x42, 0x50,
    0x56, 0x50, 0x38, 0x58, ...u32le(10), 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
  ];
  const webpHeaderB64 = b64(webpBytes);
  // Plain UTF-8 text — the attack payload class: valid base64, real bytes,
  // but not an image at all.
  const textB64 = b64(Array.from("#!/bin/sh\nrm -rf /\n", (c) => c.charCodeAt(0)));

  it("accepts structurally complete uploads whose format matches the extension", async () => {
    for (const [name, payload] of [
      ["issue-20260929-101010-a1b2c3.png", tinyPngB64],
      ["comment-1-20260929-101010-a1b2c3.jpg", jpegHeaderB64],
      ["comment-1-20260929-101010-a1b2c3.jpeg", jpegHeaderB64],
      ["shot.webp", webpHeaderB64],
    ] as const) {
      const parsed = await validateAssetRequest(name, payload);
      expect(parsed).not.toBeNull();
      expect(parsed!.safeFilename).toBe(name);
    }
  });

  it("rejects header-prefix attacks: magic bytes followed by arbitrary data", async () => {
    // The exact class Codex flagged: a real-looking header prepended to junk.
    const jpegPrefixPlusJunk = b64([
      0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01,
      ...Array.from("#!/bin/sh\nrm -rf /", (c) => c.charCodeAt(0)),
    ]);
    expect(await validateAssetRequest("evil.jpg", jpegPrefixPlusJunk)).toBeNull();
    // PNG signature + one garbage chunk with a bogus CRC.
    const pngSigPlusJunk = b64([...PNG_SIG, 0x00, 0x00, 0x00, 0x0a, 0x49, 0x44, 0x41, 0x54, ...Array(10).fill(0x41), 0xde, 0xad, 0xbe, 0xef]);
    expect(await validateAssetRequest("evil.png", pngSigPlusJunk)).toBeNull();
    // Truncated PNG (no IEND).
    const truncatedPng = b64([
      ...PNG_SIG,
      ...pngChunk("IHDR", [...u32be(1), ...u32be(1), 8, 2, 0, 0, 0]),
    ]);
    expect(await validateAssetRequest("cut.png", truncatedPng)).toBeNull();
    // PNG with a corrupted CRC.
    const corruptedCrc = b64([
      ...PNG_SIG,
      ...pngChunk("IHDR", [...u32be(1), ...u32be(1), 8, 2, 0, 0, 0]).slice(0, -4),
      ...u32be(0xdeadbeef),
      ...pngChunk("IEND", []),
    ]);
    expect(await validateAssetRequest("crc.png", corruptedCrc)).toBeNull();
    // Trailing garbage after IEND.
    const trailingJunk = b64([
      ...PNG_SIG,
      ...pngChunk("IHDR", [...u32be(1), ...u32be(1), 8, 2, 0, 0, 0]),
      ...pngChunk("IEND", []),
      0x41, 0x41, 0x41, 0x41,
    ]);
    expect(await validateAssetRequest("tail.png", trailingJunk)).toBeNull();
  });

  it("rejects non-image bytes regardless of the file extension", async () => {
    expect(await validateAssetRequest("payload.png", textB64)).toBeNull();
    expect(await validateAssetRequest("payload.jpg", textB64)).toBeNull();
    expect(await validateAssetRequest("payload.webp", textB64)).toBeNull();
  });

  it("rejects payloads whose real format disagrees with the extension", async () => {
    expect(await validateAssetRequest("photo.jpg", tinyPngB64)).toBeNull();
    expect(await validateAssetRequest("photo.png", jpegHeaderB64)).toBeNull();
    expect(await validateAssetRequest("photo.webp", tinyPngB64)).toBeNull();
  });

  it("rejects payloads too short to contain an image structure", async () => {
    const oneByte = b64([0x89]);
    const riffOnly = b64([0x52, 0x49, 0x46, 0x46]);
    expect(await validateAssetRequest("a.png", oneByte)).toBeNull();
    expect(await validateAssetRequest("a.webp", riffOnly)).toBeNull();
  });

  it("isSupportedImageContent classifies structurally complete images", async () => {
    expect(isSupportedImageContent(bytesOf(tinyPngB64))).toBe("png");
    expect(isSupportedImageContent(bytesOf(jpegHeaderB64))).toBe("jpeg");
    expect(isSupportedImageContent(bytesOf(webpHeaderB64))).toBe("webp");
    expect(isSupportedImageContent(bytesOf(textB64))).toBeNull();
    expect(isSupportedImageContent(null)).toBeNull();
  });

  it("rejects unsupported extensions", async () => {
    expect(await validateAssetRequest("evil.exe", tinyPngB64)).toBeNull();
  });

  it("rejects invalid base64", async () => {
    expect(await validateAssetRequest("ok.png", "!!not-base64!!")).toBeNull();
  });

  it("rejects oversized payloads", async () => {
    const big = "A".repeat((8 * 1024 * 1024 + 1) * 4);
    expect(await validateAssetRequest("big.png", big)).toBeNull();
  });

  it("rejects payloads with malformed base64 padding in the header", async () => {
    // isValidBase64 accepts this; the header decoder must not throw on it.
    expect(await validateAssetRequest("ok.png", "aGVsbG8 world")).toBeNull();
  });

  it("builds paths strictly under the assets dir", async () => {
    expect(buildAssetPath({ FEEDBACK_ASSETS_DIR: "feedback-assets" }, "x.png")).toBe(
      "feedback-assets/x.png",
    );
    expect(buildAssetPath({ FEEDBACK_ASSETS_DIR: "/feedback-assets/" }, "x.png")).toBe(
      "feedback-assets/x.png",
    );
  });
});

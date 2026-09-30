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

  // Real 1x1 PNG (signature + IHDR + IDAT + IEND).
  const tinyPngB64 =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
  // Minimal header-shaped JPEG (FF D8 FF E0 + JFIF APP0 marker bytes).
  const jpegHeaderB64 = b64([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01]);
  // RIFF....WEBP container header with a VP8 chunk marker.
  const webpHeaderB64 = b64([
    0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x20,
  ]);
  // Plain UTF-8 text — the attack payload class: valid base64, real bytes,
  // but not an image at all.
  const textB64 = b64(Array.from("#!/bin/sh\nrm -rf /\n", (c) => c.charCodeAt(0)));

  it("accepts uploads whose bytes match the claimed format", () => {
    for (const [name, payload] of [
      ["issue-20260929-101010-a1b2c3.png", tinyPngB64],
      ["comment-1-20260929-101010-a1b2c3.jpg", jpegHeaderB64],
      ["comment-1-20260929-101010-a1b2c3.jpeg", jpegHeaderB64],
      ["shot.webp", webpHeaderB64],
    ] as const) {
      const parsed = validateAssetRequest(name, payload);
      expect(parsed).not.toBeNull();
      expect(parsed!.safeFilename).toBe(name);
    }
  });

  it("rejects non-image bytes regardless of the file extension", () => {
    expect(validateAssetRequest("payload.png", textB64)).toBeNull();
    expect(validateAssetRequest("payload.jpg", textB64)).toBeNull();
    expect(validateAssetRequest("payload.webp", textB64)).toBeNull();
  });

  it("rejects payloads whose real format disagrees with the extension", () => {
    expect(validateAssetRequest("photo.jpg", tinyPngB64)).toBeNull();
    expect(validateAssetRequest("photo.png", jpegHeaderB64)).toBeNull();
    expect(validateAssetRequest("photo.webp", tinyPngB64)).toBeNull();
  });

  it("rejects payloads too short to contain an image signature", () => {
    const oneByte = b64([0x89]);
    const riffOnly = b64([0x52, 0x49, 0x46, 0x46]);
    expect(validateAssetRequest("a.png", oneByte)).toBeNull();
    expect(validateAssetRequest("a.webp", riffOnly)).toBeNull(); // missing WEBP at offset 8
  });

  it("isSupportedImageContent classifies headers", () => {
    expect(isSupportedImageContent(bytesOf(tinyPngB64))).toBe("png");
    expect(isSupportedImageContent(bytesOf(jpegHeaderB64))).toBe("jpeg");
    expect(isSupportedImageContent(bytesOf(webpHeaderB64))).toBe("webp");
    expect(isSupportedImageContent(bytesOf(textB64))).toBeNull();
    expect(isSupportedImageContent(null)).toBeNull();
  });

  it("rejects unsupported extensions", () => {
    expect(validateAssetRequest("evil.exe", tinyPngB64)).toBeNull();
  });

  it("rejects invalid base64", () => {
    expect(validateAssetRequest("ok.png", "!!not-base64!!")).toBeNull();
  });

  it("rejects oversized payloads", () => {
    const big = "A".repeat((8 * 1024 * 1024 + 1) * 4);
    expect(validateAssetRequest("big.png", big)).toBeNull();
  });

  it("rejects payloads with malformed base64 padding in the header", () => {
    // isValidBase64 accepts this; the header decoder must not throw on it.
    expect(validateAssetRequest("ok.png", "aGVsbG8 world")).toBeNull();
  });

  it("builds paths strictly under the assets dir", () => {
    expect(buildAssetPath({ FEEDBACK_ASSETS_DIR: "feedback-assets" }, "x.png")).toBe(
      "feedback-assets/x.png",
    );
    expect(buildAssetPath({ FEEDBACK_ASSETS_DIR: "/feedback-assets/" }, "x.png")).toBe(
      "feedback-assets/x.png",
    );
  });
});

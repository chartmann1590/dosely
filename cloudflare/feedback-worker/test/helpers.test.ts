import {
  byteLength,
  isNonEmptyString,
  isSupportedImageExtension,
  isValidBase64,
  isValidIssueNumber,
  normalizeAsset,
  normalizeComment,
  normalizeIssue,
  sanitizeFilename,
} from "../src/github";
import { buildAssetPath, validateAssetRequest } from "../src/assets";
import { describe, expect, it } from "vitest";

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
  const tinyPngB64 = "iVBORw0KGgoAAAANSUhEUg==";

  it("accepts a valid png upload", () => {
    const parsed = validateAssetRequest("issue-20260929-101010-a1b2c3.png", tinyPngB64);
    expect(parsed).not.toBeNull();
    expect(parsed!.safeFilename).toBe("issue-20260929-101010-a1b2c3.png");
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

  it("builds paths strictly under the assets dir", () => {
    expect(buildAssetPath({ FEEDBACK_ASSETS_DIR: "feedback-assets" }, "x.png")).toBe(
      "feedback-assets/x.png",
    );
    expect(buildAssetPath({ FEEDBACK_ASSETS_DIR: "/feedback-assets/" }, "x.png")).toBe(
      "feedback-assets/x.png",
    );
  });
});

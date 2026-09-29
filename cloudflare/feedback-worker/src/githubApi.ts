import type { Env } from "./env";

/**
 * GitHub REST API access for the feedback worker.
 *
 * The worker is the only place the GitHub token exists at runtime, stored as
 * the Cloudflare secret GITHUB_TOKEN. The Authorization header is never logged
 * and never included in error payloads returned to clients.
 */

const GITHUB_API_BASE = "https://api.github.com";
const GITHUB_API_VERSION = "2022-11-28";
const GITHUB_ACCEPT = "application/vnd.github+json";
const USER_AGENT = "Dosely-Feedback-Worker";

/** Error raised when the GitHub API answers with a non-2xx response. */
export class GitHubApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "GitHubApiError";
  }
}

/**
 * Performs an authenticated GitHub API request. The Authorization header is
 * never logged and never included in error payloads returned to clients.
 */
export async function githubRequest(
  env: Env,
  path: string,
  init?: RequestInit,
): Promise<Response> {
  const url = `${GITHUB_API_BASE}${path}`;
  const headers = new Headers(init?.headers);
  headers.set("Accept", GITHUB_ACCEPT);
  headers.set("X-GitHub-Api-Version", GITHUB_API_VERSION);
  headers.set("User-Agent", USER_AGENT);
  headers.set("Authorization", `Bearer ${env.GITHUB_TOKEN}`);
  if (init?.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(url, { ...init, headers });
  if (!response.ok) {
    throw new GitHubApiError(response.status, `GitHub API returned ${response.status}`);
  }
  return response;
}

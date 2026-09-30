/**
 * Environment bindings for the feedback worker.
 *
 * GITHUB_TOKEN is a Cloudflare SECRET (wrangler secret put GITHUB_TOKEN).
 * It must never appear in this repo, logs, or worker responses.
 *
 * FEEDBACK_WORKER_API_KEY is an optional Cloudflare SECRET. When set, every
 * /api request must carry it in the X-Api-Key header; when unset the worker
 * falls back to rate limiting + validation only (development mode). The value
 * is compared with a timing-safe digest and never logged or returned.
 */
export interface Env {
  GITHUB_TOKEN: string;
  GITHUB_REPO_OWNER: string;
  GITHUB_REPO_NAME: string;
  /** Optional; defaults to "feedback-assets" when unset. */
  FEEDBACK_ASSETS_DIR?: string;
  /** Optional shared secret required in the X-Api-Key header when set. */
  FEEDBACK_WORKER_API_KEY?: string;
}

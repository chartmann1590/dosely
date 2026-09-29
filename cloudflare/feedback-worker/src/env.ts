/**
 * Environment bindings for the feedback worker.
 *
 * GITHUB_TOKEN is a Cloudflare SECRET (wrangler secret put GITHUB_TOKEN).
 * It must never appear in this repo, logs, or worker responses.
 */
export interface Env {
  GITHUB_TOKEN: string;
  GITHUB_REPO_OWNER: string;
  GITHUB_REPO_NAME: string;
  FEEDBACK_ASSETS_DIR: string;
}

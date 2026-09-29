# Dosely Feedback Worker

Cloudflare Worker that backs the in-app **Support & Feedback** feature. The
Android app talks **only** to this worker — never to GitHub directly — and this
worker is the single place the GitHub credential exists at runtime.

## Architecture

```text
Android app  --HTTPS/JSON-->  Cloudflare Worker  --GitHub REST API-->  GitHub repository
                                                  Bearer env.GITHUB_TOKEN   Issues / Comments / feedback-assets/
```

The Android client is untrusted: it cannot choose the GitHub host, repository,
path, or credentials. Those are fixed by this worker's configuration and secret.

## Routes

| Method | Path                          | Purpose                        |
|--------|-------------------------------|--------------------------------|
| GET    | `/health`                     | Liveness + repo configuration check |
| POST   | `/api/issues`                 | Create a feedback issue        |
| GET    | `/api/issues/:number`         | Fetch issue state              |
| GET    | `/api/issues/:number/comments`| List comments (oldest first)   |
| POST   | `/api/issues/:number/comments`| Post a reply                   |
| POST   | `/api/assets`                 | Upload a screenshot/image      |

## Configuration

Non-secret values live in `wrangler.jsonc`:

- `GITHUB_REPO_OWNER`
- `GITHUB_REPO_NAME`
- `FEEDBACK_ASSETS_DIR`

The GitHub token is a **Cloudflare Worker secret** named `GITHUB_TOKEN`:

```bash
printf '%s' '<token>' | npx wrangler secret put GITHUB_TOKEN
```

It is never stored in this repository, never logged, and never returned by any
endpoint.

## Validation & abuse protection

- Fixed repository; no generic GitHub proxying.
- Strict route/method/Content-Type validation.
- Issue title ≤ 200 chars; issue body ≤ 50 KB; comment ≤ 25 KB.
- Images: Base64 ≤ ~11 MB (~8 MB decoded), extensions limited to png/jpg/jpeg/webp.
- Filename sanitization: path traversal stripped; uploads always land under `feedback-assets/`.
- Issue numbers validated as positive integers.
- Per-IP rate limiting: 30 POST requests per hour (best-effort, per isolate).

## Android configuration

The app reads the (non-secret) worker URL from `BuildConfig.FEEDBACK_WORKER_URL`,
overridable via `-Pfeedback.worker.url=...` or the `FEEDBACK_WORKER_URL` env var.
If it is missing, the feedback UI disables submission and shows a configuration
error instead of crashing.

## Development

```bash
npm install
npm run typecheck
npm test
npm run deploy   # wrangler deploy
```
